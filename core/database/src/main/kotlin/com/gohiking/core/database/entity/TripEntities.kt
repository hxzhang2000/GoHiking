package com.gohiking.core.database.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * 运动记录主表（PRD 7.1）。
 * 枚举列一律存 TEXT（String），domain 层映射见 DEV §3.3；
 * [status] 只取 RECORDING / PAUSED / FINISHED（DEV 决策 13），只有 FINISHED 进列表与统计。
 */
@Entity(tableName = "trip")
data class TripEntity(
    @PrimaryKey val id: String, // UUID
    val name: String,
    val note: String?,
    val plannedRouteId: String?, // 关联计划线路（可空；不建硬外键，删除计划线路时由仓库层置空）
    val startTime: Long, // ms
    val endTime: Long, // ms
    val durationSec: Long, // 总时长（含暂停）
    val movingDurationSec: Long, // 运动时长（不含暂停）——平均速度/配速分母（DEV 决策 14）
    val pausedDurationSec: Long,
    val distanceM: Double,
    val totalAscentM: Double,
    val totalDescentM: Double,
    val maxAltitudeM: Double?,
    val minAltitudeM: Double?,
    val avgSpeedMps: Double?,
    val avgPaceSecPerKm: Long?, // distanceM < 50m 时为 null（F-HIS-34）
    val maxSpeedMps: Double?,
    val steps: Int, // -1 表示不可用
    val stepSource: String, // SENSOR_COUNTER / SENSOR_DETECTOR / ACCEL_ALGORITHM / MANUAL / UNAVAILABLE
    val caloriesKcal: Double?,
    val altitudeSource: String, // BAROMETER_FUSED / GPS_ONLY / MANUAL_CALIBRATED
    val hasBarometer: Boolean,
    val status: String,
    val createdAt: Long,
)

/**
 * 轨迹点表（PRD 7.1 + DEV §3.2 补列 distanceM）。
 * tripId 不建外键：记录期「点随到随入库」时 trip 行尚未创建（DEV §3.4）。
 */
@Entity(
    tableName = "track_point",
    indices = [
        Index(value = ["tripId", "segmentIndex", "seq"]),
        Index(value = ["tripId", "timestamp"]),
        Index(value = ["tripId", "quality"]), // chartPoints 过滤列必须在索引内（DEV §3.2）
    ],
)
data class TrackPointEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val tripId: String,
    val segmentIndex: Int, // 暂停一次 +1
    val seq: Int, // 段内序号
    val timestamp: Long, // ms
    val latitude: Double, // GCJ-02
    val longitude: Double, // GCJ-02
    val altitude: Double?, // 米；不可用时 null（DEV 决策 3，绝不编造）
    val accuracy: Double?, // 米
    val speedMps: Double?,
    val bearing: Double?,
    val quality: Int, // 0=正常 1=低精度 2=异常速度
    val distanceM: Double?, // 该点为止的累计距离（写入时算好，F-HIS-23/25 依赖）
)

/** 标记表（PRD 7.1）。[type]：SUMMIT / ALERT_DISTANCE / ALERT_ASCENT / ALERT_DESCENT / MANUAL / UNKNOWN */
@Entity(
    tableName = "marker",
    indices = [Index(value = ["tripId", "type"])],
)
data class MarkerEntity(
    @PrimaryKey val id: String, // UUID
    val tripId: String,
    val type: String,
    val timestamp: Long,
    val latitude: Double,
    val longitude: Double,
    val altitude: Double?,
    // 仅人眼阅读的展示快照；App 渲染必须按 type + 结构化数值本地化生成，禁止直接使用（PRD 7.1/7.3）
    val label: String?,
    val note: String?,
    val sequence: Int, // 同类标记内序号
    val extraJson: String?, // 类型特有字段；UNKNOWN 的原始 type 字符串也保留在此（PRD 7.5）
)

/** 运动汇总行（TripDao.observeSummary 的投影） */
data class TripSummaryRow(
    val c: Int, // FINISHED 记录数
    val d: Double, // 总距离
    val t: Long, // 总时长
    val a: Double, // 总爬升
)
