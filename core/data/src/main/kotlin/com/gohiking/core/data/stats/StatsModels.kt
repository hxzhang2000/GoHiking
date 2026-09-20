package com.gohiking.core.data.stats

/**
 * 统计计算结果模型（DEV §4.12）。
 * ⚠ splits / legs 不落库：按需计算（O(n)）+ 内存 LruCache（key = tripId）。
 */

/** 每公里分段行（PRD F-HIS-25 byKilometer 表） */
data class KmSplit(
    val index: Int, // 第几段（0 起）
    val distanceM: Double,
    val movingSec: Long, // 段内运动时间（跳过跨暂停的时间差）
    val paceSecPerKm: Long?, // 段距离 < 50m 时为 null
)

/** 每爬升分段行（byAltitudeGain 表；ascentThresholdM 默认 100m） */
data class GainSplit(
    val index: Int,
    val gainM: Double, // 实际爬升（尾段不足照常列出）
    val distanceM: Double,
    val movingSec: Long,
    val paceSecPerKm: Long?,
)

/** 上下山切分（F-REC-37）：以时间上最后一个 SUMMIT 为界 */
data class Legs(
    val uphill: LegSummary,
    val downhill: LegSummary,
)

data class LegSummary(
    val distanceM: Double,
    val movingSec: Long,
    val ascentM: Double,
    val descentM: Double,
)
