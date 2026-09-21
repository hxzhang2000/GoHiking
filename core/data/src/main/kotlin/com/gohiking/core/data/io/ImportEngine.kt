package com.gohiking.core.data.io

import com.gohiking.core.common.format.Formatters
import com.gohiking.core.database.entity.MarkerEntity
import com.gohiking.core.database.entity.TrackPointEntity
import com.gohiking.core.database.entity.TripEntity
import com.gohiking.core.database.entity.UNNAMED_ROUTE_PLACEHOLDER
import com.gohiking.core.location.crs.CoordinateConverter
import java.util.UUID
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.json.JsonPrimitive
import timber.log.Timber

/**
 * 导入执行引擎（PRD 7.5 / F-IO-26~31/34/40~44）。
 * 通过 [ImportSink] 抽象落库，纯逻辑可单测；Room 实现见 [RoomImportSink]。
 * 单文件级故障隔离：单条失败计入报告不影响其他条（F-IO-28/30）；
 * 幂等：同 trip.id 重复导入按冲突策略处理，默认跳过不产生重复数据（F-IO-31）。
 */
class ImportEngine(private val sink: ImportSink) {

    data class Progress(val done: Int, val total: Int)

    data class ImportPreview(
        val tripCount: Int,
        val routeCount: Int,
        val conflictTrips: Set<String>, // trip.id 已存在（F-IO-40）
        val suspectedDupTrips: Set<String>, // name+startTime 相同但 id 不同（F-IO-41）
        val conflictRoutes: Set<String>,
        val invalidCount: Int,
        val crsValues: Set<String>, // 文件声明的坐标系（GCJ-02 之外的都要转 + 告知，F-IO-26）
        val earliestStartMs: Long?,
        val latestStartMs: Long?,
        val invalidReasons: List<ImportItemMessage>,
        // F-IO-64：导入体积与「超限需确认」标记。默认值让「不关心体积」的调用点（单测）保持可用。
        val totalBytes: Long = 0,
        val estimatedSec: Long = 0,
        val oversize: Boolean = false, // totalBytes > OVERSIZE_CONFIRM_BYTES
        val fileCount: Int = 0,
    )

    data class ItemResult(
        val name: String,
        val status: Status,
        val reason: ImportReasonCode? = null,
        val arg: String? = null,
    ) {
        enum class Status { IMPORTED, SKIPPED, FAILED }
    }

    data class ImportReport(
        val imported: Int,
        val skipped: Int,
        val failed: Int,
        val items: List<ItemResult>,
        val warnings: List<ImportWarning>, // suspected dup / crs 转换 / counts 不一致等
    )

    /**
     * 导入预览（F-IO-24）。
     *
     * [totalBytes] 由 UI 侧（SAF 读出的原始字节）传入——ZIP 解压后的体积与磁盘上的
     * 压缩包体积不是一回事，这里以**实际读入的字节数**为准判定 F-IO-64 的超限。
     */
    suspend fun preview(files: List<ParsedFile>, totalBytes: Long = 0L): ImportPreview {
        val tripFiles = files.filterIsInstance<ParsedFile.TripFile>()
        val routeFiles = files.filterIsInstance<ParsedFile.RouteFile>()
        val invalid = files.filterIsInstance<ParsedFile.Invalid>()
        val existingTrips = sink.existingTripIds(tripFiles.map { it.envelope.trip.id })
        val existingRoutes = sink.existingRouteIds(routeFiles.mapNotNull { it.envelope.plannedRoute.id })
        val suspected = mutableSetOf<String>()
        for (t in tripFiles) {
            val startMs = IoCodecs.fromIso(t.envelope.trip.startTime, 0L)
            if (sink.suspectedDuplicateTripIds(t.envelope.trip.name, startMs).isNotEmpty()) {
                suspected.add(t.envelope.trip.id)
            }
        }
        val startTimes = tripFiles.mapNotNull { IoCodecs.fromIso(it.envelope.trip.startTime, 0L).takeIf { ms -> ms > 0 } }
        val crsValues = tripFiles.map { it.envelope.trip.crs ?: WGS84 }.toSet() // 缺失按 WGS-84（PRD 7.5）
        return ImportPreview(
            tripCount = tripFiles.size,
            routeCount = routeFiles.size,
            conflictTrips = existingTrips,
            suspectedDupTrips = suspected,
            conflictRoutes = existingRoutes,
            invalidCount = invalid.size,
            crsValues = crsValues,
            earliestStartMs = startTimes.minOrNull(),
            latestStartMs = startTimes.maxOrNull(),
            invalidReasons = invalid.map { ImportItemMessage(it.name, it.code, it.arg) },
            totalBytes = totalBytes,
            estimatedSec = Formatters.estimatedSec(totalBytes, IMPORT_BYTES_PER_SEC),
            oversize = totalBytes > OVERSIZE_CONFIRM_BYTES,
            fileCount = files.size,
        )
    }

    /**
     * 执行导入。冲突处理（F-IO-27/40~43）：
     * ASK 策略靠 [decisions]（key=文件名）逐条给策略；缺失按 SKIP。
     * 进度 [onProgress] 支持取消：抛出 CancellationException 即停（已导入的保留，F-IO-29）。
     */
    suspend fun execute(
        files: List<ParsedFile>,
        policy: ConflictPolicy = ConflictPolicy.SKIP,
        decisions: Map<String, ConflictPolicy> = emptyMap(),
        onProgress: suspend (Int, Int) -> Unit = { _, _ -> },
    ): ImportReport {
        val items = mutableListOf<ItemResult>()
        val warnings = mutableListOf<ImportWarning>()
        val total = files.size
        var done = 0

        // 计划线路先行（trip 可能内嵌 plannedRoute，导入后回填 plannedRouteId）
        for (f in files.filterIsInstance<ParsedFile.RouteFile>()) {
            onProgress(done, total)
            done++
            val effective = decisions[f.name] ?: policy
            try {
                val bundle = toRouteBundle(f.envelope)
                val exists = bundle.route.id.let { sink.existingRouteIds(listOf(it)).isNotEmpty() }
                when {
                    exists && effective == ConflictPolicy.SKIP ->
                        items.add(
                            ItemResult(f.name, ItemResult.Status.SKIPPED, ImportReasonCode.ROUTE_EXISTS)
                        )
                    exists && effective == ConflictPolicy.ASK ->
                        items.add(
                            ItemResult(f.name, ItemResult.Status.SKIPPED, ImportReasonCode.ROUTE_CONFLICT_WAIT)
                        )
                    else -> {
                        sink.putPlannedRoute(bundle, overwrite = exists && effective == ConflictPolicy.OVERWRITE)
                        items.add(ItemResult(f.name, ItemResult.Status.IMPORTED))
                    }
                }
            } catch (t: Throwable) {
                // L-07：取消不是「导入失败」。吞掉 CancellationException 会让协程取消后
                // 依旧返回一份报告，并凭空多出一条 FAILED 记录（F-IO-28 的故障隔离
                // 只针对真实异常，不针对取消）。
                if (t is CancellationException) throw t
                Timberw(t)
                items.add(ItemResult(f.name, ItemResult.Status.FAILED, ImportReasonCode.IMPORT_FAILED))
            }
        }

        for (f in files.filterIsInstance<ParsedFile.TripFile>()) {
            onProgress(done, total)
            done++
            val effective = decisions[f.name] ?: policy
            try {
                val trip = f.envelope.trip
                val crs = trip.crs
                if (crs == null) {
                    warnings.add(
                        ImportWarning(code = ImportWarningCode.CRS_MISSING, fileName = f.name)
                    )
                }
                val startMs = IoCodecs.fromIso(trip.startTime, 0L)
                val existing = sink.existingTripIds(listOf(trip.id))
                val suspected = if (startMs > 0 && sink.suspectedDuplicateTripIds(trip.name, startMs).isNotEmpty()) {
                    warnings.add(
                        ImportWarning(code = ImportWarningCode.SUSPECTED_DUPLICATE, fileName = f.name)
                    )
                    true
                } else {
                    false
                }
                if (effective == ConflictPolicy.ASK) {
                    items.add(
                        ItemResult(f.name, ItemResult.Status.SKIPPED, ImportReasonCode.CONFLICT_WAIT)
                    )
                    continue
                }
                if (existing.isNotEmpty() && effective == ConflictPolicy.SKIP) {
                    items.add(
                        ItemResult(f.name, ItemResult.Status.SKIPPED, ImportReasonCode.TRIP_EXISTS)
                    )
                    continue
                }
                val bundle = toTripBundle(f.envelope)
                val finalBundle = if (existing.isNotEmpty() && effective == ConflictPolicy.DUPLICATE) {
                    // F-IO「保留两者」：新 id 重建 bundle
                    bundle.reid(UUID.randomUUID().toString())
                } else {
                    bundle
                }
                val overwrite = existing.isNotEmpty() && effective == ConflictPolicy.OVERWRITE
                sink.putTrip(finalBundle, overwrite = overwrite)
                items.add(
                    ItemResult(
                        f.name,
                        ItemResult.Status.IMPORTED,
                        reason = if (suspected) ImportReasonCode.DUPLICATE_SUSPECTED else null,
                    )
                )
            } catch (t: Throwable) {
                // L-07：取消不是「导入失败」。吞掉 CancellationException 会让协程取消后
                // 依旧返回一份报告，并凭空多出一条 FAILED 记录（F-IO-28 的故障隔离
                // 只针对真实异常，不针对取消）。
                if (t is CancellationException) throw t
                Timberw(t)
                items.add(ItemResult(f.name, ItemResult.Status.FAILED, ImportReasonCode.IMPORT_FAILED))
            }
        }
        for (f in files.filterIsInstance<ParsedFile.Invalid>()) {
            items.add(ItemResult(f.name, ItemResult.Status.FAILED, f.code, f.arg))
        }
        onProgress(total, total)
        return ImportReport(
            imported = items.count { it.status == ItemResult.Status.IMPORTED },
            skipped = items.count { it.status == ItemResult.Status.SKIPPED },
            failed = items.count { it.status == ItemResult.Status.FAILED },
            items = items,
            warnings = warnings,
        )
    }

    // ---- TripEnvelope → TripBundle（含坐标系转换 F-IO-26 / 距离重算）----

    /** N-44：只有 FINISHED 会在历史列表里出现（DEV 决策 13）；其余一律纠正并记录 */
    private fun normalizeStatus(raw: String?): String {
        if (raw == null) return "FINISHED"
        if (raw == "FINISHED") return raw
        Timber.w("导入行程 status=%s 非终态，已纠正为 FINISHED", raw)
        return "FINISHED"
    }

    internal fun toTripBundle(envelope: TripEnvelope): TripBundle {
        val t = envelope.trip
        val fileCrs = t.crs ?: WGS84 // 缺失按 WGS-84（PRD 7.5）
        val toGcj: (Double, Double) -> Pair<Double, Double> = when (fileCrs) {
            "GCJ-02" -> { lat, lng -> lat to lng }
            WGS84, "WGS84", "wgs-84" -> { lat, lng -> CoordinateConverter.wgs84ToGcj02(lat, lng) }
            else -> { lat, lng -> lat to lng } // 未知坐标系：原样入库（内部统一 GCJ-02 的最接近假设）
        }
        val startTime = IoCodecs.fromIso(t.startTime, 0L)
        val endTime = IoCodecs.fromIso(t.endTime, startTime)
        val stats = t.stats

        val tripEntity = TripEntity(
            id = t.id,
            name = t.name,
            note = t.note,
            plannedRouteId = t.plannedRoute?.id,
            startTime = startTime,
            endTime = endTime,
            durationSec = stats?.durationSec ?: 0L,
            movingDurationSec = stats?.movingDurationSec ?: stats?.durationSec ?: 0L,
            pausedDurationSec = stats?.pausedDurationSec ?: 0L,
            distanceM = stats?.distanceM ?: 0.0,
            totalAscentM = stats?.totalAscentM ?: 0.0,
            totalDescentM = stats?.totalDescentM ?: 0.0,
            maxAltitudeM = stats?.maxAltitudeM,
            minAltitudeM = stats?.minAltitudeM,
            avgSpeedMps = stats?.avgSpeedMps,
            avgPaceSecPerKm = stats?.avgPaceSecPerKm,
            maxSpeedMps = stats?.maxSpeedMps,
            steps = stats?.steps ?: -1,
            stepSource = t.stepSource ?: "UNAVAILABLE",
            caloriesKcal = stats?.caloriesKcal,
            altitudeSource = t.altitudeSource ?: "GPS_ONLY",
            hasBarometer = t.hasBarometer ?: false,
            // N-44：此前直接 `t.status ?: "FINISHED"`，完全不校验。历史列表与统计只取
            // FINISHED（DEV 决策 13），一旦文件里是 RECORDING/PAUSED 或任意垃圾值，
            // 行程会「导入成功」（报告 imported=N）却在列表里永远看不见——用户以为数据丢了。
            // 导入的是历史归档，本就不该有进行中的记录，非终态一律纠正为 FINISHED。
            status = normalizeStatus(t.status),
            createdAt = System.currentTimeMillis(),
        )

        // 轨迹点：按 fields 顺序解列；distanceM 逐段重算（导出不含该字段）
        val tp = t.trackPoints
        val fieldIndex = tp?.fields?.withIndex()?.associate { (i, f) -> f to i } ?: emptyMap()
        fun rowIdx(field: String) = fieldIndex[field]
        val points = mutableListOf<TrackPointEntity>()
        var lastLat = Double.NaN
        var lastLng = Double.NaN
        var segIndex = -1
        var cumulative = 0.0
        tp?.values?.forEach { row ->
            val seq = IoCodecs.rowLong(row, rowIdx("seq") ?: -1)?.toInt() ?: 0
            val seg = IoCodecs.rowLong(row, rowIdx("segmentIndex") ?: -1)?.toInt() ?: 0
            val ts = IoCodecs.rowLong(row, rowIdx("timestamp") ?: -1) ?: 0L
            val lat = IoCodecs.rowDouble(row, rowIdx("lat") ?: -1)
            val lng = IoCodecs.rowDouble(row, rowIdx("lng") ?: -1)
            if (lat == null || lng == null) return@forEach // 无坐标的行丢弃（无法定位）
            val (gLat, gLng) = toGcj(lat, lng)
            // N-22：此前每段开头把 cumulative 清零，与记录链路的口径相反 ——
            // RecordingSession 的 distanceM 是**整程全局累加**（pause/resume 只重置
            // 位移基准，不清零累计值），track_point.distanceM 写的就是那个全局值。
            // 导入却按段清零，于是同一条轨迹导出再导入后，每段的距离都从 0 起算：
            // 分段表、图表横轴、按距离切分全部错位。这里改成全程累加，段与段之间不重置。
            if (seg != segIndex) {
                segIndex = seg
                // 段间不连线（与记录链路一致：新段的第一点不参与距离累加）
            } else if (!lastLat.isNaN()) {
                cumulative += IoCodecs.haversineM(lastLat, lastLng, gLat, gLng)
            }
            lastLat = gLat
            lastLng = gLng
            points.add(
                TrackPointEntity(
                    tripId = t.id,
                    segmentIndex = seg,
                    seq = seq,
                    timestamp = ts,
                    latitude = gLat,
                    longitude = gLng,
                    altitude = IoCodecs.rowDouble(row, rowIdx("alt") ?: -1),
                    accuracy = IoCodecs.rowDouble(row, rowIdx("accuracy") ?: -1),
                    speedMps = IoCodecs.rowDouble(row, rowIdx("speedMps") ?: -1),
                    bearing = IoCodecs.rowDouble(row, rowIdx("bearing") ?: -1),
                    quality = IoCodecs.rowLong(row, rowIdx("quality") ?: -1)?.toInt() ?: 0,
                    distanceM = cumulative,
                ),
            )
        }

        // 标记：未知 type 降级 UNKNOWN 且原始值保留在 extraJson（F-IO-55 / PRD 7.5）
        val knownTypes = setOf("SUMMIT", "ALERT_DISTANCE", "ALERT_ASCENT", "ALERT_DESCENT", "MANUAL")
        var markersWithoutCoord = 0
        val markers = t.markers.mapNotNull { m ->
            // N-21：缺坐标时原实现写 (0.0, 0.0)——标记被钉在几内亚湾，详情页地图会在
            // 非洲西海岸出现一个「登顶」标记。这与 PRD 红线「绝不编造」直接冲突。
            // 改为跳过该标记并计入警告条数，绝不写 0,0。
            val coord = m.lat?.let { la -> m.lng?.let { lo -> toGcj(la, lo) } }
            if (coord == null) {
                markersWithoutCoord += 1
                return@mapNotNull null
            }
            val (gLat, gLng) = coord
            val (type, extraJson) = if (m.type in knownTypes) {
                m.type to m.extra?.toString()
            } else {
                val extraMap = HashMap<String, kotlinx.serialization.json.JsonElement>()
                extraMap["rawType"] = JsonPrimitive(m.type)
                m.extra?.forEach { (k, v) -> extraMap[k] = v }
                "UNKNOWN" to IoCodecs.json.encodeToString(
                    kotlinx.serialization.json.JsonObject.serializer(),
                    kotlinx.serialization.json.JsonObject(extraMap),
                )
            }
            MarkerEntity(
                id = m.id ?: UUID.randomUUID().toString(),
                tripId = t.id,
                type = type,
                timestamp = m.timestamp,
                latitude = gLat,
                longitude = gLng,
                altitude = m.altitudeM,
                label = m.label.takeIf { type == "MANUAL" || m.type == "MANUAL" }, // 非 MANUAL 的展示快照不导入（PRD 7.3）
                note = m.note,
                sequence = m.sequence,
                extraJson = extraJson,
            )
        }
        if (markersWithoutCoord > 0) {
            Timber.w("导入跳过 %d 个无坐标的标记（N-21：绝不写 0,0）", markersWithoutCoord)
        }

        val media = t.media.map { m ->
            val geo = m.lat?.let { la -> m.lng?.let { lo -> toGcj(la, lo) } }
            com.gohiking.core.database.entity.MediaRefEntity(
                id = m.id ?: UUID.randomUUID().toString(),
                tripId = t.id,
                uri = m.uri ?: "",
                fileName = m.fileName,
                note = m.note,
                mediaType = m.type,
                timestamp = m.timestamp,
                latitude = geo?.first,
                longitude = geo?.second,
                durationMs = m.durationMs,
                sizeBytes = null,
            )
        }
        return TripBundle(tripEntity, points, markers, media)
    }

    private fun TripBundle.reid(newId: String): TripBundle = TripBundle(
        trip = trip.copy(id = newId),
        points = points.map { it.copy(id = 0, tripId = newId) },
        markers = markers.map { it.copy(id = UUID.randomUUID().toString(), tripId = newId) },
        media = media.map { it.copy(id = UUID.randomUUID().toString(), tripId = newId) },
    )

    internal fun toRouteBundle(envelope: PlannedRouteEnvelope): PlannedRouteBundle {
        val r = envelope.plannedRoute
        val routeId = r.id ?: UUID.randomUUID().toString()
        val route = com.gohiking.core.database.entity.PlannedRouteEntity(
            id = routeId,
            // L-06：core 层不得产出用户可见文案（H-09）→ 落中性哨兵，UI 层本地化
            name = r.name?.takeIf { it.isNotBlank() } ?: UNNAMED_ROUTE_PLACEHOLDER,
            note = r.note,
            source = r.source ?: "MANUAL",
            createdAt = r.createdAt ?: System.currentTimeMillis(),
            totalDistanceM = r.totalDistanceM ?: r.legs.sumOf { it.distanceM ?: 0.0 },
            totalAscentM = r.totalAscentM ?: sumOrNull(r.legs.map { it.ascentM }),
            totalDescentM = r.totalDescentM ?: sumOrNull(r.legs.map { it.descentM }),
        )
        val legEntities = r.legs.map { leg ->
            com.gohiking.core.database.entity.PlannedLegEntity(
                id = UUID.randomUUID().toString(),
                plannedRouteId = routeId,
                legType = leg.legType,
                distanceM = leg.distanceM ?: 0.0,
                ascentM = leg.ascentM,
                descentM = leg.descentM,
                estimatedMin = leg.estimatedMin,
                difficulty = leg.difficulty,
                polylineJson = IoCodecs.encodePolyline(leg.polyline.map { it[0] to it[1] }),
            )
        }
        val waypointEntities = r.legs.flatMap { leg ->
            val legId = legEntities.firstOrNull { it.legType == leg.legType }?.id ?: return@flatMap emptyList()
            leg.waypoints.map { wp ->
                com.gohiking.core.database.entity.PlannedWaypointEntity(
                    id = UUID.randomUUID().toString(),
                    legId = legId,
                    orderIndex = wp.order,
                    latitude = wp.lat,
                    longitude = wp.lng,
                    name = wp.name,
                    source = wp.source ?: "MANUAL",
                )
            }
        }
        return PlannedRouteBundle(route, legEntities, waypointEntities)
    }

    /** 任一子项未知 → 汇总未知（H-06：不得用 0 冒充「已知为 0」） */
    private fun sumOrNull(values: List<Double?>): Double? =
        if (values.any { it == null }) null else values.sumOf { it ?: 0.0 }

    /** 单条失败不中断整批（F-IO-28）；技术堆栈只进日志，不给用户看（M-10） */
    private fun Timberw(t: Throwable) {
        Timber.w(t, "单条导入失败")
    }

    companion object {
        private const val WGS84 = "WGS-84"

        /**
         * F-IO-64：超过此体积的导入必须先提示确认（PRD 7.5 / DEV §5.2 P-17）。
         * 判定用**实际读入的字节数**，不是 ZIP 解压后的条目体积。
         */
        const val OVERSIZE_CONFIRM_BYTES: Long = 500L * 1024 * 1024

        /**
         * 预估耗时的吞吐假设（粗估，非实测）：解压 + JSON 解析 + Room 落库合计约 20 MB/s。
         * 只用于给用户一个量级提示，不做任何超时或限流判断。
         */
        const val IMPORT_BYTES_PER_SEC: Long = 20L * 1024 * 1024
    }
}

/** 导入落库抽象（Room 实现包事务；F-IO-65 半条回滚） */
interface ImportSink {
    suspend fun existingTripIds(ids: Collection<String>): Set<String>
    suspend fun suspectedDuplicateTripIds(name: String, startTime: Long): Set<String>
    suspend fun existingRouteIds(ids: Collection<String>): Set<String>
    suspend fun putTrip(bundle: TripBundle, overwrite: Boolean)
    suspend fun putPlannedRoute(bundle: PlannedRouteBundle, overwrite: Boolean)
}
