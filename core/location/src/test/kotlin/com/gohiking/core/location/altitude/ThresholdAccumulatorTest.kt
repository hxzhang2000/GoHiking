package com.gohiking.core.location.altitude

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ThresholdAccumulatorTest {

    @Test
    fun `噪声不累积`() {
        // DEV §4.11：±2m 抖动序列（阈值 3m），累计爬升为 0
        val acc = ThresholdAccumulator(3.0)
        listOf(100.0, 102.0, 100.0, 102.0, 99.0, 101.0, 100.0).forEach { acc.accept(it) }
        assertEquals(0.0, acc.ascent, 1e-9)
        assertEquals(0.0, acc.descent, 1e-9)
    }

    @Test
    fun `阈值法重置_0到3到6应得ascent6且锚点6`() {
        // DEV §4.11：0→3→6 应得 ascent=6 且锚点=6
        val acc = ThresholdAccumulator(3.0)
        acc.accept(0.0)
        acc.accept(3.0)
        acc.accept(6.0)
        assertEquals(6.0, acc.ascent, 1e-9)
        assertEquals(6.0, acc.currentAnchor()!!, 1e-9)
    }

    @Test
    fun `下降计入descent`() {
        val acc = ThresholdAccumulator(3.0)
        acc.accept(100.0)
        acc.accept(90.0)
        assertEquals(10.0, acc.descent, 1e-9)
        assertEquals(0.0, acc.ascent, 1e-9)
    }

    @Test
    fun `返回值标记是否计入`() {
        val acc = ThresholdAccumulator(10.0)
        acc.accept(500.0)
        assertFalse(acc.accept(505.0)) // 5m < 10m 阈值
        assertTrue(acc.accept(515.0))  // 累计 15m ≥ 10m，计入
    }

    @Test
    fun `restore_回灌锚点与已累计值`() {
        // 崩溃恢复：不把中断段位移计入，但锚点与已累计值必须回灌
        val acc = ThresholdAccumulator(3.0)
        acc.accept(100.0)
        acc.accept(110.0) // ascent=10
        val restored = ThresholdAccumulator(3.0)
        restored.restore(110.0, 10.0, 0.0)
        restored.accept(112.0) // 2m < 3m 不计入
        assertEquals(10.0, restored.ascent, 1e-9)
        restored.accept(113.0) // 相对锚点 110 达 3m，计入
        assertEquals(13.0, restored.ascent, 1e-9)
    }

    @Test
    fun `双累加器独立_打点3次时统计值约等于实际爬升`() {
        // DEV §4.11：打点累加器（100m）触发 3 次时，统计累加器（3m）值 ≈ 实际爬升
        val stat = ThresholdAccumulator(3.0)
        val mark = ThresholdAccumulator(100.0)
        var altitude = 500.0
        // 爬升 320m：每步 1m
        repeat(320) {
            altitude += 1.0
            stat.accept(altitude)
            mark.accept(altitude)
        }
        assertEquals(3, (mark.ascent / 100).toInt()) // 打点触发 3 次
        assertTrue(stat.ascent in 310.0..320.0)      // 统计接近实际
    }
}
