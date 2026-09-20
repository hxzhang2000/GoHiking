package com.gohiking.core.location.step

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.PI
import kotlin.math.sin

/**
 * AccelStepDetector 单测（DEV §4.4 / §4.11）。
 * 合成信号：50Hz 采样，1.8Hz 步频的正弦合加速度叠加重力。
 */
class AccelStepDetectorTest {

    private val fs = 50.0
    private val stepFreqHz = 1.8
    private val amplitude = 3.0 // m/s²，去重力后的摆动幅值

    private class FakeClock {
        var now = 0L
        fun tick() = now
    }

    private fun feedWalk(detector: AccelStepDetector, seconds: Double, clock: FakeClock): Int {
        val n = (seconds * fs).toInt()
        var counted = 0
        for (i in 0 until n) {
            clock.now += 20L // 50Hz
            val t = i / fs
            val a = 9.81 + amplitude * sin(2 * PI * stepFreqHz * t)
            // 合加速度：单轴近似（模长）
            if (detector.accept(a.toFloat(), 0f, 0f)) counted++
        }
        return counted
    }

    @Test
    fun `warmup 25 steps are detected but not counted`() {
        val clock = FakeClock()
        val detector = AccelStepDetector(timestampMs = { clock.tick() })
        feedWalk(detector, 30.0, clock)
        // 30s × 1.8Hz = 54 个波峰；预热 25 步不计 → steps ≈ 54 - 25 = 29（容差 ±8）
        assertTrue("steps=${detector.steps}", detector.steps in 20..38)
        assertEquals(0, detector.warmupRemaining)
    }

    @Test
    fun `steps accumulate at expected rate after warmup`() {
        val clock = FakeClock()
        val detector = AccelStepDetector(timestampMs = { clock.tick() })
        // 预热 14s ≈ 25 步
        feedWalk(detector, 14.0, clock)
        val before = detector.steps
        feedWalk(detector, 30.0, clock)
        val gained = detector.steps - before
        // 30s × 1.8Hz = 54 步，容差 ±12（阈值自适应带来的偏差）
        assertTrue("gained=$gained", gained in 42..66)
    }

    @Test
    fun `min step interval 250ms rejects bursts above 4Hz`() {
        val clock = FakeClock()
        val detector = AccelStepDetector(timestampMs = { clock.tick() })
        // 6Hz 步频超生理范围：算法应被 250ms 间隔截到 ≤4Hz
        val hiFreq = 6.0
        val n = (20 * fs).toInt()
        for (i in 0 until n) {
            clock.now += 20L
            val t = i / fs
            val a = 9.81 + amplitude * sin(2 * PI * hiFreq * t)
            detector.accept(a.toFloat(), 0f, 0f)
        }
        // 20s × 6Hz = 120 峰，间隔截断后 ≤ 80（20s/0.25s）
        assertTrue("steps=${detector.steps}", detector.steps <= 80)
    }

    @Test
    fun `flat signal produces no steps`() {
        val clock = FakeClock()
        val detector = AccelStepDetector(timestampMs = { clock.tick() })
        feedWalk(detector, 0.5, clock) // 初始化重力
        val n = (20 * fs).toInt()
        for (i in 0 until n) {
            clock.now += 20L
            detector.accept(9.81f, 0f, 0f) // 静止
        }
        assertEquals(0, detector.steps)
    }
}
