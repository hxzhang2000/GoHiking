package com.gohiking.core.data.stats

import com.gohiking.core.database.entity.MarkerEntity
import com.gohiking.core.database.entity.TrackPointEntity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 统计口径单测（PRD 6.5.2 口径表 + DEV §4.12）。
 * 合成轨迹：沿经线向北，每点约 100m，海拔线性或可控变化。
 */
class StatsCalculatorsTest {

    private var t = 0L
    private var seq = 0
    private var seg = 0

    /** 每调用一次前进 distM 米（纬度北向），海拔按 altDelta 变化 */
    private fun pt(distM: Double, alt: Double?, quality: Int = 0, speed: Double? = null): TrackPointEntity {
        val prevLat = lastLat
        lastLat += distM / 111_320.0
        t += 60_000L // 每点 60s
        return TrackPointEntity(
            tripId = "T", segmentIndex = seg, seq = seq++, timestamp = t,
            latitude = lastLat, longitude = 120.0, altitude = alt,
            accuracy = 5.0, speedMps = speed, bearing = 0.0, quality = quality, distanceM = null,
        )
    }

    private var lastLat = 30.0

    private fun reset() {
        lastLat = 30.0; t = 0L; seq = 0; seg = 0
    }

    private fun marker(type: String, ts: Long, alt: Double?) = MarkerEntity(
        id = "M-$type-$ts", tripId = "T", type = type, timestamp = ts,
        latitude = lastLat, longitude = 120.0, altitude = alt, label = null, note = null, sequence = 1, extraJson = null,
    )

    // —— TripStatsCalculator ——

    @Test
    fun `stats - quality filter, distance, max speed, pace`() {
        reset()
        val pts = listOf(
            pt(100.0, 100.0, speed = 2.0),
            pt(100.0, 110.0, speed = 1.5),
            pt(100.0, 120.0, speed = 3.0, quality = 1), // 低质量：全部指标剔除
            pt(100.0, 130.0, speed = 2.5),
        )
        val stats = TripStatsCalculator.compute(pts, emptyList(), mapOf(0 to 240L))
        assertEquals(300.0, stats.distanceM, 1.0) // 3 个有效间隔
        assertEquals(2.5, stats.maxSpeedMps!!, 1e-9) // 3.0 在低质量点上，已剔除
        assertEquals(130.0, stats.maxAltitudeM!!, 1e-9)
        assertEquals(100.0, stats.minAltitudeM!!, 1e-9)
        assertEquals(240L, stats.movingDurationSec)
        assertEquals(300.0 / 240, stats.avgSpeedMps!!, 0.01) // haversine 距离 299.66m，容差放宽
        assertEquals((240 / 0.3).toLong(), stats.avgPaceSecPerKm)
    }

    @Test
    fun `stats - cross-segment gap is pause, not moving time`() {
        reset()
        val a = pt(100.0, 100.0)
        t += 600_000L // 暂停 10 分钟
        seg = 1
        val b = pt(100.0, 100.0)
        val pts = listOf(a, b)
        val stats = TripStatsCalculator.compute(pts, emptyList(), mapOf(0 to 60L, 1 to 60L))
        // 距离：跨段不累加
        assertEquals(0.0, stats.distanceM, 0.5)
        // 运动时长只来自 movingSecBySegment
        assertEquals(120L, stats.movingDurationSec)
        assertTrue(stats.pausedDurationSec > 0)
    }

    @Test
    fun `stats - threshold ascent recomputed`() {
        reset()
        val pts = listOf(
            pt(100.0, 0.0),
            pt(100.0, 2.0),  // +2 < 3
            pt(100.0, 5.0),  // 累计 +5 ≥ 3 → 计入 5
            pt(100.0, 4.0),  // -1
            pt(100.0, 2.0),  // 累计 -3 → 下降 3
        )
        val stats = TripStatsCalculator.compute(pts, emptyList(), mapOf(0 to 240L), ascentThresholdM = 3.0)
        assertEquals(5.0, stats.totalAscentM, 0.5)
        assertEquals(3.0, stats.totalDescentM, 0.5)
    }

    // —— CalorieCalculator ——

    @Test
    fun `calorie - below 60s returns null`() {
        reset()
        val a = pt(100.0, 100.0)
        t -= 45_000L // 把第二个点的间隔缩到 15s（< 60s 边界内）
        val b = pt(100.0, 101.0)
        assertNull(CalorieCalculator.estimate(listOf(a, b), 65))
    }

    @Test
    fun `calorie - flat uses MET 4_5 and 100m windows`() {
        reset()
        // 10 点 × 100m = 1km 平地，每点 60s → 600s 运动时间
        val pts = (0..10).map { pt(100.0, 100.0) }
        val kcal = CalorieCalculator.estimate(pts, 65)!!
        // 10 窗口 × 4.5 × 65 × 60/3600 = 48.75
        assertEquals(48.75, kcal, 1.0)
    }

    @Test
    fun `calorie - uphill window uses MET 7_0`() {
        reset()
        // 每 100m 爬 5m → 斜率 5% > 3% → 全程 MET 7.0
        val pts = (0..10).map { pt(100.0, 100.0 + it * 5.0) }
        val kcal = CalorieCalculator.estimate(pts, 65)!!
        assertEquals(10 * 7.0 * 65 * 60 / 3600.0, kcal, 1.5)
    }

    // —— SplitsCalculator ——

    @Test
    fun `splits - byKilometer with tail segment`() {
        reset()
        // 25 点间隔 100m → 2.5km；每点 60s
        val pts = (0..25).map { pt(100.0, 100.0) }
        val splits = SplitsCalculator.byKilometer(pts)
        assertEquals(3, splits.size)
        assertEquals(1000.0, splits[0].distanceM, 1.0)
        assertEquals(1000.0, splits[1].distanceM, 1.0)
        assertEquals(500.0, splits[2].distanceM, 3.0) // 尾段照常输出（haversine 实际 497.2m，容差 ±3）
        assertEquals(600L, splits[0].movingSec)
        assertEquals(600L, splits[1].movingSec)
        // 尾段只有 5 个间隔（300s）；跨界点秒数按比例截断后为 298
        assertTrue("movingSec=${splits[2].movingSec}", splits[2].movingSec in 296L..300L)
        assertTrue(splits.all { it.paceSecPerKm != null })
    }

    @Test
    fun `splits - short segment pace is null`() {
        reset()
        val pts = listOf(pt(30.0, 100.0), pt(30.0, 100.0))
        val splits = SplitsCalculator.byKilometer(pts)
        assertNull(splits[0].paceSecPerKm)
    }

    @Test
    fun `splits - byAltitudeGain cuts at threshold`() {
        reset()
        // 每 100m 距离爬 10m（斜率 10%）；阈值 30m → 每 3 点切一段
        val pts = (0..9).map { pt(100.0, 100.0 + it * 10.0) }
        val splits = SplitsCalculator.byAltitudeGain(pts, ascentThresholdM = 30.0)
        assertTrue("size=${splits.size}", splits.size >= 3)
        assertEquals(900.0, splits.sumOf { it.distanceM }, 5.0)
    }

    // —— LegSplitter ——

    @Test
    fun `legs - last summit splits uphill and downhill`() {
        reset()
        val up = (0..5).map { pt(100.0, 100.0 + it * 10.0) } // 上山 600m，爬 50m
        val summitTs = t + 1000
        val down = (0..5).map { pt(100.0, 150.0 - it * 10.0) } // 下山 600m
        val pts = up + down
        val legs = LegSplitter.split(pts, listOf(marker("SUMMIT", summitTs, 150.0)))!!
        assertTrue(legs.uphill.ascentM > legs.downhill.ascentM)
        assertTrue(legs.downhill.descentM > legs.uphill.descentM)
        assertTrue(legs.uphill.distanceM > 0 && legs.downhill.distanceM > 0)
    }

    @Test
    fun `legs - no summit returns null`() {
        reset()
        val pts = (0..5).map { pt(100.0, 100.0) }
        assertNull(LegSplitter.split(pts, emptyList()))
    }
}
