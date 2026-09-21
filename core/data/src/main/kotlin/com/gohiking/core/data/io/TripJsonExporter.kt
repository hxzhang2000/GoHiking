package com.gohiking.core.data.io

import com.gohiking.core.database.entity.MarkerEntity
import com.gohiking.core.database.entity.MediaRefEntity
import com.gohiking.core.database.entity.PlannedLegEntity
import com.gohiking.core.database.entity.PlannedRouteEntity
import com.gohiking.core.database.entity.PlannedWaypointEntity
import com.gohiking.core.database.entity.TrackPointEntity
import com.gohiking.core.database.entity.TripEntity
import com.gohiking.core.data.stats.LegSplitter
import com.gohiking.core.data.stats.LegSummary
import com.gohiking.core.location.crs.CoordinateConverter
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/**
 * 记录 → gohiking.trip 信封（PRD 7.3）。
 * 坐标系：库内 GCJ-02；[crs]="GCJ-02" 原样导出，"WGS-84" 逐点转换（F-IO-26 对称要求）。
 * GPX 的恒定 WGS-84 由 GpxExporter 负责，与本类无关（F-IO-14）。
 */
object TripJsonExporter {

    fun export(
        trip: TripEntity,
        points: List<TrackPointEntity>,
        markers: List<MarkerEntity>,
        media: List<MediaRefEntity>,
        crs: String = "GCJ-02",
        plannedRoute: PlannedRouteEntity? = null,
        plannedLegs: List<PlannedLegEntity> = emptyList(),
        plannedWaypoints: List<PlannedWaypointEntity> = emptyList(),
        alertSettings: AlertSettingsJson? = null,
        splits: JsonObject? = null,
        generator: GeneratorInfo? = null,
        exportedAtMs: Long = System.currentTimeMillis(),
    ): TripEnvelope {
        val convert: (Double, Double) -> Pair<Double, Double> = when (crs) {
            "WGS-84" -> { lat, lng -> CoordinateConverter.gcj02ToWgs84(lat, lng) }
            else -> { lat, lng -> lat to lng } // GCJ-02 原样
        }

        val stats = trip.toStatsJson()
        val segments = points.groupBy { it.segmentIndex }.toSortedMap().map { (segIdx, pts) ->
            val ordered = pts.sortedBy { it.seq }
            SegmentJson(
                index = segIdx,
                startTime = ordered.firstOrNull()?.timestamp?.let(IoCodecs::toIso),
                endTime = ordered.lastOrNull()?.timestamp?.let(IoCodecs::toIso),
                distanceM = ordered.lastOrNull()?.distanceM,
                pointCount = ordered.size,
            )
        }
        val trackPoints = TrackPointsJson(
            values = points.sortedWith(compareBy({ it.segmentIndex }, { it.seq })).map { p ->
                val (lat, lng) = convert(p.latitude, p.longitude)
                listOf(
                    JsonPrimitive(p.seq), // 整数原样编码（double 会破坏 longOrNull 解析）
                    JsonPrimitive(p.segmentIndex),
                    JsonPrimitive(p.timestamp),
                    JsonPrimitive(lat),
                    JsonPrimitive(lng),
                    IoCodecs.numberOrNull(p.altitude),
                    IoCodecs.numberOrNull(p.accuracy),
                    IoCodecs.numberOrNull(p.speedMps),
                    IoCodecs.numberOrNull(p.bearing),
                    JsonPrimitive(p.quality),
                )
            },
        )

        return TripEnvelope(
            schema = TripEnvelope.SCHEMA,
            schemaVersion = TripEnvelope.SUPPORTED_VERSION,
            exportedAt = IoCodecs.toIso(exportedAtMs),
            generator = generator,
            trip = TripJson(
                id = trip.id,
                name = trip.name,
                note = trip.note,
                crs = crs,
                crsNote = null, // 人读说明按导出语言由 UI 层补（解析端忽略，PRD 7.3）
                altitudeSource = trip.altitudeSource,
                stepSource = trip.stepSource,
                startTime = IoCodecs.toIso(trip.startTime),
                endTime = IoCodecs.toIso(trip.endTime),
                status = trip.status,
                hasBarometer = trip.hasBarometer,
                stats = stats,
                // L-11：PRD 7.3 的 trip.legs 此前只声明、从不导出（恒为 null），与 schema 不符。
                // 这里复用 F-REC-37 的上下山切分（以时间上最后一个 SUMMIT 为界）填充。
                // 无登顶点、或无任何海拔样本时保持 null —— 阈值累加器在无高程输入时返回 0，
                // 直接写 0 等于编造数据（H-06）。
                legs = exportLegs(points, markers),
                segments = segments,
                trackPoints = trackPoints,
                markers = markers.sortedBy { it.timestamp }.map { m -> m.toMarkerJson(convert) },
                alertSettings = alertSettings,
                plannedRoute = plannedRoute?.toJson(plannedLegs, plannedWaypoints, convert),
                media = media.map { it.toMediaJson(convert) },
                splits = splits,
            ),
        )
    }

    /** 见 [export] 中 legs 的注释：仅在确有海拔样本时才产出上下山汇总 */
    private fun exportLegs(points: List<TrackPointEntity>, markers: List<MarkerEntity>): TripLegsJson? {
        if (points.none { it.altitude != null }) return null
        val legs = LegSplitter.split(points, markers) ?: return null
        return TripLegsJson(
            outbound = legs.uphill.toLegJson(),
            returnLeg = legs.downhill.toLegJson(),
        )
    }

    private fun LegSummary.toLegJson(): TripLegJson = TripLegJson(
        distanceM = distanceM,
        ascentM = ascentM,
        descentM = descentM,
        durationSec = movingSec,
    )

    /** 计划线路单文件信封（F-PLAN-43，PRD 7.3 末尾） */
    fun exportPlannedRoute(
        route: PlannedRouteEntity,
        legs: List<PlannedLegEntity>,
        waypoints: List<PlannedWaypointEntity>,
        crs: String = "GCJ-02",
        generator: GeneratorInfo? = null,
        exportedAtMs: Long = System.currentTimeMillis(),
    ): PlannedRouteEnvelope {
        val convert: (Double, Double) -> Pair<Double, Double> = when (crs) {
            "WGS-84" -> { lat, lng -> CoordinateConverter.gcj02ToWgs84(lat, lng) }
            else -> { lat, lng -> lat to lng }
        }
        return PlannedRouteEnvelope(
            exportedAt = IoCodecs.toIso(exportedAtMs),
            generator = generator,
            plannedRoute = route.toJson(legs, waypoints, convert),
        )
    }

    private fun TripEntity.toStatsJson() = TripStatsJson(
        durationSec = durationSec,
        movingDurationSec = movingDurationSec,
        pausedDurationSec = pausedDurationSec,
        distanceM = distanceM,
        totalAscentM = totalAscentM,
        totalDescentM = totalDescentM,
        maxAltitudeM = maxAltitudeM,
        minAltitudeM = minAltitudeM,
        avgSpeedMps = avgSpeedMps,
        maxSpeedMps = maxSpeedMps,
        avgPaceSecPerKm = avgPaceSecPerKm,
        steps = steps,
        caloriesKcal = caloriesKcal,
    )

    private fun MarkerEntity.toMarkerJson(convert: (Double, Double) -> Pair<Double, Double>): MarkerJson {
        val (lat, lng) = convert(latitude, longitude)
        val extra: JsonObject? = extraJson?.let { raw ->
            try {
                IoCodecs.json.decodeFromString(JsonObject.serializer(), raw)
            } catch (t: Throwable) {
                null
            }
        }
        return MarkerJson(
            id = id,
            type = type,
            sequence = sequence,
            timestamp = timestamp,
            lat = lat,
            lng = lng,
            altitudeM = altitude,
            label = label,
            note = note,
            extra = extra,
        )
    }

    private fun MediaRefEntity.toMediaJson(convert: (Double, Double) -> Pair<Double, Double>): MediaJson {
        val lat = latitude
        val lng = longitude
        val geo: Pair<Double, Double>? = if (lat != null && lng != null) convert(lat, lng) else null
        return MediaJson(
            id = id,
            type = mediaType,
            timestamp = timestamp,
            lat = geo?.first,
            lng = geo?.second,
            fileName = fileName,
            uri = uri,
            note = note,
            durationMs = durationMs,
        )
    }

    private fun PlannedRouteEntity.toJson(
        legs: List<PlannedLegEntity>,
        waypoints: List<PlannedWaypointEntity>,
        convert: (Double, Double) -> Pair<Double, Double>,
    ): PlannedRouteJson = PlannedRouteJson(
        id = id,
        name = name,
        note = note,
        source = source,
        createdAt = createdAt,
        totalDistanceM = totalDistanceM,
        totalAscentM = totalAscentM,
        totalDescentM = totalDescentM,
        legs = legs.sortedBy { it.legType }.map { leg ->
            PlannedLegJson(
                legType = leg.legType,
                distanceM = leg.distanceM,
                ascentM = leg.ascentM,
                descentM = leg.descentM,
                estimatedMin = leg.estimatedMin,
                difficulty = leg.difficulty,
                waypoints = waypoints
                    .filter { it.legId == leg.id }
                    .sortedBy { it.orderIndex }
                    .map { wp ->
                        val (wLat, wLng) = convert(wp.latitude, wp.longitude)
                        WaypointJson(order = wp.orderIndex, lat = wLat, lng = wLng, name = wp.name, source = wp.source)
                    },
                polyline = IoCodecs.decodePolyline(leg.polylineJson).map { (lat, lng) ->
                    val (pLat, pLng) = convert(lat, lng)
                    listOf(pLat, pLng)
                },
            )
        },
    )
}
