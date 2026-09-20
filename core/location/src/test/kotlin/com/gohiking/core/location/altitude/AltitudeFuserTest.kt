package com.gohiking.core.location.altitude

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * AltitudeFuser 单测（DEV §4.3 / §4.11）。
 * 用假时钟：nowMs 可推进，全部时间相关行为（标定窗口、漂移周期、稳定确认）可控。
 */
class AltitudeFuserTest {

    private var t = 0L
    private fun fuser(hasBarometer: Boolean) = AltitudeFuser(hasBarometer, nowMs = { t })

    // —— 标定窗口 ——

    @Test
    fun `baro path - current is null before calibration window completes`() {
        val f = fuser(hasBarometer = true)
        f.onGpsFix(100.0, 5.0)
        f.onPressure(1013.25)
        assertNull(f.current())
    }

    @Test
    fun `baro path - altRef is median of GPS altitudes in first 10 seconds`() {
        val f = fuser(hasBarometer = true)
        listOf(100.0, 102.0, 104.0, 101.0, 103.0).forEach { f.onGpsFix(it, 5.0) }
        t = 10_001L
        f.onGpsFix(300.0, 5.0) // 窗口后 spike，不进标定集
        f.onPressure(1013.25)
        t = 10_100L
        // 标定集 = {100,102,104,101,103} 中位数 102；气压 = 标准大气 → 气压高度 ≈ pRef → 估值 ≈ 102
        assertEquals(102.0, f.current()!!, 0.5)
        assertEquals(AltitudeFuser.Source.BAROMETER_FUSED, f.source)
    }

    @Test
    fun `baro path - pressure drop of 1hPa raises estimate about 8_4m`() {
        val f = fuser(hasBarometer = true)
        repeat(11) { i ->
            t = i * 1000L
            f.onGpsFix(100.0, 5.0)
            f.onPressure(1013.25)
        }
        t = 11_000L
        f.onPressure(1003.25) // -10 hPa（sec 11 桶）
        t = 12_000L
        f.onPressure(1003.25) // 提交 sec 11 桶 → latest 生效
        val est = f.current()!!
        assertTrue("expected >105, got $est", est > 105.0) // 10hPa ≈ 84m
    }

    // —— 中值滤波 ——

    @Test
    fun `baro path - median filter suppresses single-point spike`() {
        val f = fuser(hasBarometer = true)
        // 标定 100m
        repeat(11) { i ->
            t = i * 1000L
            f.onGpsFix(100.0, 5.0)
            f.onPressure(1013.25)
        }
        t = 11_000L
        // 9 个稳定秒里夹一个 spike 秒
        val pressures = listOf(1013.25, 1013.25, 1003.25, 1013.25, 1013.25, 1013.25, 1013.25, 1013.25, 1013.25, 1013.25)
        pressures.forEachIndexed { i, p ->
            t = 11_000L + i * 1000L
            f.onPressure(p)
        }
        t = 21_000L
        val est = f.current()!!
        assertTrue("spike should be filtered, got $est", est < 104.0)
    }

    // —— 漂移修正 ——

    @Test
    fun `baro path - drift correction after 60s pulls estimate toward GPS median`() {
        val f = fuser(hasBarometer = true)
        repeat(11) { i ->
            t = i * 1000L
            f.onGpsFix(100.0, 5.0)
            f.onPressure(1013.25)
        }
        // 60 秒内 GPS 稳定在 110（气压仍报 100 附近）
        for (i in 11..70) {
            t = i * 1000L
            f.onGpsFix(110.0, 5.0)
            f.onPressure(1013.25)
        }
        // 60s 到点触发修正：altRef += 0.1 * (110 - est) → est 上移约 1
        t = 71_000L
        f.onGpsFix(110.0, 5.0)
        f.onPressure(1013.25)
        t = 71_500L
        val est = f.current()!!
        assertTrue("drift correction should raise estimate, got $est", est > 100.5)
        assertTrue("correction is first-order k=0.1, should stay well below 110, got $est", est < 103.0)
    }

    // —— GPS_ONLY 路径 ——

    @Test
    fun `gps-only path - vertical accuracy above 20m is gated out`() {
        val f = fuser(hasBarometer = false)
        repeat(11) { i ->
            t = i * 1000L
            f.onGpsFix(100.0, 5.0)
        }
        t = 11_000L
        f.onGpsFix(500.0, 25.0) // 精度 >20m，不参与
        t = 11_100L
        assertEquals(100.0, f.current()!!, 0.5)
        assertEquals(AltitudeFuser.Source.GPS_ONLY, f.source)
    }

    @Test
    fun `gps-only path - change requires 10s continuous stability`() {
        val f = fuser(hasBarometer = false)
        repeat(11) { i ->
            t = i * 1000L
            f.onGpsFix(100.0, 5.0)
        }
        t = 11_000L
        f.onGpsFix(120.0, 5.0) // 候选变化，但只出现一次
        t = 11_100L
        assertEquals("unstable change must not confirm", 100.0, f.current()!!, 1.0)
        // 连续稳定 10 秒
        for (i in 12..25) {
            t = i * 1000L
            f.onGpsFix(120.0, 5.0)
        }
        t = 26_000L
        f.onGpsFix(120.0, 5.0)
        t = 26_100L
        assertEquals("stable change should confirm to 120", 120.0, f.current()!!, 1.0)
    }

    @Test
    fun `gps-only path - msl altitude takes priority`() {
        val f = fuser(hasBarometer = false)
        repeat(11) { i ->
            t = i * 1000L
            f.onGpsFix(50.0, 5.0, mslAltM = 100.0)
        }
        t = 11_000L
        f.onGpsFix(50.0, 5.0, mslAltM = 100.0)
        t = 11_100L
        assertEquals(100.0, f.current()!!, 0.5)
    }

    // —— 手动校准 ——

    @Test
    fun `manual calibration applies offset and switches source`() {
        val f = fuser(hasBarometer = false)
        repeat(11) { i ->
            t = i * 1000L
            f.onGpsFix(100.0, 5.0)
        }
        t = 11_000L
        f.onGpsFix(100.0, 5.0)
        t = 11_100L
        val before = f.current()!!
        f.calibrateTo(before + 13.0)
        assertEquals(before + 13.0, f.current()!!, 0.1)
        assertEquals(AltitudeFuser.Source.MANUAL_CALIBRATED, f.source)
    }

    @Test
    fun `manual calibration on unavailable altitude is a no-op`() {
        val f = fuser(hasBarometer = true)
        f.calibrateTo(100.0)
        assertNull(f.current())
        assertNull(f.source)
    }

    // —— 暂停保留 ——

    @Test
    fun `pause keeps calibration and median window`() {
        val f = fuser(hasBarometer = true)
        repeat(11) { i ->
            t = i * 1000L
            f.onGpsFix(100.0, 5.0)
            f.onPressure(1013.25)
        }
        val before = f.current()
        f.onSessionPause()
        t += 5_000L
        f.onPressure(1013.25)
        assertEquals(before!!, f.current()!!, 0.5)
    }
}
