package com.gohiking.core.location.step

import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * 加速度计自研计步算法（DEV §4.4 级③，依据 PRD 4.5.3/4.5.4）。
 *
 * 流水线：合加速度 → 低通去重力（一阶 IIR α=0.9）→ 带通 0.5–3Hz（二阶 Butterworth，双二次）
 * → 峰值检测 + 自适应阈值 → 最小步间隔 250ms 防抖 → 预热 25 步内不计步。
 *
 * ⚠ §4.4 已声明：滤波系数与自适应阈值系数是文档自创参数、无真机数据支撑，
 * 真机核定前只当起点不是结论（§9.5-B6）。
 */
class AccelStepDetector(
    private val timestampMs: () -> Long = { System.currentTimeMillis() },
) {

    // —— 低通去重力（一阶 IIR）——
    private var gravity = 0.0
    private var gravityInit = false

    // —— 带通（RBJ 双二次，f0=1.22Hz、Q≈0.49 @ 50Hz，覆盖 0.5–3Hz）——
    private val bp = BiquadBandpass(
        sampleRateHz = SAMPLE_RATE_HZ,
        centerHz = sqrt(0.5 * 3.0),
        q = sqrt(0.5 * 3.0) / (3.0 - 0.5),
    )

    // —— 峰值检测 / 自适应阈值 ——
    private var prevFiltered = 0.0
    private var prevSlope = 0.0
    private var lastStepAtMs = Long.MIN_VALUE
    private val warmupPeaks = mutableListOf<Double>()
    private val recentPeaks = ArrayDeque<Double>(ADAPT_WINDOW)
    private var threshold = DEFAULT_THRESHOLD

    var steps = 0
        private set
    var warmupRemaining = WARMUP_STEPS
        private set

    /** 喂入一次三轴采样（建议 50Hz，SENSOR_DELAY_GAME）。@return 本步是否计入 */
    fun accept(x: Float, y: Float, z: Float): Boolean {
        val norm = sqrt(x * x + y * y + z * z.toDouble())

        // 低通去重力
        if (!gravityInit) {
            gravity = norm
            gravityInit = true
            return false
        }
        gravity = ALPHA * gravity + (1 - ALPHA) * norm
        val linear = norm - gravity

        // 带通
        val filtered = bp.process(linear)

        // 峰值检测：由升转降且幅值超阈
        val slope = filtered - prevFiltered
        var counted = false
        if (prevSlope > 0 && slope <= 0 && prevFiltered > threshold) {
            val now = timestampMs()
            if (lastStepAtMs == Long.MIN_VALUE || now - lastStepAtMs >= MIN_STEP_INTERVAL_MS) {
                if (warmupRemaining > 0) {
                    warmupPeaks.add(prevFiltered)
                    warmupRemaining--
                    if (warmupRemaining == 0) {
                        threshold = 0.6 * (warmupPeaks.sum() / warmupPeaks.size)
                        recentPeaks.addAll(warmupPeaks)
                    }
                } else {
                    steps++
                    counted = true
                    recentPeaks.addLast(prevFiltered)
                    if (recentPeaks.size > ADAPT_WINDOW) recentPeaks.removeFirst()
                    threshold = 0.6 * (recentPeaks.sum() / recentPeaks.size)
                }
                lastStepAtMs = now
            }
        }
        prevSlope = slope
        prevFiltered = filtered
        return counted
    }

    companion object {
        const val SAMPLE_RATE_HZ = 50.0
        const val ALPHA = 0.9 // 低通去重力系数（DEV §4.4 表）
        const val MIN_STEP_INTERVAL_MS = 250L
        const val WARMUP_STEPS = 25 // 预热 25 步不计步（§4.11 与测试断言对齐）
        const val ADAPT_WINDOW = 50 // 连续 50 步滑动更新阈值
        const val DEFAULT_THRESHOLD = 0.8 // 预热期兜底阈值（m/s² 量级，合加速度去重力后）
    }
}

/** RBJ cookbook 二阶带通双二次 */
internal class BiquadBandpass(sampleRateHz: Double, centerHz: Double, q: Double) {
    private val a0: Double
    private val b0: Double
    private val b2: Double
    private val a1: Double
    private val a2: Double
    private var x1 = 0.0
    private var x2 = 0.0
    private var y1 = 0.0
    private var y2 = 0.0

    init {
        val w0 = 2 * PI * centerHz / sampleRateHz
        val alpha = sin(w0) / (2 * q)
        val cosW0 = cos(w0)
        a0 = 1 + alpha
        b0 = alpha
        b2 = -alpha
        a1 = -2 * cosW0
        a2 = 1 - alpha
    }

    fun process(x: Double): Double {
        val y = (b0 * x + b2 * x2 - a1 * y1 - a2 * y2) / a0
        // b1 = 0（带通恒为零）
        x2 = x1; x1 = x
        y2 = y1; y1 = y
        return y
    }
}
