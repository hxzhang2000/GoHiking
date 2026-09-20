package com.gohiking.core.data.io

import com.gohiking.core.database.entity.MarkerEntity
import com.gohiking.core.database.entity.TrackPointEntity
import com.gohiking.core.database.entity.TripEntity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** GPX 1.1 导出规则（PRD 7.2.1，F-IO-14/15） */
class GpxExporterTest {

    private val trip = TripEntity(
        id = "t1", name = "Before & After <peak>", note = null, plannedRouteId = null,
        startTime = 1_758_240_723_000, endTime = 1_758_255_115_000,
        durationSec = 0, movingDurationSec = 0, pausedDurationSec = 0,
        distanceM = 0.0, totalAscentM = 0.0, totalDescentM = 0.0,
        maxAltitudeM = null, minAltitudeM = null, avgSpeedMps = null,
        avgPaceSecPerKm = null, maxSpeedMps = null, steps = -1,
        stepSource = "UNAVAILABLE", caloriesKcal = null,
        altitudeSource = "GPS_ONLY", hasBarometer = false, status = "FINISHED",
        createdAt = 0,
    )

    private fun pt(seg: Int, seq: Int, ts: Long, lat: Double, lng: Double) = TrackPointEntity(
        id = 0, tripId = "t1", segmentIndex = seg, seq = seq, timestamp = ts,
        latitude = lat, longitude = lng, altitude = 120.5, accuracy = null,
        speedMps = null, bearing = null, quality = 0, distanceM = null,
    )

    private val marker = MarkerEntity(
        id = "m1", tripId = "t1", type = "ALERT_ASCENT", timestamp = 1_758_250_000_000,
        latitude = 39.900, longitude = 116.400, altitude = 300.0, label = "↑300m",
        note = "a & b", sequence = 1, extraJson = null,
    )

    @Test
    fun `one trkseg per segment, never merged`() {
        val gpx = GpxExporter.export(
            trip,
            listOf(pt(0, 1, 1L, 39.9, 116.4), pt(0, 2, 2L, 39.91, 116.41), pt(1, 1, 3L, 39.92, 116.42)),
            emptyList(),
        ) { m -> m.label ?: "" }
        assertEquals(2, Regex("<trkseg>").findAll(gpx).count())
    }

    @Test
    fun `coordinates are WGS-84 regardless of export setting`() {
        val gpx = GpxExporter.export(trip, listOf(pt(0, 1, 1L, 39.9, 116.4)), emptyList()) { "" }
        // 北京区域内 GCJ-02 偏移为非线性但必然非零（几十~几百米 → >1e-4 度）
        assertTrue(gpx.contains("lat=\"39.9") || gpx.contains("lat=\"39.8") || gpx.contains("lat=\"40.0"))
        // 转换后不应与输入完全一致
        assertFalse(gpx.contains("lat=\"39.9\" lon=\"116.4\""))
    }

    @Test
    fun `marker becomes wpt with raw type enum and escaped desc`() {
        val gpx = GpxExporter.export(trip, emptyList(), listOf(marker)) { m -> m.label ?: "" }
        assertTrue(gpx.contains("<wpt "))
        assertTrue(gpx.contains("<type>ALERT_ASCENT</type>")) // 枚举原文不翻译（PRD 9.7.4）
        assertTrue(gpx.contains("<name>↑300m</name>"))
        assertTrue(gpx.contains("<desc>a &amp; b</desc>"))
    }

    @Test
    fun `trip name and attributes are escaped`() {
        val gpx = GpxExporter.export(trip, emptyList(), emptyList()) { "" }
        assertTrue(gpx.contains("<name>Before &amp; After &lt;peak&gt;</name>"))
        assertTrue(gpx.contains("xmlns=\"http://www.topografix.com/GPX/1/1\""))
        assertFalse(gpx.contains("<extensions>")) // F-IO-15：不写自定义扩展
    }

    @Test
    fun `gpx time is UTC format`() {
        val gpx = GpxExporter.export(trip, listOf(pt(0, 1, 1_758_240_723_000L, 39.9, 116.4)), emptyList()) { "" }
        Regex("<time>(\\d{4}-\\d{2}-\\d{2}T\\d{2}:\\d{2}:\\d{2}Z)</time>").findAll(gpx).count().let {
            assertTrue(it >= 2) // metadata.time + trkpt.time
        }
    }
}
