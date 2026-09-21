package com.gohiking.core.elevation

import com.gohiking.core.common.result.GhError
import com.gohiking.core.common.result.GhResult
import com.gohiking.core.model.Difficulty
import com.gohiking.core.model.LatLngValue
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class RouteEvaluatorTest {

    /** 平地：每个点海拔 = 沿线累计公里数 × 10m/km（可控的斜坡） */
    private fun slopeLine(points: Int, metersPerKm: Double): List<LatLngValue> {
        // 沿经线每 0.001° ≈ 111.19m（haversine 平均半径 R=6371000；期望值必须与实现同模型）
        return (0 until points).map { i ->
            LatLngValue(0.0, i * 0.001)
        }
    }

    // ---------- difficultyOf：严格不等号边界（PRD 6.2.3） ----------

    @Test
    fun difficulty_边界值恰好不满足本档() {
        // 恰好 5km / 300m → 不满足 EASY（严格不等号），归 MODERATE
        assertEquals(Difficulty.MODERATE, RouteEvaluator.difficultyOf(5_000.0, 300.0))
        assertEquals(Difficulty.MODERATE, RouteEvaluator.difficultyOf(5_000.0, 299.9))
        assertEquals(Difficulty.MODERATE, RouteEvaluator.difficultyOf(4_999.9, 300.0))
        // 恰好 10km / 800m → 不满足 MODERATE → HARD
        assertEquals(Difficulty.HARD, RouteEvaluator.difficultyOf(10_000.0, 800.0))
        // 恰好 15km / 1500m → 不满足 HARD → CHALLENGING
        assertEquals(Difficulty.CHALLENGING, RouteEvaluator.difficultyOf(15_000.0, 1_500.0))
        // 常规档内
        assertEquals(Difficulty.EASY, RouteEvaluator.difficultyOf(4_999.9, 299.9))
        assertEquals(Difficulty.MODERATE, RouteEvaluator.difficultyOf(9_999.9, 799.9))
        assertEquals(Difficulty.HARD, RouteEvaluator.difficultyOf(14_999.9, 1_499.9))
        // 距离够近但爬升爆表 → 下一档
        assertEquals(Difficulty.CHALLENGING, RouteEvaluator.difficultyOf(1_000.0, 2_000.0))
    }

    // ---------- evaluate：正常路径 ----------

    @Test
    fun evaluate_线性高程_爬升等于高差() = runTest {
        // 99 段 × 111.19m ≈ 11.0km，海拔 0 → 99m（每点 +1m，阈值 10m 逐步触发）
        val pts = (0 until 100).map { i -> LatLngValue(0.0, i * 0.001) }
        val fake: suspend (List<LatLngValue>) -> GhResult<List<Double?>> = { samples ->
            GhResult.Ok(samples.map { it.longitude * 1000.0 }) // 经度 0.001° → +1m 海拔
        }
        val m = RouteEvaluator.evaluate(pts, fake)
        assertTrue(m.elevationAvailable)
        assertEquals(11008.3, m.distanceM, 2.0) // 99 × 111.1949（R=6371000 haversine）
        // 总高差 99m（0→0.099°）±阈值锚点误差
        assertTrue("ascent=${m.ascentM}", m.ascentM!! in 89.0..99.5)
        assertEquals(99.0, m.maxAltitudeM!!, 0.5)
        assertEquals(0.0, m.minAltitudeM!!, 0.5)
        // 11.008km ÷ 3.5km/h ≈ 11322s
        assertEquals(11_322.8, m.estimatedDurationSec.toDouble(), 2.0)
    }

    @Test
    fun evaluate_高程不可用_指标为null不编造() = runTest {
        val pts = (0 until 50).map { i -> LatLngValue(0.0, i * 0.001) }
        val errFake: suspend (List<LatLngValue>) -> GhResult<List<Double?>> = {
            GhResult.Err(GhError.ElevationUnavailable("offline"))
        }
        val m = RouteEvaluator.evaluate(pts, errFake)
        assertFalse(m.elevationAvailable)
        assertNull(m.ascentM)
        assertNull(m.descentM)
        assertNull(m.maxAltitudeM)
        assertNull(m.minAltitudeM)
        // 距离仍由折线给出：50 点 = 49 段 × 111.1949m
        assertEquals(5_448.6, m.distanceM, 2.0)
    }

    /**
     * N-40：原断言是 `assertFalse(m.elevationAvailable)` —— 它把「一个采样点没拿到高程
     * ⇒ 整条线路爬升永久 null」这条缺陷固化成了期望行为。真实场景里高程是分批远程请求
     * 的（100 点/批），任意一批抖动就会让爬升显示「—」。
     * 现行为：按连续已知段分段累计，结果可用但须标 elevationPartial。
     */
    @Test
    fun evaluate_部分点缺失_按已知段累计并标注partial() = runTest {
        val pts = (0 until 50).map { i -> LatLngValue(0.0, i * 0.001) }
        val partialFake: suspend (List<LatLngValue>) -> GhResult<List<Double?>> = { samples ->
            GhResult.Ok(samples.mapIndexed { i, _ -> if (i == 3) null else i.toDouble() })
        }
        val m = RouteEvaluator.evaluate(pts, partialFake)
        assertTrue(m.elevationAvailable)
        assertTrue("应标注为不完整", m.elevationPartial)
        assertNotNull("已知点足够时应给出爬升", m.ascentM)
        assertTrue("爬升应大于 0：${m.ascentM}", m.ascentM!! > 0.0)
        // 缺口处不连线，因此爬升是下界：严格小于完整数据的爬升
        val fullFake: suspend (List<LatLngValue>) -> GhResult<List<Double?>> = { samples ->
            GhResult.Ok(samples.mapIndexed { i, _ -> i.toDouble() })
        }
        val full = RouteEvaluator.evaluate(pts, fullFake)
        assertFalse("完整数据不应标 partial", full.elevationPartial)
        assertTrue(
            "缺口会让爬升成为下界：partial=${m.ascentM} full=${full.ascentM}",
            m.ascentM!! <= full.ascentM!! + 1e-9,
        )
    }

    /** N-40：已知点太少（< 2）时仍必须判为不可用，绝不编造 */
    @Test
    fun evaluate_已知点太少_仍判不可用() = runTest {
        val pts = (0 until 50).map { i -> LatLngValue(0.0, i * 0.001) }
        val almostAllMissing: suspend (List<LatLngValue>) -> GhResult<List<Double?>> = { samples ->
            GhResult.Ok(samples.mapIndexed { i, _ -> if (i == 0) 100.0 else null })
        }
        val m = RouteEvaluator.evaluate(pts, almostAllMissing)
        assertFalse(m.elevationAvailable)
        assertNull(m.ascentM)
        assertNull(m.maxAltitudeM)
    }

    @Test
    fun evaluate_降海拔_统计下降() = runTest {
        val pts = (0 until 60).map { i -> LatLngValue(0.0, i * 0.001) }
        val fake: suspend (List<LatLngValue>) -> GhResult<List<Double?>> = { samples ->
            GhResult.Ok(samples.map { 600.0 - it.longitude * 1000.0 }) // 600m 递减到 541m
        }
        val m = RouteEvaluator.evaluate(pts, fake)
        assertTrue(m.elevationAvailable)
        assertTrue("descent=${m.descentM}", m.descentM!! in 49.0..59.5)
        assertEquals(541.0, m.minAltitudeM!!, 0.5)
    }

    // ---------- resample：50m 等距 ----------

    @Test
    fun resample_间距不超采样间隔() {
        val pts = (0 until 50).map { i -> LatLngValue(0.0, i * 0.001) } // 每段 111.19m
        val samples = RouteEvaluator.resample(pts, 50.0)
        // 相邻采样点间距 ≤ 50m + 浮点误差
        for (i in 0 until samples.size - 1) {
            val d = RouteEvaluator.haversineM(samples[i], samples[i + 1])
            assertTrue("d=$d", d <= 50.0 + 1e-6)
        }
        // 采样数 ≈ 总长/50：111.19×49 / 50 ≈ 109
        assertEquals(109.0, (samples.size - 1).toDouble(), 2.0)
        assertTrue(samples.first() == pts.first())
    }

    @Test
    fun resample_短线端点保留() {
        // 0.0001° ≈ 11.1m < 50m 间隔：不产生中间采样点，但首尾端点都在
        val pts = listOf(LatLngValue(30.0, 120.0), LatLngValue(30.0001, 120.0))
        val r = RouteEvaluator.resample(pts, 50.0)
        assertEquals(2, r.size)
        assertEquals(pts, r)
        // 极小间隔时正常加密，且终点仍是最后一个点
        val dense = RouteEvaluator.resample(pts, 5.0)
        assertEquals(pts.last(), dense.last())
        assertTrue(dense.size > 2)
    }

    // ---------- 距离 ----------

    @Test
    fun polylineDistance_两点经线距离() {
        // 0.001° 经线 ≈ 111.19m（R=6371000 haversine）
        val d = RouteEvaluator.haversineM(LatLngValue(0.0, 0.0), LatLngValue(0.0, 0.001))
        assertEquals(111.19, d, 0.1)
    }
}
