package com.gohiking.core.datastore

import org.junit.Assert.assertEquals
import org.junit.Test

/** 阈值合法范围校验（DEV §3.5：F-ALERT-12/22、F-SET-05；F-REC-63 无气压计下限） */
class AppSettingsValidateTest {

    @Test
    fun distanceIntervalClampedAndStepped() {
        val v = AppSettings(alertDistanceIntervalM = 333).validate(hasBarometer = true)
        assertEquals(300, v.alertDistanceIntervalM) // 对齐 50 步进
        val lo = AppSettings(alertDistanceIntervalM = 10).validate(hasBarometer = true)
        assertEquals(50, lo.alertDistanceIntervalM)
        val hi = AppSettings(alertDistanceIntervalM = 9999).validate(hasBarometer = true)
        assertEquals(5000, hi.alertDistanceIntervalM)
    }

    @Test
    fun altitudeIntervalBarometerClamp() {
        // 有气压计：下限 10
        assertEquals(10, AppSettings(alertAltitudeIntervalM = 5).validate(hasBarometer = true).alertAltitudeIntervalM)
        // 无气压计：下限提到 30（F-REC-63）
        assertEquals(30, AppSettings(alertAltitudeIntervalM = 5).validate(hasBarometer = false).alertAltitudeIntervalM)
        assertEquals(30, AppSettings(alertAltitudeIntervalM = 20).validate(hasBarometer = false).alertAltitudeIntervalM)
        // 步进 10
        assertEquals(340, AppSettings(alertAltitudeIntervalM = 345).validate(hasBarometer = true).alertAltitudeIntervalM)
        assertEquals(1000, AppSettings(alertAltitudeIntervalM = 5000).validate(hasBarometer = true).alertAltitudeIntervalM)
    }

    @Test
    fun bodyWeightClamped() {
        assertEquals(30, AppSettings(bodyWeightKg = 1).validate(hasBarometer = true).bodyWeightKg)
        assertEquals(200, AppSettings(bodyWeightKg = 500).validate(hasBarometer = true).bodyWeightKg)
        assertEquals(72, AppSettings(bodyWeightKg = 72).validate(hasBarometer = true).bodyWeightKg)
    }
}
