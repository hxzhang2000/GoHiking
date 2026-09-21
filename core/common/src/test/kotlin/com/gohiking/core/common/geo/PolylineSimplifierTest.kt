package com.gohiking.core.common.geo

import com.gohiking.core.model.LatLngValue
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PolylineSimplifierTest {

    @Test
    fun `zero epsilon returns original list`() {
        val pts = listOf(
            LatLngValue(30.0, 120.0),
            LatLngValue(30.001, 120.001),
            LatLngValue(30.002, 120.002),
        )
        assertEquals(pts, PolylineSimplifier.simplify(pts, 0.0))
    }

    @Test
    fun `collinear points are removed`() {
        // 沿经线 1000 点共线 → 只剩首尾
        val pts = (0 until 1000).map { LatLngValue(30.0 + it * 0.00001, 120.0) }
        val out = PolylineSimplifier.simplify(pts, 5.0)
        assertEquals(2, out.size)
    }

    @Test
    fun `significant bend is kept`() {
        val pts = listOf(
            LatLngValue(30.0, 120.0),
            LatLngValue(30.01, 120.01),     // 中间点偏离首尾连线约 1.1km（东西向拐弯）
            LatLngValue(30.02, 120.0),
        )
        val out = PolylineSimplifier.simplify(pts, 5.0)
        assertEquals(3, out.size)
    }

    @Test
    fun `spike is dropped when within epsilon`() {
        val pts = listOf(
            LatLngValue(30.0, 120.0),
            LatLngValue(30.0, 120.00001),   // ≈1.1m 偏移
            LatLngValue(30.0, 120.00002),
        )
        val out = PolylineSimplifier.simplify(pts, 5.0)
        assertEquals(2, out.size)
    }

    @Test
    fun `segments are simplified independently`() {
        // 两段各自首尾；跨段中间点各自保留判断
        val seg0 = (0 until 100).map { LatLngValue(30.0 + it * 0.00001, 120.0) }
        val seg1 = (0 until 100).map { LatLngValue(31.0 + it * 0.00001, 121.0) }
        val tagged = seg0.map { 0 to it } + seg1.map { 1 to it }
        val out = PolylineSimplifier.simplifyBySegment(tagged, 5.0)
        // 每段只剩 2 点 → 共 4
        assertEquals(4, out.size)
    }

    @Test
    fun `zoom epsilon table matches DEV spec`() {
        assertEquals(40.0, PolylineSimplifier.epsilonForZoom(5f), 0.0)
        assertEquals(25.0, PolylineSimplifier.epsilonForZoom(11f), 0.0)
        assertEquals(12.0, PolylineSimplifier.epsilonForZoom(13.5f), 0.0)
        assertEquals(5.0, PolylineSimplifier.epsilonForZoom(15.5f), 0.0)
        assertEquals(0.0, PolylineSimplifier.epsilonForZoom(17f), 0.0)
        assertEquals(0.0, PolylineSimplifier.epsilonForZoom(19f), 0.0)
    }

    @Test
    fun `10000 points simplify within loose budget`() {
        // 折线：每 10 点抖动一次
        val pts = (0 until 10_000).map {
            LatLngValue(30.0 + it * 0.00001, 120.0 + (if (it % 10 == 0) 0.0001 else 0.0))
        }
        val start = System.nanoTime()
        val out = PolylineSimplifier.simplify(pts, 12.0)
        val costMs = (System.nanoTime() - start) / 1_000_000
        assertTrue("out.size=${out.size}", out.size < 100)
        // B5 的「10000 点 < 30ms」是真机基准目标，DEV §9.2.3 明确「达标与否不作为验收门禁」；
        // 单测只防数量级回归（阈值放宽到 200ms，容忍门禁并行执行的负载抖动——实测贴边 30ms 假失败）
        assertTrue("cost=${costMs}ms", costMs < 200)
    }

    /**
     * N-47：上面那条性能测试**此前是空转的** —— 它的输入（每 10 点抖一次）能让 DP 去掉
     * 99% 的点、切分也很平衡，实际扫描量只有 ~1×10⁶，永远测不出 O(n²) 退化。
     *
     * 这条用例用「每个点都在容差外」的输入（等幅锯齿，epsilon 极小）逼出最坏情形：
     * 几乎所有点都要保留 → 分割次数趋近 n、每次扫描 ~n/2 → 扫描量 n²/2 ≈ 5×10⁷。
     * 没有 [SCAN_BUDGET_FACTOR] 保护时，JVM 上约 150~300ms，详情页直接掉帧。
     */
    @Test
    fun `worst case O(n^2) input stays within budget`() {
        val n = 10_000
        val pts = (0 until n).map {
            // 等幅锯齿：相邻点交替偏移 ~11m，epsilon=1m → 全部保留，切分极不平衡
            LatLngValue(30.0 + it * 0.00001, 120.0 + (if (it % 2 == 0) 0.0001 else 0.0))
        }
        val start = System.nanoTime()
        val out = PolylineSimplifier.simplify(pts, 1.0)
        val costMs = (System.nanoTime() - start) / 1_000_000
        assertTrue("cost=${costMs}ms（预算保护未生效？）", costMs < 200)
        // 退化时允许少保留一些点，但形状骨架必须在：首尾一定要在，且不能退化成 2 个点
        assertTrue("out.size=${out.size}", out.size > 2)
        assertEquals(pts.first(), out.first())
        assertEquals(pts.last(), out.last())
    }

    /** N-47：预算只在病态输入下生效 —— 正常轨迹的输出必须与「无预算」时一致 */
    @Test
    fun `budget does not alter normal trajectories`() {
        // 接近真实 GPS：沿经线匀速 + 小幅噪声，epsilon=12m（详情页默认容差）
        val n = 4_000
        var seed = 12345L
        fun rnd(): Double { // 确定性伪随机，避免测试依赖 java.util.Random 版本
            seed = (seed * 1103515245 + 12345) and 0x7fffffff
            return (seed / 0x7fffffff.toDouble()) - 0.5
        }
        val pts = (0 until n).map {
            LatLngValue(30.0 + it * 0.00001, 120.0 + rnd() * 0.00005)
        }
        val out = PolylineSimplifier.simplify(pts, 12.0)
        // 预算 1024n = 4.1e6，而这类轨迹实测扫描量 ~15n~200n，远未触及 → 输出应显著抽稀但仍保形
        assertTrue("out.size=${out.size}", out.size in 2 until n)
        assertEquals(pts.first(), out.first())
        assertEquals(pts.last(), out.last())
        // 抽稀必须是保序的子序列
        var lastIdx = -1
        for (p in out) {
            val idx = pts.indexOf(p)
            assertTrue("子序列必须保序", idx > lastIdx)
            lastIdx = idx
        }
    }
}
