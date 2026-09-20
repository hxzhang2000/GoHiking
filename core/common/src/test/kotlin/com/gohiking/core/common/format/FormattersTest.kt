package com.gohiking.core.common.format

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class FormattersTest {

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
}
