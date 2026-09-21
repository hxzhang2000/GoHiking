package com.gohiking.core.data.io

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject

/**
 * 导出/导入 JSON DTO（PRD 7.3 / 7.4）。
 * 兼容约定（F-IO-50/51）：全部字段可选 + 默认值 + ignoreUnknownKeys，
 * 枚举一律存协议原文（String），解析端未知值降级不崩溃（F-IO-55）。
 */
@Serializable
data class GeneratorInfo(
    val app: String = "GoHiking",
    val packageName: String? = null,
    val versionName: String? = null,
    val versionCode: Int? = null,
)

@Serializable
data class TripStatsJson(
    val durationSec: Long = 0,
    val movingDurationSec: Long? = null,
    val pausedDurationSec: Long? = null,
    val distanceM: Double = 0.0,
    val totalAscentM: Double = 0.0,
    val totalDescentM: Double = 0.0,
    val maxAltitudeM: Double? = null,
    val minAltitudeM: Double? = null,
    val avgSpeedMps: Double? = null,
    val maxSpeedMps: Double? = null,
    val avgPaceSecPerKm: Long? = null,
    val steps: Int = -1, // 不可用哨兵 -1（PRD 7.3 空值约定）
    val caloriesKcal: Double? = null,
)

/** 记录级去/返段汇总（trip.legs，注意与 plannedRoute.legs 数组是两回事） */
@Serializable
data class TripLegJson(
    val startTime: String? = null,
    val endTime: String? = null,
    val distanceM: Double? = null,
    val ascentM: Double? = null,
    val descentM: Double? = null,
    val durationSec: Long? = null,
)

/** trip.legs 对象（outbound / return 两个键） */
@Serializable
data class TripLegsJson(
    val outbound: TripLegJson? = null,
    @kotlinx.serialization.SerialName("return") val returnLeg: TripLegJson? = null,
)

@Serializable
data class SegmentJson(
    val index: Int = 0,
    val startTime: String? = null,
    val endTime: String? = null,
    val distanceM: Double? = null,
    val pointCount: Int? = null,
)

/** 轨迹点紧凑数组编码（PRD 7.3）；values 每行与 fields 一一对应，允许 null */
@Serializable
data class TrackPointsJson(
    val encoding: String = "array",
    val fields: List<String> = DEFAULT_FIELDS,
    val values: List<List<JsonElement?>> = emptyList(),
) {
    companion object {
        /** PRD 7.3 固定字段顺序（F-IO-08 之外的距离为写入时重算，不导出） */
        val DEFAULT_FIELDS = listOf(
            "seq", "segmentIndex", "timestamp", "lat", "lng", "alt",
            "accuracy", "speedMps", "bearing", "quality",
        )
    }
}

@Serializable
data class MarkerJson(
    val id: String? = null,
    val type: String = "MANUAL",
    val sequence: Int = 0,
    val timestamp: Long = 0,
    val lat: Double? = null,
    val lng: Double? = null,
    val altitudeM: Double? = null,
    val label: String? = null, // 人读展示快照；解析端忽略（MANUAL 除外：用户输入原文）
    val note: String? = null,
    val extra: JsonObject? = null,
)

@Serializable
data class AlertSettingsJson(
    val enabled: Boolean = true,
    val voiceEnabled: Boolean = true,
    val distanceAlertEnabled: Boolean = true,
    val distanceIntervalM: Int = 100,
    val altitudeAlertEnabled: Boolean = true,
    val altitudeIntervalM: Int = 100,
)

@Serializable
data class PlannedLegJson(
    val legType: String = "OUTBOUND",
    val distanceM: Double? = null,
    val ascentM: Double? = null,
    val descentM: Double? = null,
    val estimatedMin: Int? = null,
    val difficulty: String? = null,
    val waypoints: List<WaypointJson> = emptyList(),
    val polyline: List<List<Double>> = emptyList(),
)

@Serializable
data class WaypointJson(
    val order: Int = 0,
    val lat: Double = 0.0,
    val lng: Double = 0.0,
    val name: String? = null,
    val source: String? = null,
)

@Serializable
data class PlannedRouteJson(
    val id: String? = null,
    val name: String? = null,
    val note: String? = null,
    val source: String? = null,
    val createdAt: Long? = null,
    val totalDistanceM: Double? = null,
    val totalAscentM: Double? = null,
    val totalDescentM: Double? = null,
    val legs: List<PlannedLegJson> = emptyList(),
)

@Serializable
data class MediaJson(
    val id: String? = null,
    val type: String = "IMAGE",
    val timestamp: Long = 0,
    val lat: Double? = null,
    val lng: Double? = null,
    val fileName: String? = null,
    val uri: String? = null,
    val note: String? = null,
    val durationMs: Long? = null,
)

/** splits 为计算型字段透传（F-IO-09 默认全含；导入不落库，冻结决策 14） */
@Serializable
data class TripJson(
    val id: String,
    val name: String = "",
    val note: String? = null,
    val crs: String? = null, // 缺失按 WGS-84 处理（PRD 7.5）
    val crsNote: String? = null,
    val altitudeSource: String? = null,
    val altitudeSourceNote: String? = null,
    val stepSource: String? = null,
    val stepSourceNote: String? = null,
    val startTime: String? = null,
    val endTime: String? = null,
    val status: String? = null,
    val hasBarometer: Boolean? = null,
    val stats: TripStatsJson? = null,
    val legs: TripLegsJson? = null,
    val segments: List<SegmentJson> = emptyList(),
    val trackPoints: TrackPointsJson? = null,
    val markers: List<MarkerJson> = emptyList(),
    val alertSettings: AlertSettingsJson? = null,
    val plannedRoute: PlannedRouteJson? = null,
    val media: List<MediaJson> = emptyList(),
    val splits: JsonElement? = null,
)

/** gohiking.trip 单条记录信封（PRD 7.3） */
@Serializable
data class TripEnvelope(
    val schema: String = SCHEMA,
    val schemaVersion: String = SUPPORTED_VERSION,
    val exportedAt: String? = null,
    val generator: GeneratorInfo? = null,
    val trip: TripJson,
) {
    companion object {
        const val SCHEMA = "gohiking.trip"
        const val SUPPORTED_VERSION = "1.0"
        const val SUPPORTED_MAJOR = 1
    }
}

/** gohiking.planned_route 计划线路信封（PRD 7.3 末尾） */
@Serializable
data class PlannedRouteEnvelope(
    val schema: String = SCHEMA,
    val schemaVersion: String = SUPPORTED_VERSION,
    val exportedAt: String? = null,
    val generator: GeneratorInfo? = null,
    val plannedRoute: PlannedRouteJson,
) {
    companion object {
        const val SCHEMA = "gohiking.planned_route"
        const val SUPPORTED_VERSION = "1.0"
    }
}

@Serializable
data class BackupCounts(val trips: Int = 0, val plannedRoutes: Int = 0, val mediaFiles: Int = 0)

@Serializable
data class BackupOptions(
    val containsMedia: Boolean = false,
    val settingsIncluded: Boolean = true,
    val checksumsIncluded: Boolean = true,
)

/** gohiking.backup manifest（PRD 7.4） */
@Serializable
data class BackupManifest(
    val schema: String = SCHEMA,
    val schemaVersion: String = SUPPORTED_VERSION,
    val exportedAt: String? = null,
    val generator: GeneratorInfo? = null,
    val crs: String? = null,
    val counts: BackupCounts = BackupCounts(),
    val options: BackupOptions = BackupOptions(),
    val checksumAlgorithm: String? = null,
    val checksums: Map<String, String> = emptyMap(),
) {
    companion object {
        const val SCHEMA = "gohiking.backup"
        const val SUPPORTED_VERSION = "1.0"
    }
}

/** 解析后的单个导入文件 */
sealed interface ParsedFile {
    val name: String

    data class TripFile(override val name: String, val envelope: TripEnvelope) : ParsedFile
    data class RouteFile(override val name: String, val envelope: PlannedRouteEnvelope) : ParsedFile

    /** 不符合 schema 或版本不兼容；reason 面向结果报告（F-IO-34） */
    data class Invalid(
        override val name: String,
        val code: ImportReasonCode,
        val arg: String? = null,
    ) : ParsedFile
}

/** 冲突策略（F-IO-27/40~43）；默认跳过（F-IO-42） */
enum class ConflictPolicy { SKIP, OVERWRITE, DUPLICATE, ASK }

/** 逐条事务导入的数据包 */
data class TripBundle(
    val trip: com.gohiking.core.database.entity.TripEntity,
    val points: List<com.gohiking.core.database.entity.TrackPointEntity>,
    val markers: List<com.gohiking.core.database.entity.MarkerEntity>,
    val media: List<com.gohiking.core.database.entity.MediaRefEntity>,
)

data class PlannedRouteBundle(
    val route: com.gohiking.core.database.entity.PlannedRouteEntity,
    val legs: List<com.gohiking.core.database.entity.PlannedLegEntity>,
    val waypoints: List<com.gohiking.core.database.entity.PlannedWaypointEntity>,
)
