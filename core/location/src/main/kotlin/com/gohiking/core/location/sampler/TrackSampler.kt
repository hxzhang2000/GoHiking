package com.gohiking.core.location.sampler

import com.gohiking.core.location.LocationFix
import com.gohiking.core.location.geo.GeoMath

/**
 * 轨迹采样器（PRD 6.3.7）：
 * - 最小位移过滤：与上一入库点位移 < 3m 不入库（静止时不产生海量重复点）
 * - accuracy > 50m 标记低质量（quality=1），参与绘制但**不参与距离与爬升统计**
 * - 速度 > 30 km/h 标记异常（quality=2），**不参与距离统计**
 * - SDK speed 优先，为 0 或缺失时用差分值（DEV §4.2）
 */
class TrackSampler(
    private val minDisplacementM: Double = 3.0,
    private val lowAccuracyThresholdM: Float = 50f,
    private val abnormalSpeedMps: Double = 30.0 * 1000 / 3600,
) {
    /** 打完质量标记的入库点 */
    data class Sample(val fix: LocationFix, val quality: Int) // 0=正常 1=低精度 2=异常速度

    private var lastAccepted: LocationFix? = null

    /** @return null = 不入库（位移过小） */
    fun accept(fix: LocationFix): Sample? {
        val last = lastAccepted
        val displacement = last?.let {
            GeoMath.distanceMeters(it.lat, it.lng, fix.lat, fix.lng)
        } ?: Double.MAX_VALUE
        if (displacement < minDisplacementM) return null

        val speed = if (last == null || fix.speedMps > 0f) {
            fix.speedMps.toDouble()
        } else {
            val dtMs = (fix.timestampMs - last.timestampMs).coerceAtLeast(0)
            GeoMath.speedMps(displacement, dtMs)
        }

        val quality = when {
            fix.accuracyM > lowAccuracyThresholdM -> 1
            speed > abnormalSpeedMps -> 2
            else -> 0
        }
        val tagged = fix.copy(speedMps = speed.toFloat())
        lastAccepted = tagged
        return Sample(tagged, quality)
    }

    /** 崩溃恢复时重置位移基准（不把恢复点与中断点连线） */
    fun reset() {
        lastAccepted = null
    }
}
