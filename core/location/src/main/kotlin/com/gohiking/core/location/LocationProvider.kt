package com.gohiking.core.location

/** 定位修复（DEV §6.2）。坐标统一 GCJ-02：高德源不转，Fused 源必须先转 */
data class LocationFix(
    val lat: Double,
    val lng: Double,
    val altitudeM: Double?,
    val verticalAccuracyM: Float?,
    val mslAltitudeM: Double?, // API 34+ getMslAltitudeMeters（PRD 4.4.2 防线⑤）
    val accuracyM: Float,
    val speedMps: Float,
    val bearing: Float,
    val timestampMs: Long,
)

/**
 * 定位源接口（DEV §6.2）。fixes 用 SharedFlow：多消费者（会话 + 地图蓝点）可同时订阅。
 * 采样间隔：高精度 1000ms / 省电 3000ms（PRD 6.3.7）。
 */
interface LocationProvider {
    val fixes: kotlinx.coroutines.flow.Flow<LocationFix>
    val droppedCount: Int // 回调 Channel 丢点计数（DROP_OLDEST），会话结束 diagnostics 可见
    fun start(intervalMs: Long)
    fun stop()
}
