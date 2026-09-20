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
}
