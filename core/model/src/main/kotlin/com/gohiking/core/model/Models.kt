package com.gohiking.core.model

/**
 * 领域模型（PRD 7.1 数据设计 / DEV §2.2）。
 * 纯数据类：不带 Android 依赖、不带持久化注解；导出 JSON 的序列化在 core:data 定义 DTO。
 * 枚举值是协议（PRD 9.7.4），不翻译。
 */

enum class TripStatus { RECORDING, PAUSED, FINISHED }

enum class MarkerType { SUMMIT, ALERT_DISTANCE, ALERT_ASCENT, ALERT_DESCENT, MANUAL, UNKNOWN }

enum class TrackPointQuality { OK, LOW_ACCURACY, ABNORMAL_SPEED }

enum class LegType { OUTBOUND, RETURN }

enum class Difficulty { EASY, MODERATE, HARD, CHALLENGING }

enum class WaypointSource { AUTO, MANUAL }

enum class PlannedRouteSource { AUTO, MANUAL, MIXED }

enum class MediaType { IMAGE, VIDEO }

enum class AltitudeSource { BAROMETER_FUSED, GPS_ONLY, MANUAL_CALIBRATED }

enum class StepSource { SENSOR_COUNTER, SENSOR_DETECTOR, ACCEL_ALGORITHM, MANUAL, UNAVAILABLE }

/** 经纬度值对象。注意：内部统一 GCJ-02（PRD 4.3），来源转换在 core:location 完成。 */
data class LatLngValue(
    val latitude: Double,
    val longitude: Double,
)

/** 运动记录主表领域模型（PRD 7.1 trip）。 */
data class Trip(
    val id: String,
    val name: String,
    val note: String? = null,
    val plannedRouteId: String? = null,
    val startTime: Long,
    val endTime: Long? = null,
    val durationSec: Long = 0,
    val movingDurationSec: Long = 0,
    val pausedDurationSec: Long = 0,
    val distanceM: Double = 0.0,
    val totalAscentM: Double = 0.0,
    val totalDescentM: Double = 0.0,
    val maxAltitudeM: Double? = null,
    val minAltitudeM: Double? = null,
    val avgSpeedMps: Double? = null,
    val avgPaceSecPerKm: Long? = null,
    val maxSpeedMps: Double? = null,
    val steps: Int = -1,
    val stepSource: StepSource = StepSource.UNAVAILABLE,
    val caloriesKcal: Double? = null,
    val altitudeSource: AltitudeSource = AltitudeSource.GPS_ONLY,
    val hasBarometer: Boolean = false,
    val status: TripStatus = TripStatus.RECORDING,
    val createdAt: Long = 0,
)

/** 轨迹点（PRD 7.1 track_point）。 */
data class TrackPoint(
    val id: Long = 0,
    val tripId: String,
    val segmentIndex: Int,
    val seq: Int,
    val timestamp: Long,
    val latitude: Double,
    val longitude: Double,
    val altitude: Double? = null,
    val accuracy: Double? = null,
    val speedMps: Double? = null,
    val bearing: Double? = null,
    val quality: TrackPointQuality = TrackPointQuality.OK,
)

/** 标记（PRD 7.1 marker：登顶点 / 距离提醒点 / 海拔提醒点 / 手动标记）。 */
data class Marker(
    val id: String,
    val tripId: String,
    val type: MarkerType,
    val timestamp: Long,
    val latitude: Double,
    val longitude: Double,
    val altitude: Double? = null,
    /** 仅人眼阅读的展示快照；App 渲染必须按 type + 结构化数值本地化生成（PRD 7.1 / 9.7.4）。 */
    val label: String? = null,
    val note: String? = null,
    val sequence: Int = 0,
    val extraJson: String? = null,
)

/** 计划线路（PRD 7.1 planned_route + planned_leg + planned_waypoint）。 */
data class PlannedRoute(
    val id: String,
    val name: String,
    val note: String? = null,
    val source: PlannedRouteSource = PlannedRouteSource.AUTO,
    val createdAt: Long = 0,
    val totalDistanceM: Double = 0.0,
    val totalAscentM: Double = 0.0,
    val totalDescentM: Double = 0.0,
    val legs: List<PlannedLeg> = emptyList(),
)

data class PlannedLeg(
    val id: String,
    val plannedRouteId: String,
    val legType: LegType,
    val distanceM: Double = 0.0,
    val ascentM: Double = 0.0,
    val descentM: Double = 0.0,
    val estimatedMin: Int = 0,
    val difficulty: Difficulty = Difficulty.MODERATE,
    /** 抽稀后的折线（PRD 7.1 polylineJson）。 */
    val polyline: List<LatLngValue> = emptyList(),
    val waypoints: List<Waypoint> = emptyList(),
)

data class Waypoint(
    val id: String,
    val legId: String,
    val order: Int,
    val latitude: Double,
    val longitude: Double,
    val name: String? = null,
    val source: WaypointSource = WaypointSource.MANUAL,
)

/** 媒体引用（PRD 7.1 media_ref：不复制原文件）。 */
data class MediaItem(
    val id: String,
    val tripId: String? = null,
    val uri: String,
    val fileName: String? = null,
    val note: String? = null,
    val mediaType: MediaType,
    val timestamp: Long,
    val latitude: Double? = null,
    val longitude: Double? = null,
    val durationMs: Long? = null,
    val sizeBytes: Long? = null,
)

/** 统计汇总（PRD 6.5.2 唯一口径表；splits 不落库、按需计算）。 */
data class TripStats(
    val distanceM: Double,
    val durationSec: Long,
    val movingDurationSec: Long,
    val pausedDurationSec: Long,
    val totalAscentM: Double,
    val totalDescentM: Double,
    val maxAltitudeM: Double? = null,
    val minAltitudeM: Double? = null,
    /** 距离 ÷ 运动时长；movingDurationSec < 1s 时为 null。 */
    val avgSpeedMps: Double? = null,
    /** 运动时长 ÷ 公里数；distanceM < 50m 时为 null。 */
    val avgPaceSecPerKm: Long? = null,
    val maxSpeedMps: Double? = null,
    val steps: Int = -1,
    val caloriesKcal: Double? = null,
)

/** 提醒设置（PRD 7.3 alertSettings）。 */
data class AlertSettings(
    val enabled: Boolean = true,
    val voiceEnabled: Boolean = true,
    val distanceAlertEnabled: Boolean = true,
    /** 距离阈值（米），范围 50–5000 步进 50（F-ALERT-12）。 */
    val distanceIntervalM: Int = 100,
    val altitudeAlertEnabled: Boolean = true,
    /** 海拔阈值（米），范围 10–1000 步进 10；无气压计下限 30（F-ALERT-22 / F-REC-63）。 */
    val altitudeIntervalM: Int = 100,
)

/** 应用设置（PRD 6.7 设置表；具体键表见 DEV §3.5）。 */
data class AppSettings(
    /** null = 跟随系统（F-I18N-10）。 */
    val language: String? = null,
    /** 体重 kg，卡路里估算用（F-SET-06）。 */
    val weightKg: Int = 65,
    val alertSettings: AlertSettings = AlertSettings(),
    val privacyAgreed: Boolean = false,
)
