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
import com.gohiking.core.data.alert.AlertEngine
import com.gohiking.core.data.alert.AlertSample
import com.gohiking.core.data.alert.AlertSettings
import com.gohiking.core.data.alert.AlertSettingsProvider
import com.gohiking.core.data.alert.AlertVoice
import com.gohiking.core.data.alert.MarkerDraft
import com.gohiking.core.data.stats.CalorieCalculator
import com.gohiking.core.datastore.SettingsRepository
import com.gohiking.core.location.LocationProvider
import com.gohiking.core.location.altitude.AltitudeFusion
import com.gohiking.core.location.altitude.AltitudeFuserFactory
import com.gohiking.core.location.altitude.RealAltitudeFuserFactory
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
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
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
    private val alertSettingsProvider: AlertSettingsProvider,
    private val settingsRepository: SettingsRepository,
    // H-3/D10：海拔融合器有逐场状态 → 注入**工厂**，每场 start()/恢复时新建，绝不跨场继承
    private val altitudeFuserFactory: AltitudeFuserFactory = RealAltitudeFuserFactory,
) {
    private val _state = MutableStateFlow<SessionState>(SessionState.Idle)
    val state: StateFlow<SessionState> = _state.asStateFlow()

    /**
     * N-26：「屏幕常亮」（F-REC-18，P0）此前是**死设置** —— 设置页能开关、能存、能随备份
     * 导出导入，但记录页无条件 `addFlags(FLAG_KEEP_SCREEN_ON)`，关掉它屏幕照样常亮，
     * 夜间长线徒步时白白耗电。这里把设置暴露给 UI（session 已持有 repository，无需改 DI）。
     */
    val keepScreenOn: StateFlow<Boolean> = settingsRepository.settings
        .map { it.recordKeepScreenOn }
        .stateIn(scope, SharingStarted.Eagerly, true)

    private val _samples = MutableSharedFlow<TrackSample>(
        extraBufferCapacity = 64,
        onBufferOverflow = BufferOverflow.DROP_OLDEST,
    )
    val samples: SharedFlow<TrackSample> = _samples.asSharedFlow()

    private val _speakEvents = MutableSharedFlow<AlertVoice>(
        extraBufferCapacity = 16,
        onBufferOverflow = BufferOverflow.DROP_OLDEST,
    )

    /** 提醒播报事件（M3-B TTS 消费；已按 voiceEnabled 裁剪，F-ALERT-06） */
    val speakEvents: SharedFlow<AlertVoice> = _speakEvents.asSharedFlow()

    // 提醒引擎（DEV §4.9）：海拔打点累加器与统计累加器独立（冻结决策 5）
    private var alertEngine = AlertEngine(alertSettingsProvider.settings.value)

    init {
        // F-ALERT-03：设置即时生效（含记录过程中变更）
        scope.launch {
            alertSettingsProvider.settings.collect { s ->
                alertEngine.onSettingsChanged(s)
                val active = _state.value as? SessionState.Active
                if (active != null && active.alertsEnabled != s.masterEnabled) {
                    _state.value = active.copy(alertsEnabled = s.masterEnabled)
                }
            }
        }
    }

    // —— 逐场可变状态（start() 时重建，绝不跨场继承）——
    private var tripId: String? = null
    private var plannedRouteId: String? = null
    private var planName: String? = null // F-PLAN-44：关联计划名（F-REC-08 未命名时采用）
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
    private var hasBarometer = false
    private var altitudeFuser: AltitudeFusion? = null
    private var stepSource: StepSource? = null
    private var currentSteps = -1
    private var pressureListener: android.hardware.SensorEventListener? = null
    private var stepsCollectJob: Job? = null

    private var lastAcceptedFix: com.gohiking.core.location.LocationFix? = null
    // N-04：只有 quality==0 的点才能作为下一个距离区间的起点。异常点（quality 1/2）
    // 会把基准置为无效，从而同时跳过「进入」与「离开」两段距离累加（详见 onSample）。
    // 默认 false：恢复/开局后第一个有效点只建立基准，不凭空累计一段距离。
    private var distanceBaseValid: Boolean = false
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
    // N-17：buffer 被 collectJob（Dispatchers.Default）、tickJob、以及 pause()/stop()
    // 各自 launch 的 flush 协程并发访问，而 ArrayList 非线程安全。最坏情况是并发结构性
    // 修改抛异常，使 fixes.collect 静默终止——状态仍是 RECORDING、通知仍在跳，但此后
    // 一个点都不再落库，整场轨迹丢失且用户无感知。这里用对象锁而非 Mutex，因为
    // onSample 不是 suspend 函数，无法挂起等待。
    private val bufferLock = Any()
    // markers 被 UI 线程（markSummit/dropMarker）与 Default 线程（addAlertMarker）并发写
    private val markersLock = Any()
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
        this.planName = null
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
        synchronized(markersLock) { markers.clear() }
        sequenceByType.clear()
        buffer.clear()
        lastFlushAtMs = System.currentTimeMillis()
        lastStatePersistAtMs = lastFlushAtMs
        sampler = TrackSampler()
        statAccumulator = ThresholdAccumulator(if (hasBarometer) 3.0 else 10.0)
        altitudeFuser = altitudeFuserFactory.create(hasBarometer)
        currentSteps = -1
        stepSource = StepSourceFactory.create(context, hasPermission = true).also { src ->
            stepsCollectJob?.cancel()
            stepsCollectJob = scope.launch {
                src.steps.collect { currentSteps = it }
            }
        }
        if (hasBarometer) registerPressureListener()

        alertEngine = AlertEngine(alertSettingsProvider.settings.value)
        // ⚠ 修复：start() 此前从未把状态从 Idle 切到 Active（UI 无法进入记录页，真机 P0；
        // M1/M2 仅编译/单测验证未真机回归，故未暴露）
        // L-05：且必须在启动采集**之前**置位。反过来的话，第一个定位点会在状态还是
        // Idle/Finished 时被处理：onSample 回写的 distanceM 会被随后的 Active(0.0) 覆盖，
        // 该点触发的提醒标记也会丢。
        _state.value = SessionState.Active(
            tripId = requireNotNull(tripId),
            name = name,
            plannedRouteId = plannedRouteId,
            startedAtMs = startedAtMs,
            currentSegment = 0,
            status = "RECORDING",
            distanceM = 0.0,
            ascentM = 0.0,
            descentM = 0.0,
            maxAltitudeM = null,
            minAltitudeM = null,
            accumulatedPausedMs = 0,
            pausedAtMs = null,
            pointCount = 0,
            alertsEnabled = alertSettingsProvider.settings.value.masterEnabled,
            markers = emptyList(),
        )
        startLocationCollection()
        Timber.i("记录开始 id=%s hasBarometer=%s", tripId, hasBarometer)
    }

    /** —— 暂停（F-REC-04）：停止定位省电；anchor 与累加值保留 —— */
    fun pause() {
        val s = _state.value as? SessionState.Active ?: return
        if (!s.isRecording) return
        lastPausedAtMs = System.currentTimeMillis()
        lastSampleTsMs = null // 暂停后的时间差不计入任何段
        // N-04：暂停期间用户可能移动位置（甚至换了地方），恢复后的第一个点不能与暂停前的
        // 点连成一段距离。置为无效后，恢复后的首个有效点只建立基准。
        distanceBaseValid = false
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
        distanceBaseValid = false // 新段起点只建立基准，不跨段累加
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

    /** —— 关联计划线路（F-PLAN-44）：记录中可关联/更换/取消；会话期元数据，崩溃不恢复（同 name，D-22）—— */
    fun associatePlan(routeId: String?, planName: String?) {
        val s = _state.value as? SessionState.Active ?: return
        this.plannedRouteId = routeId
        this.planName = planName
        _state.value = s.copy(plannedRouteId = routeId)
        Timber.i("关联计划 routeId=%s", routeId)
    }

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
        // 卡路里与最高速度：保存时算一次落库（DEV §4.12「汇总统计要落库」）。
        // H-02：体重此前写死 65kg，F-SET-06 的输入项完全不生效 —— 改为读设置。
        val allPoints = withContext(Dispatchers.IO) { db.trackPointDao().allOf(s.tripId) }
        val bodyWeightKg = settingsRepository.settings.first().bodyWeightKg
        val kcal = CalorieCalculator.estimate(allPoints, bodyWeightKg = bodyWeightKg)
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
            // F-REC-08：未命名（空）→ 关联计划线路取计划名，否则日期 + 时间命名（F-PLAN-44）
            name = s.name.ifBlank {
                planName ?: DATE_TIME_FORMAT.format(
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
        val finalMarkers = synchronized(markersLock) { markers.toList() }
        val draft = TripDraft(
            trip = trip,
            markers = finalMarkers,
            droppedCount = droppedCount,
            pointCount = pointCount,
        )
        collectJob?.cancel()
        collectJob = null
        tickJob?.cancel()
        tickJob = null
        stepsCollectJob?.cancel()
        stepsCollectJob = null
        releaseStepSource()
        unregisterPressureListener()
        tripId = null
        _state.value = SessionState.Finished(draft)
        Timber.i("记录停止 distance=%.1fm points=%d markers=%d", distance, pointCount, finalMarkers.size)
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
            // N-30：标记已即时落库，丢弃行程时必须一起删，否则留下无主标记
            db.markerDao().deleteOf(draft.trip.id)
            db.recordingStateDao().clear()
        }
        releaseStepSource()
        _state.value = SessionState.Idle
    }

    /** 检测未结束记录（崩溃恢复 F-REC-09）：App 启动时调用 */
    suspend fun hasRecoverableSession(): Boolean = db.recordingStateDao().get() != null

    /** 恢复（F-REC-09）：rebind 同一 tripId，新建 segment；绝不把恢复点与中断点连线 */
    suspend fun restoreFromSnapshot(): Boolean {
        if (_state.value !is SessionState.Idle) return false
        val snap = db.recordingStateDao().get() ?: return false
        alertEngine = AlertEngine(alertSettingsProvider.settings.value)
        tripId = snap.tripId
        name = ""
        plannedRouteId = null
        planName = null
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
        // N-30：读回崩溃前已打下的标记（此前这里只 clear，标记全丢）。
        // 轨迹点/统计都能从快照恢复，唯独标记是「只增不快照」的数据，必须回库取。
        val restoredMarkers = try {
            withContext(Dispatchers.IO) { db.markerDao().allOf(snap.tripId) }
        } catch (t: Throwable) {
            Timber.e(t, "恢复标记失败，本次继续记录但历史标记丢失")
            emptyList()
        }
        synchronized(markersLock) {
            markers.clear()
            markers.addAll(restoredMarkers)
        }
        sequenceByType.clear()
        restoredMarkers.forEach { m ->
            sequenceByType[m.type] = maxOf(sequenceByType[m.type] ?: 0, m.sequence)
        }
        sampler = TrackSampler()
        sampler.reset() // 恢复点与中断点不连线 → 位移基准重建
        statAccumulator = ThresholdAccumulator(if (snap.hasBarometer) 3.0 else 10.0)
        statAccumulator.restore(snap.statAnchorM, snap.ascentAccumM, snap.descentAccumM)
        alertEngine.restore(
            lastDistanceM = snap.lastDistanceMarkM,
            lastAscentM = snap.lastAscentMarkM,
            lastDescentM = snap.lastDescentMarkM,
            elevationAnchor = snap.markAnchorM,
        )
        altitudeFuser = altitudeFuserFactory.create(snap.hasBarometer)
            .also { it.restoreRef(snap.altitudeRefM, snap.pressureRefHpa) }
        currentSteps = -1
        stepSource = StepSourceFactory.create(context, hasPermission = true).also { src ->
            stepsCollectJob?.cancel()
            stepsCollectJob = scope.launch { src.steps.collect { currentSteps = it } }
        }
        // L-05：与 start() 同理，先置位 Active 再启动采集
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
            // L-04：原本写 snap.accumulatedPausedMs，但上面已把「崩溃时正处于暂停」的那段
            // 时长结算进内部字段。状态里沿用快照值会让 UI 算出的运动时长凭空多出这段暂停。
            accumulatedPausedMs = accumulatedPausedMs,
            pausedAtMs = null,
            pointCount = 0,
            alertsEnabled = alertSettingsProvider.settings.value.masterEnabled,
            markers = restoredMarkers, // N-30
        )
        startLocationCollection()
        Timber.w(
            "崩溃恢复 tripId=%s segment=%d markers=%d",
            snap.tripId, snap.currentSegment + 1, restoredMarkers.size,
        )
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

    /** H-04：注销计步传感器监听，停止记录 / 恢复前都必须调用，避免后台持续采样耗电 */
    private fun releaseStepSource() {
        stepSource?.close()
        stepSource = null
        currentSteps = -1
    }

    /** C-02（F-REC-09 前半）：丢弃快照里已分批落库的轨迹点 */
    suspend fun discardRecoverable() {
        val snap = db.recordingStateDao().get() ?: return
        db.withTransaction {
            db.trackPointDao().deleteOf(snap.tripId)
            db.recordingStateDao().clear()
        }
        Timber.i("丢弃可恢复快照 tripId=%s", snap.tripId)
    }

    private fun startLocationCollection() {
        locationProvider.start(intervalMs = 1_000) // 高精度 1s（PRD 6.3.7；省电 3s 由设置项控制）
        val id = requireNotNull(tripId)
        collectJob?.cancel()
        collectJob = scope.launch {
            locationProvider.fixes.collect { fix ->
                // N-17：单点处理异常绝不能让整条采集链静默终止——否则状态仍是 RECORDING、
                // 通知仍在跳、UI 仍显示记录中，但此后一个点都不再落库，整场轨迹丢失且
                // 用户完全无感知。这里吞掉单点异常并记日志，保证后续点继续采集。
                try {
                    val sample = sampler.accept(fix) ?: return@collect
                    onSample(id, fix, sample)
                } catch (t: Throwable) {
                    Timber.e(t, "onSample 异常，跳过该定位点")
                }
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
        // N-04：文档承诺「quality=1/2 不参与距离统计」，但原实现无条件 `lastAcceptedFix = fix`，
        // 使异常点成为下一段的位移基准——排除只在「进入异常点那一段」生效，离开的那一段
        // 照旧累加（实测单次 300m 跳变可让距离虚增约 600m，且跳变点还成为后续位移门基准）。
        // 这里让只有 quality==0 的点更新基准；异常点把基准置为无效，使「进入」与「离开」
        // 两段都不累加，也不会把跨越异常区间的直线距离误算成实际路程。
        if (sample.quality == 0) {
            val last = lastAcceptedFix
            if (distanceBaseValid && last != null) {
                distanceM += GeoMath.distanceMeters(last.lat, last.lng, fix.lat, fix.lng)
            }
            lastAcceptedFix = fix
            distanceBaseValid = true
        } else {
            distanceBaseValid = false
        }
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
            if (maxAltitudeM == null || altitude > maxAltitudeM!!) maxAltitudeM = altitude
            if (minAltitudeM == null || altitude < minAltitudeM!!) minAltitudeM = altitude
        }
        // 提醒引擎（DEV §4.9）：仅有效点参与；打点 + 播报（voiceEnabled 裁剪，F-ALERT-06）
        if (sample.quality == 0) {
            alertEngine.onSample(
                AlertSample(
                    distanceM = distanceM,
                    altitudeM = altitude,
                    latitude = fix.lat,
                    longitude = fix.lng,
                    timestampMs = fix.timestampMs,
                ),
            ).forEach { event ->
                addAlertMarker(event.marker)
                if (alertSettingsProvider.settings.value.voiceEnabled) {
                    _speakEvents.tryEmit(event.voice)
                }
            }
        }
        val point = TrackPointEntity(
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
        // N-17：与 flushBuffer 的 toList()/clear() 互斥（见 bufferLock 注释）
        synchronized(bufferLock) { buffer += point }
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
                // N-38：current() 名义是 getter，实际会往 9 点中值窗口推一个值（有副作用）。
                // 同一次 onSample 里上面 :537 已调用过一次，这里再调一次会让窗口只覆盖
                // 4.5 个独立样本（实测噪声放大约 1.34×）。直接复用已算好的 altitude。
                currentAltitudeM = if (sample.quality == 0) altitude else s.currentAltitudeM,
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
        val pending = synchronized(bufferLock) { buffer.size }
        if (pending >= 10) scope.launch { flushBuffer(force = false) } // 每 10 点（PRD 6.3.7）
    }

    private fun addMarker(type: String, note: String?, extraJson: String?) {
        val fix = lastAcceptedFix ?: return // 无有效定位时不打点
        val s = _state.value as? SessionState.Active ?: return
        val seq = (sequenceByType[type] ?: 0) + 1
        sequenceByType[type] = seq
        val marker = MarkerEntity(
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
        // N-17：与 UI 线程的 markSummit/dropMarker 互斥
        synchronized(markersLock) { markers += marker }
        publishMarkers()
    }

    /** 提醒标记（F-ALERT-25/27）：内容来自 AlertEngine.MarkerDraft，序号由引擎按类型维护 */
    private fun addAlertMarker(d: MarkerDraft) {
        val s = _state.value as? SessionState.Active ?: return
        val marker = MarkerEntity(
            id = UUID.randomUUID().toString(),
            tripId = s.tripId,
            type = d.type,
            timestamp = System.currentTimeMillis(),
            latitude = d.latitude,
            longitude = d.longitude,
            altitude = d.altitude,
            label = null,
            note = null,
            sequence = d.sequence,
            extraJson = d.extraJson,
        )
        synchronized(markersLock) { markers += marker }
        persistMarker(marker) // N-30
        publishMarkers()
    }

    /**
     * N-30：标记随打随落库。此前标记只活在内存里、stop() 时才 insertAll，
     * 一旦进程被系统回收（前台服务被杀 / 崩溃），登顶点、手动标记、提醒标记
     * 全部丢失——而崩溃恢复恰恰最容易发生在长时间登山记录中。
     * 用 upsert（REPLACE）：save() 会再写一次同一批主键，默认 ABORT 会炸。
     */
    private fun persistMarker(marker: MarkerEntity) {
        scope.launch(Dispatchers.IO) {
            try {
                db.markerDao().upsert(marker)
            } catch (t: Throwable) {
                Timber.e(t, "标记落库失败 id=%s type=%s", marker.id, marker.type)
            }
        }
    }

    /** 标记快照推送到 state（记录页地图实时渲染，F-ALERT-25/30） */
    private fun publishMarkers() {
        val s = _state.value as? SessionState.Active ?: return
        val snapshot = synchronized(markersLock) { markers.toList() }
        _state.value = s.copy(markers = snapshot)
    }

    private suspend fun flushBuffer(force: Boolean) {
        // N-17：「取走 + 清空」必须在锁内一次完成。原实现三步（isEmpty 检查 → toList → clear）
        // 无锁，两个并发 flush 可能都读到同一批点并各自 insertBatch，而 track_point 只有
        // 非唯一索引、主键自增，重复行会被静默写入（轨迹点与 trip 距离对不上）。
        val batch = synchronized(bufferLock) {
            if (buffer.isEmpty()) return
            if (!force && buffer.size < 10) return
            val b = buffer.toList()
            buffer.clear()
            b
        }
        lastFlushAtMs = System.currentTimeMillis()
        try {
            withContext(Dispatchers.IO) { db.trackPointDao().insertBatch(batch) }
        } catch (t: Throwable) {
            Timber.e(t, "轨迹点批量写入失败 size=%d", batch.size)
            synchronized(bufferLock) { buffer.addAll(0, batch) } // 失败回填，下次重试
        }
    }

    private suspend fun persistSessionState() {
        val s = _state.value as? SessionState.Active ?: return
        val alertBaselines = alertEngine.markBaselines()
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
                    lastDistanceMarkM = alertBaselines.first,
                    lastAscentMarkM = alertBaselines.second,
                    lastDescentMarkM = alertBaselines.third,
                    stepAtStart = -1, // 级①基准由 SensorCounterStepSource 内部维护，会话级恢复用不到
                    stepSourceUsed = stepSource?.wire ?: StepWire.UNAVAILABLE,
                    hasBarometer = hasBarometer,
                    altitudeRefM = altitudeFuser?.refSnapshot()?.first,
                    pressureRefHpa = altitudeFuser?.refSnapshot()?.second,
                    statAnchorM = statAccumulator.currentAnchor(),
                    markAnchorM = alertEngine.currentElevationAnchor(),
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
