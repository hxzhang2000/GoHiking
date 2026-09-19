package com.gohiking.core.location.sampler

import com.gohiking.core.location.LocationFix
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

class TrackSamplerTest {

    private fun fix(
        lat: Double,
        lng: Double,
        accuracy: Float = 5f,
        speed: Float = 0f,
        ts: Long = 0,
    ) = LocationFix(
        lat = lat, lng = lng, altitudeM = null, verticalAccuracyM = null,
        mslAltitudeM = null, accuracyM = accuracy, speedMps = speed,
        bearing = 0f, timestampMs = ts,
    )

    @Test
    fun `静止抖动_位移小于3米不入库`() {
        val sampler = TrackSampler()
        assertNotNull(sampler.accept(fix(40.0, 116.0)))
        // ~1m 位移
        assertNull(sampler.accept(fix(40.000009, 116.0)))
        assertNull(sampler.accept(fix(40.0, 116.000009)))
    }

    @Test
    fun `位移达标才入库`() {
        val sampler = TrackSampler()
        sampler.accept(fix(40.0, 116.0))
        // ~55m 位移
        val sample = sampler.accept(fix(40.0005, 116.0))
        assertNotNull(sample)
        assertEquals(0, sample!!.quality)
    }

    @Test
    fun `精度差标记低质量`() {
        val sampler = TrackSampler()
        sampler.accept(fix(40.0, 116.0))
        val sample = sampler.accept(fix(40.001, 116.0, accuracy = 80f))
        assertEquals(1, sample!!.quality)
    }

    @Test
    fun `超速标记异常`() {
        val sampler = TrackSampler()
        sampler.accept(fix(40.0, 116.0, ts = 0))
        // 500m / 10s = 50 m/s > 30km/h，且 SDK speed 缺失走差分
        val sample = sampler.accept(fix(40.0045, 116.0, ts = 10_000))
        assertEquals(2, sample!!.quality)
    }

    @Test
    fun `SDK速度优先于差分`() {
        val sampler = TrackSampler()
        sampler.accept(fix(40.0, 116.0, ts = 0))
        // 差分会得出高速，但 SDK speed=1.5 → quality 正常
        val sample = sampler.accept(fix(40.0045, 116.0, speed = 1.5f, ts = 10_000))
        assertEquals(0, sample!!.quality)
        assertEquals(1.5f, sample.fix.speedMps, 1e-6f)
    }

    @Test
    fun `reset后重新建立位移基准`() {
        val sampler = TrackSampler()
        sampler.accept(fix(40.0, 116.0))
        sampler.reset()
        // reset 后下一点无条件入库（重建基准）
        val sample = sampler.accept(fix(40.0, 116.0))
        assertNotNull(sample)
    }
}
