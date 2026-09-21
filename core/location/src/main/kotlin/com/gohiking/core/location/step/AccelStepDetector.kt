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

    // —— N-05 预热自适应（见 accept 内注释）——
    private var warmupThreshold = DEFAULT_THRESHOLD
    private var warmupMaxPeak = 0.0
    private var warmupStartedAtMs = Long.MIN_VALUE

    var steps = 0
        private set
    var warmupRemaining = WARMUP_STEPS
        private set

    /**
     * N-05：预热期是否观测到任何可辨识的步态峰。为 false 说明设备上没有步行信号，
     * 上层应把 wire 降级为 UNAVAILABLE（UI 显示「—」），而不是显示「0 步」——
     * 后者与「确实一步没走」无法区分，违反「绝不编造」的产品红线。
     */
    val gaitDetected: Boolean get() = warmupMaxPeak > MIN_WARMUP_THRESHOLD || steps > 0

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
        val isPeak = prevSlope > 0 && slope <= 0
        var counted = false

        // N-05：预热期先**无条件**记录局部峰幅（不受阈值门槛限制）并据此自适应阈值。
        // 原实现存在死锁——峰值必须超过固定阈值 DEFAULT_THRESHOLD(0.8) 才会递减
        // warmupRemaining，而阈值又只有在集满 25 峰之后才自适应。轻步态或手机放在
        // 背包/厚衣物里（仿真 damp=0.4 时全部组合失败）预热永远完不成 → 步数恒为 0，
        // 且 wire 仍报 ACCEL_ALGORITHM，UI 显示「0 步」而非「—」。
        if (isPeak && warmupRemaining > 0 && prevFiltered > warmupMaxPeak) {
            warmupMaxPeak = prevFiltered
            warmupThreshold = (WARMUP_THRESHOLD_RATIO * warmupMaxPeak)
                .coerceIn(MIN_WARMUP_THRESHOLD, DEFAULT_THRESHOLD)
        }

        val effectiveThreshold = if (warmupRemaining > 0) warmupThreshold else threshold
        if (isPeak && prevFiltered > effectiveThreshold) {
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
                // 时间兜底：预热超过 WARMUP_TIMEOUT_MS 仍未集满 25 峰，用已观测到的最大峰
                // 强行结束预热。宁可初始阈值不准（后续 50 步滑动窗口会持续修正），
                // 也不能让计步恒为 0。
                if (warmupRemaining > 0) {
                    if (warmupStartedAtMs == Long.MIN_VALUE) warmupStartedAtMs = now
                    else if (now - warmupStartedAtMs >= WARMUP_TIMEOUT_MS) finishWarmup()
                }
            }
        }
        prevSlope = slope
        prevFiltered = filtered
        return counted
    }

    /** 用当前已观测到的峰值强制结束预热（时间兜底路径） */
    private fun finishWarmup() {
        threshold = if (warmupMaxPeak > 0.0) {
            (WARMUP_THRESHOLD_RATIO * warmupMaxPeak).coerceAtLeast(MIN_WARMUP_THRESHOLD)
        } else {
            DEFAULT_THRESHOLD
        }
        warmupRemaining = 0
        // 已采集的预热峰中超阈者补入自适应窗口（warmupPeaks 可能为空 → 避免除零）
        warmupPeaks.filter { it > threshold }.forEach {
            if (recentPeaks.size < ADAPT_WINDOW) recentPeaks.addLast(it)
        }
    }

    companion object {
        const val SAMPLE_RATE_HZ = 50.0
        const val ALPHA = 0.9 // 低通去重力系数（DEV §4.4 表）
        const val MIN_STEP_INTERVAL_MS = 250L
        const val WARMUP_STEPS = 25 // 预热 25 步不计步（§4.11 与测试断言对齐）
        const val ADAPT_WINDOW = 50 // 连续 50 步滑动更新阈值
        const val DEFAULT_THRESHOLD = 0.8 // 预热期兜底阈值（m/s² 量级，合加速度去重力后）
        // N-05：预热期阈值不再固定，而是 = 观测到的最大峰 × 该系数（夹在下面两个边界之间），
        // 否则小幅步态永远跨不过 0.8 的门槛，预热无法完成、步数恒为 0。
        const val WARMUP_THRESHOLD_RATIO = 0.6
        const val MIN_WARMUP_THRESHOLD = 0.1 // 下限：避免把静态噪声当成步
        const val WARMUP_TIMEOUT_MS = 20_000L // 预热时间兜底
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
