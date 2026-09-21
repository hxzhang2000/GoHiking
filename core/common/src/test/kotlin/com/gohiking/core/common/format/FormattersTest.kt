package com.gohiking.core.common.format

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class FormattersTest {

    /**
     * L-22：DisplayUnitProvider 是进程内单例，英制用例跑完必须复位，
     * 否则会污染同批次其它测试（它们全在默认公制下断言）。
     */
    @After
    fun resetUnits() {
        DisplayUnitProvider.resetToMetric()
    }

    @Test
    fun `distance - meters below 1km, km above`() {
        assertEquals("0 m", Formatters.distanceText(0.0))
        assertEquals("832 m", Formatters.distanceText(832.4))
        assertEquals("999 m", Formatters.distanceText(999.9))
        assertEquals("1.00 km", Formatters.distanceText(1000.0))
        assertEquals("12.35 km", Formatters.distanceText(12345.6))
        assertEquals("0 m", Formatters.distanceText(-5.0)) // 防御：负值归零
    }

    @Test
    fun `duration - h mm ss with unbounded hours`() {
        assertEquals("0:00:00", Formatters.durationText(0))
        assertEquals("0:01:05", Formatters.durationText(65))
        assertEquals("1:00:00", Formatters.durationText(3600))
        assertEquals("9:05:03", Formatters.durationText(32703))
        assertEquals("100:00:00", Formatters.durationText(360_000))
        assertEquals("0:00:00", Formatters.durationText(-3)) // 防御
    }

    @Test
    fun `pace - null for missing or non-positive`() {
        assertNull(Formatters.paceText(null))
        assertNull(Formatters.paceText(0))
        assertNull(Formatters.paceText(-10))
        assertEquals("12'34\"", Formatters.paceText(754))
        assertEquals("0'59\"", Formatters.paceText(59))
        assertEquals("60'00\"", Formatters.paceText(3600))
    }

    @Test
    fun `meters - integer with unit, null as zero`() {
        assertEquals("1234 m", Formatters.metersText(1234.6))
        assertEquals("0 m", Formatters.metersText(null))
    }

    @Test
    fun `speed - mps to kmh, null for missing or non-positive`() {
        assertNull(Formatters.speedText(null))
        assertNull(Formatters.speedText(0.0))
        assertNull(Formatters.speedText(-1.0))
        assertEquals("4.2 km/h", Formatters.speedText(1.1666667)) // 1.1667 m/s ≈ 4.2 km/h
        assertEquals("0.0 km/h", Formatters.speedText(0.001))
    }

    @Test
    fun `kcal - integer with unit, null for missing or non-positive`() {
        assertNull(Formatters.kcalText(null))
        assertNull(Formatters.kcalText(0.0))
        assertNull(Formatters.kcalText(-5.0))
        assertEquals("512 kcal", Formatters.kcalText(512.4))
        assertEquals("1 kcal", Formatters.kcalText(1.2))
    }

    // —— L-22：H-02 新增的英制分支此前零测试（6 用例 32 断言全在默认公制下）——

    @Test
    fun `imperial - distance in yards below 1 mile, miles above`() {
        DisplayUnitProvider.update(DisplayUnits(distance = DistanceUnit.IMPERIAL))
        assertEquals("0 yd", Formatters.distanceText(0.0))
        assertEquals("910 yd", Formatters.distanceText(832.4)) // 832.4 m ≈ 910 yd
        assertEquals("1.00 mi", Formatters.distanceText(DisplayUnitProvider.mPerMile))
        assertEquals("7.67 mi", Formatters.distanceText(12345.6))
        assertEquals("0 yd", Formatters.distanceText(-5.0)) // 防御
    }

    @Test
    fun `imperial - altitude in feet`() {
        DisplayUnitProvider.update(DisplayUnits(altitude = AltitudeUnit.FEET))
        assertEquals("4050 ft", Formatters.metersText(1234.6))
        assertEquals("0 ft", Formatters.metersText(null))
    }

    @Test
    fun `imperial - pace per mile and speed in mph`() {
        DisplayUnitProvider.update(DisplayUnits(distance = DistanceUnit.IMPERIAL))
        // 754 s/km × 1.609344 = 1213 s/mi → 20'13"
        assertEquals("20'13\"", Formatters.paceText(754))
        assertEquals("2.6 mph", Formatters.speedText(1.1666667))
    }

    @Test
    fun `paceOrSpeedText - follows the pace display preference`() {
        // 默认 PACE：有配速时优先配速
        assertEquals("12'34\"", Formatters.paceOrSpeedText(754, 1.1666667))
        DisplayUnitProvider.update(DisplayUnits(pace = PaceDisplay.SPEED))
        assertEquals("4.2 km/h", Formatters.paceOrSpeedText(754, 1.1666667))
        // 速度取不到时回落到配速（PRD 6.5 二选一）
        assertEquals("12'34\"", Formatters.paceOrSpeedText(754, null))
    }
}
