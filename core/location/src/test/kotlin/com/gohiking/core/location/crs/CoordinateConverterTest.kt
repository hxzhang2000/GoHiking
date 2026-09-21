package com.gohiking.core.location.crs

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs

class CoordinateConverterTest {

    @Test
    fun `境外坐标不偏移`() {
        val result = CoordinateConverter.wgs84ToGcj02(48.8584, 2.2945) // 巴黎
        assertEquals(48.8584, result.first, 1e-12)
        assertEquals(2.2945, result.second, 1e-12)
    }

    @Test
    fun `境内坐标产生非线性偏移`() {
        // 北京附近（WGS-84）
        val (gLat, gLng) = CoordinateConverter.wgs84ToGcj02(40.0, 116.0)
        // 偏移量应在几十~几百米量级（约 0.001~0.006 度），且纬度经度偏移不相等（非线性）
        val dLat = gLat - 40.0
        val dLng = gLng - 116.0
        assertTrue(abs(dLat) in 0.0005..0.01)
        assertTrue(abs(dLng) in 0.0005..0.01)
        assertFalse(abs(abs(dLat) - abs(dLng)) < 1e-9)
    }

    @Test
    fun `gcjToWgs_迭代收敛_往返误差在毫米级`() {
        val wgs = 34.2592 to 108.9473 // 西安
        val gcj = CoordinateConverter.wgs84ToGcj02(wgs.first, wgs.second)
        val back = CoordinateConverter.gcj02ToWgs84(gcj.first, gcj.second)
        // 往返误差 < 1e-6 度 ≈ 0.1 m
        assertEquals(wgs.first, back.first, 1e-6)
        assertEquals(wgs.second, back.second, 1e-6)
    }

    @Test
    fun `gcjToWgs_迭代次数_小于5`() {        // DEV §4.11：gcjToWgs_迭代收敛 断言「迭代次数 < 5」
        var iterations = 0
        var wLat = 39.9; var wLng = 116.3
        repeat(CoordinateConverter.MAX_ITER) {
            val (gLat, gLng) = CoordinateConverter.wgs84ToGcj02(wLat, wLng)
            val dLat = gLat - 39.9; val dLng = gLng - 116.3
            if (abs(dLat) < CoordinateConverter.EPS_DEG && abs(dLng) < CoordinateConverter.EPS_DEG) return@repeat
            wLat -= dLat; wLng -= dLng
            iterations++
        }
        assertTrue("期望 <5 次收敛，实际 $iterations", iterations < 5)
    }

    /**
     * H-10 回归：比对**标准公开算法**的基准值。
     * transformLat 的第二个多项式项系数曾误写为 20（应为 40），
     * 导致纬度方向系统性偏差最多约 13.4 m；这里用三个城市把标准值钉死。
     */
    @Test
    fun `符合标准算法基准值_H10回归`() {
        // (wgsLat, wgsLng) -> (gcjLat, gcjLng)，取自公开参考实现的精确输出
        val samples = listOf(
            Triple(39.908722, 116.397499, 39.910125500 to 116.403742575), // 北京
            Triple(30.657200, 104.066500, 30.654779283 to 104.069008781), // 成都
            Triple(30.545400, 114.305500, 30.542973324 to 114.310940794), // 武汉（偏差最大）
        )
        for ((wLat, wLng, expected) in samples) {
            val actual = CoordinateConverter.wgs84ToGcj02(wLat, wLng)
            assertEquals("lat @$wLat", expected.first, actual.first, 1e-8)
            assertEquals("lng @$wLng", expected.second, actual.second, 1e-8)
        }
    }
}
