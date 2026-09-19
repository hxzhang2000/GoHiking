package com.gohiking.core.database.entity

import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * 记录中状态快照（DEV §3.1.1 ①）——恒为 1 行（id=1），承载 F-REC-09 崩溃恢复
 * 与「每 10 秒持久化一次状态」。[status]：RECORDING / PAUSED。
 */
@Entity(tableName = "recording_state")
data class RecordingStateEntity(
    @PrimaryKey val id: Int = 1,
    val tripId: String,
    val status: String,
    val startedAt: Long,
    val lastPausedAt: Long?,
    val accumulatedPausedMs: Long,
    val currentSegment: Int,
    val distanceM: Double, // 已累计距离（恢复后继续累加）
    val ascentAccumM: Double,
    val descentAccumM: Double,
    val lastDistanceMarkM: Double, // 距离提醒上次触发累计值
    val lastAscentMarkM: Double,
    val lastDescentMarkM: Double,
    val stepAtStart: Int, // 计步传感器基准值（-1 = 不可用）
    val stepSourceUsed: String,
    val hasBarometer: Boolean,
    val altitudeRefM: Double?, // 海拔融合基准 altRef
    val pressureRefHpa: Double?, // 气压计基准
    val statAnchorM: Double?, // 统计累加器锚点海拔（3m/10m 阈值法，恢复时回灌；DEV 决策 5）
    val markAnchorM: Double?, // 打点累加器锚点海拔（100m 阈值法）
    val movingSecBySegmentJson: String, // 逐段运动秒数 JSON（key=segmentIndex；DEV §4.12）
    val updatedAt: Long,
)

/**
 * 高程查询缓存（DEV §3.1.1 ③）——§4.10 三级降级要求结果落本地缓存。
 * 复合主键 (latKey, lngKey)，键取 5 位小数（约 1m 格网）。
 * DEM 不变 → 缓存长期有效不设过期，仅在主动清除或更换数据源时清空。
 */
@Entity(tableName = "elevation_cache", primaryKeys = ["latKey", "lngKey"])
data class ElevationCacheEntity(
    val latKey: Double,
    val lngKey: Double,
    val altitudeM: Double,
    val source: String, // REMOTE / DEM_TILE
    val fetchedAt: Long,
)
