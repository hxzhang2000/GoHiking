package com.gohiking.core.location.altitude

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * `FakeAltitudeFuser` 自身的行为契约（文档审阅 D10）。
 * 替身不可信 → 用它写的用例全不可信，所以它自己必须有断言。
 */
class FakeAltitudeFuserTest {

    @Test
    fun `replay mode consumes one altitude per gps fix`() {
        val fake = FakeAltitudeFuser(replay = listOf(100.0, 102.0, 500.0, null))
        assertNull(fake.current())

        fake.onGpsFix(1.0, 5.0) // 值被回放序列覆盖（漂移/跳变场景）
        assertEquals(100.0, fake.current()!!, 0.0)
        fake.onGpsFix(1.0, 5.0)
        assertEquals(102.0, fake.current()!!, 0.0)
        fake.onGpsFix(1.0, 5.0) // 跳变
        assertEquals(500.0, fake.current()!!, 0.0)
        fake.onGpsFix(1.0, 5.0) // 不可用
        assertNull(fake.current())

        // 序列用尽 → 保持最后一个值（null，不抛异常）
        fake.onGpsFix(1.0, 5.0)
        assertNull(fake.current())
        assertEquals(5, fake.gpsFixes.size) // 5 次 fix 全部记录在案（序列用尽也算一次）
    }

    @Test
    fun `passthrough mode echoes msl first then gps`() {
        val fake = FakeAltitudeFuser()
        fake.onGpsFix(gpsAltM = 100.0, verticalAccuracyM = 5.0, mslAltM = 98.0)
        assertEquals(98.0, fake.current()!!, 0.0) // MSL 优先（同真实实现）
        fake.onGpsFix(gpsAltM = 200.0, verticalAccuracyM = 5.0)
        assertEquals(200.0, fake.current()!!, 0.0)
        fake.onGpsFix(null, null) // 全空输入：不改动当前值（真实实现同样直接 return）
        assertEquals(200.0, fake.current()!!, 0.0)
    }

    @Test
    fun `manual calibration records target and switches source`() {
        val fake = FakeAltitudeFuser(replay = listOf(100.0))
        fake.onGpsFix(100.0, 5.0)
        fake.calibrateTo(1532.7)
        assertEquals(listOf(1532.7), fake.calibrations)
        assertEquals(1532.7, fake.current()!!, 0.0)
        assertEquals(AltitudeFuser.Source.MANUAL_CALIBRATED, fake.source)
    }

    @Test
    fun `factory creates a fresh instance per session`() {
        val factory = FakeAltitudeFuserFactory(replay = listOf(100.0))
        val first = factory.create(hasBarometer = false)
        val second = factory.create(hasBarometer = true)
        assertTrue(first !== second) // H-3：逐场状态绝不共用实例
        assertEquals(2, factory.created.size)
        assertEquals(AltitudeFuser.Source.GPS_ONLY, first.source)
        assertEquals(AltitudeFuser.Source.BAROMETER_FUSED, second.source)
    }

    @Test
    fun `restore rewinds baseline and is observable`() {
        val fake = FakeAltitudeFuser()
        fake.restoreRef(1234.0, 980.0)
        assertEquals(1234.0 to 980.0, fake.restoredRef)
        assertEquals(1234.0, fake.current()!!, 0.0)
        assertEquals(0, fake.pauseCount)
        fake.onSessionPause()
        assertEquals(1, fake.pauseCount)
    }
}
