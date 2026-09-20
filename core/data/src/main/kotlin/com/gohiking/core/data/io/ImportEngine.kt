package com.gohiking.core.data.io

import com.gohiking.core.database.entity.MarkerEntity
import com.gohiking.core.database.entity.TrackPointEntity
import com.gohiking.core.database.entity.TripEntity
import com.gohiking.core.location.crs.CoordinateConverter
import java.util.UUID
import kotlinx.serialization.json.JsonPrimitive

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
        val invalidReasons: List<String>,
    )

    data class ItemResult(val name: String, val status: Status, val reason: String? = null) {
        enum class Status { IMPORTED, SKIPPED, FAILED }
    }

    data class ImportReport(
        val imported: Int,
        val skipped: Int,
        val failed: Int,
        val items: List<ItemResult>,
        val warnings: List<String>, // suspected dup / crs 转换 / counts 不一致等
    )

    suspend fun preview(files: List<ParsedFile>): ImportPreview {
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
            invalidReasons = invalid.map { "${it.name}: ${it.reason}" },
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
        val warnings = mutableListOf<String>()
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
                        items.add(ItemResult(f.name, ItemResult.Status.SKIPPED, "计划线路已存在（id 相同）"))
                    exists && effective == ConflictPolicy.ASK ->
                        items.add(ItemResult(f.name, ItemResult.Status.SKIPPED, "计划线路冲突，等待用户选择"))
                    else -> {
                        sink.putPlannedRoute(bundle, overwrite = exists && effective == ConflictPolicy.OVERWRITE)
                        items.add(ItemResult(f.name, ItemResult.Status.IMPORTED))
                    }
                }
            } catch (t: Throwable) {
                items.add(ItemResult(f.name, ItemResult.Status.FAILED, t.message ?: t::class.java.simpleName))
            }
        }

        for (f in files.filterIsInstance<ParsedFile.TripFile>()) {
            onProgress(done, total)
            done++
            val effective = decisions[f.name] ?: policy
            try {
                val trip = f.envelope.trip
                val crs = trip.crs
                if (crs == null) warnings.add("${f.name}: 缺少 crs 字段，按 WGS-84 处理（PRD 7.5）")
                val startMs = IoCodecs.fromIso(trip.startTime, 0L)
                val existing = sink.existingTripIds(listOf(trip.id))
                val suspected = if (startMs > 0 && sink.suspectedDuplicateTripIds(trip.name, startMs).isNotEmpty()) {
                    warnings.add("${f.name}: 与现有记录同名同时开始（id 不同），疑似重复，已单独标记（F-IO-41）")
                    true
                } else {
                    false
                }
                if (effective == ConflictPolicy.ASK) {
                    items.add(ItemResult(f.name, ItemResult.Status.SKIPPED, "冲突，等待用户选择"))
                    continue
                }
                if (existing.isNotEmpty() && effective == ConflictPolicy.SKIP) {
                    items.add(ItemResult(f.name, ItemResult.Status.SKIPPED, "记录已存在（id 相同）"))
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
                if (suspected) items.add(ItemResult(f.name, ItemResult.Status.IMPORTED, "疑似重复"))
                else items.add(ItemResult(f.name, ItemResult.Status.IMPORTED))
            } catch (t: Throwable) {
                items.add(ItemResult(f.name, ItemResult.Status.FAILED, t.message ?: t::class.java.simpleName))
            }
        }
        for (f in files.filterIsInstance<ParsedFile.Invalid>()) {
            items.add(ItemResult(f.name, ItemResult.Status.FAILED, f.reason))
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
            status = t.status ?: "FINISHED",
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
            if (seg != segIndex) {
                segIndex = seg
                cumulative = 0.0
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
        val markers = t.markers.map { m ->
            val (gLat, gLng) = m.lat?.let { la -> m.lng?.let { lo -> toGcj(la, lo) } } ?: (0.0 to 0.0)
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
            name = r.name ?: "Imported route",
            note = r.note,
            source = r.source ?: "MANUAL",
            createdAt = r.createdAt ?: System.currentTimeMillis(),
            totalDistanceM = r.totalDistanceM ?: r.legs.sumOf { it.distanceM ?: 0.0 },
            totalAscentM = r.totalAscentM ?: r.legs.sumOf { it.ascentM ?: 0.0 },
            totalDescentM = r.totalDescentM ?: r.legs.sumOf { it.descentM ?: 0.0 },
        )
        val legEntities = r.legs.map { leg ->
            com.gohiking.core.database.entity.PlannedLegEntity(
                id = UUID.randomUUID().toString(),
                plannedRouteId = routeId,
                legType = leg.legType,
                distanceM = leg.distanceM ?: 0.0,
                ascentM = leg.ascentM ?: 0.0,
                descentM = leg.descentM ?: 0.0,
                estimatedMin = leg.estimatedMin,
                difficulty = leg.difficulty ?: "MODERATE",
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

    companion object {
        private const val WGS84 = "WGS-84"
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
