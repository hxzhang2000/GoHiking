package com.gohiking.core.database.entity

/** 曲线图投影行（TrackPointDao.chartPoints）：抽稀交给渲染层，只取三列 */
data class ChartPointRow(
    val timestamp: Long,
    val altitude: Double?,
    val distanceM: Double?,
)

/** 高程缓存查询投影行（ElevationCacheDao.query）：只取键与结果，不含 source/fetchedAt */
data class ElevationCacheRow(
    val latKey: Double,
    val lngKey: Double,
    val altitudeM: Double,
)

