package com.gohiking.core.location.altitude

/**
 * 阈值法累加器（DEV §4.3.1）：只有相对上一次确认点的变化超过阈值才计入，并重置锚点。
 *
 * ⚠ 统计累加器（3m/10m）与打点累加器（默认 100m，无气压计下限 30m）是【两个独立实例】
 * （DEV 冻结决策 5）——不要合成一个。anchor 在暂停/继续时保留，恢复后不把中断段位移计入。
 */
class ThresholdAccumulator(private val thresholdM: Double) {
    private var anchor: Double? = null
    var ascent = 0.0
        private set
    var descent = 0.0
        private set

    /** @return 是否发生了计入（用于触发打点判断） */
    fun accept(altitudeM: Double): Boolean {
        val a = anchor ?: run { anchor = altitudeM; return false }
        val delta = altitudeM - a
        return when {
            delta >= thresholdM -> { ascent += delta; anchor = altitudeM; true }
            delta <= -thresholdM -> { descent += -delta; anchor = altitudeM; true }
            else -> false
        }
    }

    /** 崩溃恢复回灌锚点与已累计值（recording_state.statAnchorM 等） */
    fun restore(restoredAnchor: Double?, restoredAscent: Double, restoredDescent: Double) {
        anchor = restoredAnchor
        ascent = restoredAscent
        descent = restoredDescent
    }

    fun currentAnchor(): Double? = anchor
}
