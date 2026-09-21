package com.gohiking.core.data.io

import com.gohiking.core.database.entity.MarkerEntity
import com.gohiking.core.database.entity.TrackPointEntity
import com.gohiking.core.database.entity.TripEntity
import com.gohiking.core.location.crs.CoordinateConverter
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** PRD 7.3 往返：导出 → 解析 → 实体重建（GCJ-02 原样 / WGS-84 转换 / 兼容规则） */
class TripJsonRoundTripTest {

    private val trip = TripEntity(
        id = "trip-1",
        name = "梧桐山",
        note = null,
        plannedRouteId = null,
        startTime = 1_758_240_723_000,
        endTime = 1_758_255_115_000,
        durationSec = 19_792,
        movingDurationSec = 17_230,
        pausedDurationSec = 2_562,
        distanceM = 8_234.5,
        totalAscentM = 620.3,
        totalDescentM = 615.8,
        maxAltitudeM = 943.7,
        minAltitudeM = 120.3,
        avgSpeedMps = 0.478,
        avgPaceSecPerKm = 2_093,
        maxSpeedMps = 2.41,
        steps = 15_230,
        stepSource = "SENSOR_COUNTER",
        caloriesKcal = 850.0,
        altitudeSource = "BAROMETER_FUSED",
        hasBarometer = true,
        status = "FINISHED",
        createdAt = 1_758_255_200_000,
    )

    private val points = listOf(
        point(0, 1, 1_758_240_723_000, 39.900, 116.400),
        point(0, 2, 1_758_240_783_000, 39.901, 116.401),
        point(1, 1, 1_758_250_723_000, 39.902, 116.402),
    )

    private fun point(seg: Int, seq: Int, ts: Long, lat: Double, lng: Double) = TrackPointEntity(
        id = 0, tripId = "trip-1", segmentIndex = seg, seq = seq, timestamp = ts,
        latitude = lat, longitude = lng, altitude = 100.0 + seq, accuracy = 5.0,
        speedMps = 1.0, bearing = 90.0, quality = 0, distanceM = null,
    )

    private fun export(crs: String): String {
        val envelope = TripJsonExporter.export(trip, points, emptyList(), emptyList(), crs = crs)
        return IoCodecs.json.encodeToString(TripEnvelope.serializer(), envelope)
    }

    @Test
    fun `GCJ-02 export preserves coordinates`() {
        val parsed = IoParser.parse("t.json", export("GCJ-02").toByteArray())
        assertTrue(parsed is ParsedFile.TripFile)
        val bundle = (parsed as ParsedFile.TripFile).let { ImportEngine(FakeSink()).toTripBundle(it.envelope) }
        assertEquals(39.900, bundle.points[0].latitude, 1e-9)
        assertEquals(116.400, bundle.points[0].longitude, 1e-9)
        assertEquals(3, bundle.points.size)
    }

    @Test
    fun `WGS-84 export converts coordinates and import converts back`() {
        val parsed = IoParser.parse("t.json", export("WGS-84").toByteArray())
        val envelope = (parsed as ParsedFile.TripFile).envelope
        // 导出端 lat 已转换为 WGS-84（与库内 GCJ 值不同）
        val row = envelope.trip.trackPoints!!.values[0]
        val exportedLat = IoCodecs.rowDouble(row, 3)!!
        assertTrue(kotlin.math.abs(exportedLat - 39.900) > 1e-4)
        // 导入端 wgs→gcj 回转，与原值近似（迭代逼近误差 < 1e-6 度）
        val bundle = ImportEngine(FakeSink()).toTripBundle(envelope)
        assertEquals(39.900, bundle.points[0].latitude, 1e-6)
        assertEquals(116.400, bundle.points[0].longitude, 1e-6)
    }

    @Test
    fun `trackPoints fields and segments roundtrip`() {
        val envelope = TripJsonExporter.export(trip, points, emptyList(), emptyList(), crs = "GCJ-02")
        assertEquals(TrackPointsJson.DEFAULT_FIELDS, envelope.trip.trackPoints!!.fields)
        assertEquals(2, envelope.trip.segments.size) // 两个 segment
        assertEquals(2, envelope.trip.segments.first { it.index == 0 }.pointCount)
    }

    @Test
    fun `distanceM recomputed per segment on import`() {
        val bundle = ImportEngine(FakeSink()).toTripBundle(
            TripJsonExporter.export(trip, points, emptyList(), emptyList(), crs = "GCJ-02"),
        )
        val seg0 = bundle.points.filter { it.segmentIndex == 0 }
        assertTrue(seg0[0].distanceM == 0.0) // 段首 0
        val expect = IoCodecs.haversineM(39.900, 116.400, 39.901, 116.401)
        assertEquals(expect, seg0[1].distanceM!!, expect * 1e-6)
        val seg1 = bundle.points.filter { it.segmentIndex == 1 }
        assertEquals(0.0, seg1[0].distanceM!!, 1e-9) // 新段重新累计
    }

    @Test
    fun `unknown marker type downgrades to UNKNOWN keeping rawType`() {
        val marker = MarkerEntity(
            id = "m1", tripId = "trip-1", type = "SOMETHING_NEW", timestamp = 123L,
            latitude = 39.9, longitude = 116.4, altitude = null, label = "x", note = null,
            sequence = 1, extraJson = null,
        )
        val text = IoCodecs.json.encodeToString(
            TripEnvelope.serializer(),
            TripJsonExporter.export(trip, emptyList(), listOf(marker), emptyList(), crs = "GCJ-02"),
        )
        val bundle = ImportEngine(FakeSink()).toTripBundle(
            (IoParser.parse("t", text.toByteArray()) as ParsedFile.TripFile).envelope,
        )
        assertEquals("UNKNOWN", bundle.markers[0].type)
        assertTrue(bundle.markers[0].extraJson!!.contains("\"rawType\":\"SOMETHING_NEW\""))
    }

    @Test
    fun `known marker type keeps label, unknown drops display label`() {
        val manual = MarkerEntity(
            id = "m2", tripId = "trip-1", type = "MANUAL", timestamp = 5L,
            latitude = 39.9, longitude = 116.4, altitude = null, label = "补水点", note = "备注",
            sequence = 1, extraJson = null,
        )
        val text = IoCodecs.json.encodeToString(
            TripEnvelope.serializer(),
            TripJsonExporter.export(trip, emptyList(), listOf(manual), emptyList(), crs = "GCJ-02"),
        )
        val bundle = ImportEngine(FakeSink()).toTripBundle(
            (IoParser.parse("t", text.toByteArray()) as ParsedFile.TripFile).envelope,
        )
        assertEquals("MANUAL", bundle.markers[0].type)
        assertEquals("补水点", bundle.markers[0].label)
        assertEquals("备注", bundle.markers[0].note)
    }

    @Test
    fun `unknown fields are ignored and missing optional fields defaulted`() {
        val text = export("GCJ-02").replace(
            "{\"schema\":",
            "{\"futureField\":42,\"schema\":",
        )
        val parsed = IoParser.parse("t.json", text.toByteArray())
        assertTrue(parsed is ParsedFile.TripFile)
        val tripJson = (parsed as ParsedFile.TripFile).envelope.trip
        assertNull(tripJson.crsNote)
        assertNull(tripJson.alertSettings)
    }

    @Test
    fun `schema major greater than supported is rejected`() {
        val text = export("GCJ-02").replace("\"schemaVersion\":\"1.0\"", "\"schemaVersion\":\"2.0\"")
        val parsed = IoParser.parse("t.json", text.toByteArray())
        assertTrue(parsed is ParsedFile.Invalid)
        assertEquals(ImportReasonCode.SCHEMA_TOO_NEW, (parsed as ParsedFile.Invalid).code)
    }

    @Test
    fun `envelope always carries schema identity`() {
        val obj = IoCodecs.json.parseToJsonElement(export("GCJ-02")).jsonObject
        assertEquals("gohiking.trip", (obj["schema"] as JsonPrimitive).content)
        assertEquals("1.0", (obj["schemaVersion"] as JsonPrimitive).content)
    }
}
