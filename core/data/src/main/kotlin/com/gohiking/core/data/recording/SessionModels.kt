package com.gohiking.core.data.recording

import com.gohiking.core.database.entity.MarkerEntity
import com.gohiking.core.database.entity.TripEntity

/**
 * 记录会话状态（DEV §6.1）。RECORDING / PAUSED / FINISHED 对应 DEV 冻结决策 13；
 * IDLE 是会话态，不落库。
 */
sealed interface SessionState {
    data object Idle : SessionState

    data class Active(
        val tripId: String,
        val name: String,
        val plannedRouteId: String?,
        val startedAtMs: Long,
        val currentSegment: Int, // 暂停一次 +1（resume 时增）
        val status: String, // RECORDING / PAUSED
        val distanceM: Double,
        val ascentM: Double,
        val descentM: Double,
        val maxAltitudeM: Double?,
        val minAltitudeM: Double?,
        val accumulatedPausedMs: Long,
        val pausedAtMs: Long?, // PAUSED 时的暂停起点
        val pointCount: Int,
    ) : SessionState {
        val isRecording: Boolean get() = status == "RECORDING"
        val movingDurationSec: Long
            get() {
                val now = System.currentTimeMillis()
                val paused = accumulatedPausedMs + if (pausedAtMs != null) now - pausedAtMs else 0
                return ((now - startedAtMs - paused) / 1000).coerceAtLeast(0)
            }
        val totalDurationSec: Long get() = (System.currentTimeMillis() - startedAtMs) / 1000
    }

    data class Finished(val draft: TripDraft) : SessionState
}

/** stop() 的产物；save() 落库，discard() 丢弃 */
data class TripDraft(
    val trip: TripEntity,
    val markers: List<MarkerEntity>,
    val droppedCount: Int, // 定位流丢点数（DEV §6.2，diagnostics 必须 >0 可见）
    val pointCount: Int,
)

/** 采样事件：已过采样器质量门，供地图蓝点/轨迹线实时绘制 */
data class TrackSample(
    val tripId: String,
    val lat: Double,
    val lng: Double,
    val altitudeM: Double?,
    val quality: Int, // 0=正常 1=低精度 2=异常速度
    val segmentIndex: Int,
    val seq: Int,
    val timestampMs: Long,
)
