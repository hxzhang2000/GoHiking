package com.gohiking.core.data.recording

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorManager
import com.gohiking.core.common.coroutine.ApplicationScope
import com.gohiking.core.database.GhDatabase
import com.gohiking.core.database.entity.MarkerEntity
import com.gohiking.core.database.entity.RecordingStateEntity
import com.gohiking.core.database.entity.TrackPointEntity
import com.gohiking.core.database.entity.TripEntity
import com.gohiking.core.data.stats.CalorieCalculator
import com.gohiking.core.location.LocationProvider
import com.gohiking.core.location.altitude.AltitudeFuser
import com.gohiking.core.location.altitude.ThresholdAccumulator
import com.gohiking.core.location.geo.GeoMath
import com.gohiking.core.location.sampler.TrackSampler
import com.gohiking.core.location.step.StepSource
import com.gohiking.core.location.step.StepSourceFactory
import com.gohiking.core.location.step.StepWire
import androidx.room.withTransaction
import dagger.hilt.android.qualifiers.ApplicationContext
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.Dispatchers
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import timber.log.Timber

/**
 * 记录会话——记录过程中唯一可变状态源（DEV 冻结决策 12 / §6.1）。
 * Service 只管生命周期与通知；UI 只读 [state] / [samples]。
 *
 * 批量写入（PRD 6.3.7）：每 10 点 / 每 5 秒 flush；recording_state 每 10 秒持久化（F-REC-09）。
 * 暂停语义：anchor 保留（F-ALERT-16），segmentIndex 在 resume 时 +1，暂停期间停止定位。
 */
@Singleton
class RecordingSession @Inject constructor(
    private val locationProvider: LocationProvider,
    private val db: GhDatabase,
    @ApplicationContext private val context: Context,
    @ApplicationScope private val scope: CoroutineScope,
) {
    private val _state = MutableStateFlow<SessionState>(SessionState.Idle)
    val state: StateFlow<SessionState> = _state.asStateFlow()

    private val _samples = MutableSharedFlow<TrackSample>(
        extraBufferCapacity = 64,
        onBufferOverflow = BufferOverflow.DROP_OLDEST,
    )
    val samples: SharedFlow<TrackSample> = _samples.asSharedFlow()

    // —— 逐场可变状态（start() 时重建，绝不跨场继承）——
    private var tripId: String? = null
    private var plannedRouteId: String? = null
    private var name: String = ""
    private var startedAtMs: Long = 0
    private var currentSegment: Int = 0
    private var lastPausedAtMs: Long? = null
    private var accumulatedPausedMs: Long = 0
    private var movingSecBySegment = mutableMapOf<Int, Long>() // 逐段运动秒数（DEV 决策 14 / §4.12）
    private var lastSampleTsMs: Long? = null
    private var maxSpeedMps: Double? = null

    private var sampler = TrackSampler()
    private var statAccumulator = ThresholdAccumulator(10.0) // 无气压计阈值 10m；有气压计 3m（§4.3.1）
    private var markAccumulator = ThresholdAccumulator(100.0) // 打点累加器：独立实例（冻结决策 5），默认 100m
    private var hasBarometer = false
    private var altitudeFuser: AltitudeFuser? = null
    private var stepSource: StepSource? = null
    private var currentSteps = -1
    private var pressureListener: android.hardware.SensorEventListener? = null
    private var stepsCollectJob: Job? = null

    private var lastAcceptedFix: com.gohiking.core.location.LocationFix? = null
    private var seqInSegment: Int = 0
    private var pointCount: Int = 0
    private var distanceM: Double = 0.0
    private var maxAltitudeM: Double? = null
    private var minAltitudeM: Double? = null
    private val markers = mutableListOf<MarkerEntity>()
    // DateTimeFormatter 线程安全且不可变，无需 ThreadLocal（K2 起 SimpleDateFormat?.get() 可空告警）
    private val DATE_TIME_FORMAT =
        java.time.format.DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm", java.util.Locale.ROOT)
    private var sequenceByType = mutableMapOf<String, Int>()

    private var buffer = mutableListOf<TrackPointEntity>()
    private var lastFlushAtMs: Long = 0
    private var lastStatePersistAtMs: Long = 0

    private var collectJob: Job? = null
    private var tickJob: Job? = null

    val droppedCount: Int get() = locationProvider.droppedCount

    /** —— 开始记录（F-REC-01/02）—— */
    fun start(name: String, plannedRouteId: String?) {
        if (_state.value !is SessionState.Idle && _state.value !is SessionState.Finished) return
        // 气压计探测（DEV §4.4）：Sensor.TYPE_PRESSURE 定义在 Sensor 类上，
        // 通过 SensorManager.getDefaultSensor 判断气压计是否可用
        hasBarometer = context.getSystemService(SensorManager::class.java)
            ?.getDefaultSensor(Sensor.TYPE_PRESSURE) != null

        tripId = UUID.randomUUID().toString()
        this.plannedRouteId = plannedRouteId
        this.name = name
        startedAtMs = System.currentTimeMillis()
        currentSegment = 0
        lastPausedAtMs = null
        accumulatedPausedMs = 0
        movingSecBySegment = mutableMapOf()
        lastSampleTsMs = null
        maxSpeedMps = null
        seqInSegment = 0
        pointCount = 0
        distanceM = 0.0
        maxAltitudeM = null
        minAltitudeM = null
        markers.clear()
        sequenceByType.clear()
        buffer.clear()
        lastFlushAtMs = System.currentTimeMillis()
        lastStatePersistAtMs = lastFlushAtMs
        sampler = TrackSampler()
        statAccumulator = ThresholdAccumulator(if (hasBarometer) 3.0 else 10.0)
        markAccumulator = ThresholdAccumulator(100.0)
        altitudeFuser = AltitudeFuser(hasBarometer, nowMs = System::currentTimeMillis)
        currentSteps = -1
        stepSource = StepSourceFactory.create(context, hasPermission = true).also { src ->
            stepsCollectJob?.cancel()
            stepsCollectJob = scope.launch {
                src.steps.collect { currentSteps = it }
            }
        }
        if (hasBarometer) registerPressureListener()

        startLocationCollection()
        Timber.i("记录开始 id=%s hasBarometer=%s", tripId, hasBarometer)
    }

    /** —— 暂停（F-REC-04）：停止定位省电；anchor 与累加值保留 —— */
    fun pause() {
        val s = _state.value as? SessionState.Active ?: return
        if (!s.isRecording) return
        lastPausedAtMs = System.currentTimeMillis()
        lastSampleTsMs = null // 暂停后的时间差不计入任何段
        locationProvider.stop()
        tickJob?.cancel()
        altitudeFuser?.onSessionPause()
        stepSource?.pause()
        unregisterPressureListener()
        scope.launch { flushBuffer(force = true) }
        scope.launch { persistSessionState() }
        _state.value = s.copy(status = "PAUSED", pausedAtMs = lastPausedAtMs)
        Timber.d("记录暂停 segment=%d", currentSegment)
    }

    /** —— 继续（F-REC-05）：segmentIndex + 1，重新开始定位 —— */
    fun resume() {
        val s = _state.value as? SessionState.Active ?: return
        if (s.isRecording) return
        val paused = lastPausedAtMs
        if (paused != null) {
            accumulatedPausedMs += System.currentTimeMillis() - paused
            lastPausedAtMs = null
        }
        currentSegment += 1
        seqInSegment = 0
        lastSampleTsMs = null // 新 segment 从第一个点起算
        stepSource?.resume()
        if (hasBarometer) registerPressureListener()
        startLocationCollection()
        _state.value = s.copy(
            status = "RECORDING",
            pausedAtMs = null,
            accumulatedPausedMs = accumulatedPausedMs,
            currentSegment = currentSegment,
        )
        Timber.d("记录继续 segment=%d", currentSegment)
    }

    /** —— 登顶点标记（F-REC-30）—— */
    fun markSummit(note: String?) = addMarker("SUMMIT", note, extraJson = null)

    /** —— 手动打点（F-REC-40）—— */
    fun dropMarker(note: String) = addMarker("MANUAL", note, extraJson = null)

    /** —— 停止（F-REC-06/07）：产出 TripDraft；save 落库 / discard 丢弃 —— */
    suspend fun stop(): TripDraft? {
        val s = _state.value as? SessionState.Active ?: return null
        if (s.isRecording) {
            locationProvider.stop()
            tickJob?.cancel()
            flushBuffer(force = true)
        } else {
            accumulatedPausedMs += lastPausedAtMs?.let { System.currentTimeMillis() - it } ?: 0
        }
        flushBuffer(force = true) // 暂停态停止也要把缓冲点落库（卡路里计算依赖全部点）
        val now = System.currentTimeMillis()
        val totalDurationSec = ((now - startedAtMs) / 1000).coerceAtLeast(0)
        // 运动时长：优先用逐段累计（DEV 决策 14）。暂停必然新建 segment、崩溃空窗不属于
        // 任何段，二者都不会被计入运动；无逐段数据时退化为「总时长 − 暂停」。
        val movingDurationSec = if (movingSecBySegment.isEmpty()) {
            (totalDurationSec - accumulatedPausedMs / 1000).coerceAtLeast(0)
        } else {
            movingSecBySegment.values.sum()
        }
        val distance = distanceM
        // 卡路里与最高速度：保存时算一次落库（DEV §4.12「汇总统计要落库」）；体重默认 65kg（M3 接设置）
        val allPoints = withContext(Dispatchers.IO) { db.trackPointDao().allOf(s.tripId) }
        val kcal = CalorieCalculator.estimate(allPoints, bodyWeightKg = 65)
        val maxSpd = maxSpeedMps
        // F-HIS-33/34：分母用运动时长（不含暂停）；distance < 50m → null（导出写 null）
        val avgSpeedMps = if (distance >= 50 && movingDurationSec > 0) distance / movingDurationSec else null
        val avgPaceSecPerKm = if (distance >= 50 && movingDurationSec > 0) {
            (movingDurationSec / (distance / 1000.0)).toLong()
        } else {
            null
        }
        val trip = TripEntity(
            id = s.tripId,
            // F-REC-08：未命名（空）→ 日期 + 时间命名；关联计划线路取计划名（M2 接入）
            name = s.name.ifBlank {
                DATE_TIME_FORMAT.format(
                    java.time.Instant.ofEpochMilli(startedAtMs).atZone(java.time.ZoneId.systemDefault())
                )
            },
            note = null,
            plannedRouteId = plannedRouteId,
            startTime = startedAtMs,
            endTime = now,
            durationSec = totalDurationSec,
            movingDurationSec = movingDurationSec,
            pausedDurationSec = (totalDurationSec - movingDurationSec).coerceAtLeast(0),
            distanceM = distance,
            totalAscentM = statAccumulator.ascent,
            totalDescentM = statAccumulator.descent,
            maxAltitudeM = maxAltitudeM,
            minAltitudeM = minAltitudeM,
            avgSpeedMps = avgSpeedMps,
            avgPaceSecPerKm = avgPaceSecPerKm,
            maxSpeedMps = maxSpd,
            steps = currentSteps,
            stepSource = stepSource?.wire ?: StepWire.UNAVAILABLE,
            caloriesKcal = kcal,
            altitudeSource = altitudeFuser?.source?.name ?: "GPS_ONLY",
            hasBarometer = hasBarometer,
            status = "FINISHED",
            createdAt = now,
        )
        val draft = TripDraft(
            trip = trip,
            markers = markers.toList(),
            droppedCount = droppedCount,
            pointCount = pointCount,
        )
        collectJob?.cancel()
        collectJob = null
        tickJob?.cancel()
        tickJob = null
        stepsCollectJob?.cancel()
        stepsCollectJob = null
        unregisterPressureListener()
        tripId = null
        _state.value = SessionState.Finished(draft)
        Timber.i("记录停止 distance=%.1fm points=%d markers=%d", distance, pointCount, markers.size)
        return draft
    }

    /** 保存：一个大事务 insert trip → insert markers → delete recording_state（DEV §3.4） */
    suspend fun save(draft: TripDraft): Boolean = withContext(Dispatchers.IO) {
        try {
            db.withTransaction {
                db.tripDao().insert(draft.trip)
                if (draft.markers.isNotEmpty()) db.markerDao().insertAll(draft.markers)
                db.recordingStateDao().clear()
            }
            _state.value = SessionState.Idle
            true
        } catch (t: Throwable) {
            Timber.e(t, "保存失败 tripId=%s", draft.trip.id)
            false
        }
    }

    /** 丢弃：清掉已分批落库的轨迹点与状态快照 */
    suspend fun discard(draft: TripDraft) {
        withContext(Dispatchers.IO) {
            db.trackPointDao().deleteOf(draft.trip.id)
            db.recordingStateDao().clear()
        }
        _state.value = SessionState.Idle
    }

    /** 检测未结束记录（崩溃恢复 F-REC-09）：App 启动时调用 */
    suspend fun hasRecoverableSession(): Boolean = db.recordingStateDao().get() != null

    /** 恢复（F-REC-09）：rebind 同一 tripId，新建 segment；绝不把恢复点与中断点连线 */
    suspend fun restoreFromSnapshot(): Boolean {
        if (_state.value !is SessionState.Idle) return false
        val snap = db.recordingStateDao().get() ?: return false
        tripId = snap.tripId
        name = ""
        plannedRouteId = null
        startedAtMs = snap.startedAt
        currentSegment = snap.currentSegment + 1 // 恢复即新建 segment（DEV §3.1.1：绝不把恢复点与中断点连线）
        seqInSegment = 0
        accumulatedPausedMs = snap.accumulatedPausedMs +
            (snap.lastPausedAt?.let { System.currentTimeMillis() - it } ?: 0) // 崩溃时若在暂停，先结算
        lastPausedAtMs = null
        hasBarometer = snap.hasBarometer
        movingSecBySegment = parseMovingSecBySegment(snap.movingSecBySegmentJson) // 崩溃恢复必须读回（§4.12）
        lastSampleTsMs = null
        maxSpeedMps = null
        distanceM = snap.distanceM
        pointCount = 0
        maxAltitudeM = null
        minAltitudeM = null
        buffer.clear()
        markers.clear()
        sequenceByType.clear()
        sampler = TrackSampler()
        sampler.reset() // 恢复点与中断点不连线 → 位移基准重建
        statAccumulator = ThresholdAccumulator(if (snap.hasBarometer) 3.0 else 10.0)
        statAccumulator.restore(snap.statAnchorM, snap.ascentAccumM, snap.descentAccumM)
        markAccumulator = ThresholdAccumulator(100.0)
        altitudeFuser = AltitudeFuser(snap.hasBarometer, nowMs = System::currentTimeMillis)
            .also { it.restoreRef(snap.altitudeRefM, snap.pressureRefHpa) }
        currentSteps = -1
        stepSource = StepSourceFactory.create(context, hasPermission = true).also { src ->
            stepsCollectJob?.cancel()
            stepsCollectJob = scope.launch { src.steps.collect { currentSteps = it } }
        }
        startLocationCollection()
        _state.value = SessionState.Active(
            tripId = snap.tripId,
            name = "",
            plannedRouteId = null,
            startedAtMs = snap.startedAt,
            currentSegment = snap.currentSegment + 1, // 新建 segment
            status = "RECORDING",
            distanceM = snap.distanceM,
            ascentM = snap.ascentAccumM,
            descentM = snap.descentAccumM,
            maxAltitudeM = null,
            minAltitudeM = null,
            accumulatedPausedMs = snap.accumulatedPausedMs,
            pausedAtMs = null,
            pointCount = 0,
        )
        Timber.w("崩溃恢复 tripId=%s segment=%d", snap.tripId, snap.currentSegment + 1)
        return true
    }

    // —— 内部 ——

    /** 气压采样 → AltitudeFuser（仅 hasBarometer 时注册；暂停期间注销） */
    private fun registerPressureListener() {
        if (pressureListener != null) return
        val sm = context.getSystemService(SensorManager::class.java) ?: return
        val sensor = sm.getDefaultSensor(Sensor.TYPE_PRESSURE) ?: return
        val listener = object : android.hardware.SensorEventListener {
            override fun onSensorChanged(event: android.hardware.SensorEvent) {
                val hpa = event.values.firstOrNull()?.toDouble() ?: return
                altitudeFuser?.onPressure(hpa)
            }

            override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) = Unit
        }
        sm.registerListener(listener, sensor, SensorManager.SENSOR_DELAY_NORMAL)
        pressureListener = listener
    }

    private fun unregisterPressureListener() {
        val listener = pressureListener ?: return
        context.getSystemService(SensorManager::class.java)?.unregisterListener(listener)
        pressureListener = null
    }

    private fun startLocationCollection() {
        locationProvider.start(intervalMs = 1_000) // 高精度 1s（PRD 6.3.7；省电 3s 由设置项控制）
        val id = requireNotNull(tripId)
        collectJob?.cancel()
        collectJob = scope.launch {
            locationProvider.fixes.collect { fix ->
                val sample = sampler.accept(fix) ?: return@collect
                onSample(id, fix, sample)
            }
        }
        tickJob?.cancel()
        tickJob = scope.launch {
            while (true) {
                delay(1_000)
                val now = System.currentTimeMillis()
                if (now - lastFlushAtMs >= 5_000) flushBuffer(force = true) // 每 5 秒（PRD 6.3.7）
                if (now - lastStatePersistAtMs >= 10_000) persistSessionState() // 每 10 秒（F-REC-09）
                bumpStateTick()
            }
        }
    }

    private fun onSample(id: String, fix: com.gohiking.core.location.LocationFix, sample: TrackSampler.Sample) {
        val last = lastAcceptedFix
        if (sample.quality == 0 && last != null) {
            distanceM += GeoMath.distanceMeters(last.lat, last.lng, fix.lat, fix.lng)
        }
        lastAcceptedFix = fix
        // 逐段运动时长（DEV 决策 14）：段内相邻点时间差累加。lastSampleTsMs 在暂停/继续/
        // 恢复时清空，跨段间隙与崩溃空窗都不会被误计为运动时长。
        val prevTs = lastSampleTsMs
        if (prevTs != null && fix.timestampMs > prevTs) {
            movingSecBySegment[currentSegment] =
                (movingSecBySegment[currentSegment] ?: 0L) + (fix.timestampMs - prevTs) / 1000
        }
        lastSampleTsMs = fix.timestampMs
        // 最高速度：仅统计有效点（与 PRD 6.5.2 的 quality==0 口径一致）
        if (sample.quality == 0) {
            val sp = sample.fix.speedMps.toDouble()
            if (maxSpeedMps == null || sp > maxSpeedMps!!) maxSpeedMps = sp
        }
        // 海拔融合（DEV §4.3）：每个 fix 都喂给 fuser（精度门控/稳定确认由 fuser 内部处理）
        altitudeFuser?.onGpsFix(fix.altitudeM, fix.verticalAccuracyM?.toDouble(), fix.mslAltitudeM)
        // 爬升统计：低精度点不参与（PRD 6.3.7）；海拔用滤波后的融合值
        val altitude = if (sample.quality == 0) altitudeFuser?.current() else null
        if (altitude != null) {
            statAccumulator.accept(altitude)
            markAccumulator.accept(altitude) // 打点累加器：独立实例（冻结决策 5）
            if (maxAltitudeM == null || altitude > maxAltitudeM!!) maxAltitudeM = altitude
            if (minAltitudeM == null || altitude < minAltitudeM!!) minAltitudeM = altitude
        }
        buffer += TrackPointEntity(
            tripId = id,
            segmentIndex = currentSegment,
            seq = seqInSegment,
            timestamp = fix.timestampMs,
            latitude = fix.lat,
            longitude = fix.lng,
            altitude = fix.altitudeM,
            accuracy = fix.accuracyM.toDouble(),
            speedMps = sample.fix.speedMps.toDouble(),
            bearing = fix.bearing.toDouble(),
            quality = sample.quality,
            distanceM = distanceM,
        )
        seqInSegment += 1
        pointCount += 1
        val s = _state.value as? SessionState.Active
        if (s != null) {
            _state.value = s.copy(
                distanceM = distanceM,
                ascentM = statAccumulator.ascent,
                descentM = statAccumulator.descent,
                maxAltitudeM = maxAltitudeM,
                minAltitudeM = minAltitudeM,
                pointCount = pointCount,
                currentAltitudeM = if (sample.quality == 0) altitudeFuser?.current() else s.currentAltitudeM,
                stepCount = currentSteps,
                stepWire = stepSource?.wire ?: StepWire.UNAVAILABLE,
            )
        }
        // emit 采样事件给地图绘制（失败即丢，不影响记录主链路）
        _samples.tryEmit(
            TrackSample(
                tripId = id, lat = fix.lat, lng = fix.lng, altitudeM = fix.altitudeM,
                quality = sample.quality, segmentIndex = currentSegment, seq = seqInSegment - 1,
                timestampMs = fix.timestampMs,
            ),
        )
        if (buffer.size >= 10) scope.launch { flushBuffer(force = false) } // 每 10 点（PRD 6.3.7）
    }

    private fun addMarker(type: String, note: String?, extraJson: String?) {
        val fix = lastAcceptedFix ?: return // 无有效定位时不打点
        val s = _state.value as? SessionState.Active ?: return
        val seq = (sequenceByType[type] ?: 0) + 1
        sequenceByType[type] = seq
        markers += MarkerEntity(
            id = UUID.randomUUID().toString(),
            tripId = s.tripId,
            type = type,
            timestamp = System.currentTimeMillis(),
            latitude = fix.lat,
            longitude = fix.lng,
            altitude = fix.altitudeM,
            label = null, // 渲染按 type 本地化生成；不在此写展示快照
            note = note,
            sequence = seq,
            extraJson = extraJson,
        )
    }

    private suspend fun flushBuffer(force: Boolean) {
        if (buffer.isEmpty()) return
        if (!force && buffer.size < 10) return
        val batch = buffer.toList()
        buffer.clear()
        lastFlushAtMs = System.currentTimeMillis()
        try {
            withContext(Dispatchers.IO) { db.trackPointDao().insertBatch(batch) }
        } catch (t: Throwable) {
            Timber.e(t, "轨迹点批量写入失败 size=%d", batch.size)
            buffer.addAll(0, batch) // 失败回填，下次重试
        }
    }

    private suspend fun persistSessionState() {
        val s = _state.value as? SessionState.Active ?: return
        lastStatePersistAtMs = System.currentTimeMillis()
        try {
            db.recordingStateDao().save(
                RecordingStateEntity(
                    tripId = s.tripId,
                    status = s.status,
                    startedAt = s.startedAtMs,
                    lastPausedAt = s.pausedAtMs,
                    accumulatedPausedMs = s.accumulatedPausedMs,
                    currentSegment = s.currentSegment,
                    distanceM = s.distanceM,
                    ascentAccumM = s.ascentM,
                    descentAccumM = s.descentM,
                    lastDistanceMarkM = 0.0, // 提醒引擎（§4.9）接入后维护
                    lastAscentMarkM = 0.0,
                    lastDescentMarkM = 0.0,
                    stepAtStart = -1, // 级①基准由 SensorCounterStepSource 内部维护，会话级恢复用不到
                    stepSourceUsed = stepSource?.wire ?: StepWire.UNAVAILABLE,
                    hasBarometer = hasBarometer,
                    altitudeRefM = altitudeFuser?.refSnapshot()?.first,
                    pressureRefHpa = altitudeFuser?.refSnapshot()?.second,
                    statAnchorM = statAccumulator.currentAnchor(),
                    markAnchorM = markAccumulator.currentAnchor(), // 打点累加器锚点（提醒引擎接入后生效）
                    movingSecBySegmentJson = movingSecBySegment.entries
                        .joinToString(",", prefix = "{", postfix = "}") { (k, v) -> "\"$k\":$v" },
                    updatedAt = lastStatePersistAtMs,
                ),
            )
        } catch (t: Throwable) {
            Timber.e(t, "recording_state 持久化失败")
        }
    }

    /** 解析逐段运动秒数 JSON（崩溃恢复）；格式损坏按空处理，绝不因此拒绝恢复 */
    private fun parseMovingSecBySegment(json: String): MutableMap<Int, Long> = try {
        val obj = Json.parseToJsonElement(json).jsonObject
        val out = mutableMapOf<Int, Long>()
        for ((k, v) in obj) {
            out[k.toInt()] = v.jsonPrimitive.long
        }
        out
    } catch (t: Throwable) {
        Timber.w(t, "movingSecBySegmentJson 解析失败，按空处理")
        mutableMapOf()
    }

    private fun bumpStateTick() {
        // Active 状态下每秒 bump 一次，驱动 UI 时长/速度刷新（copy 相同值也触发 collector）
        val s = _state.value as? SessionState.Active ?: return
        _state.value = s.copy()
    }
}
