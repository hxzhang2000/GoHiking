package com.gohiking.core.data.io

import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** 冲突策略 / 幂等 / 故障隔离（F-IO-27/28/31/40~44） */
class ImportEngineTest {

    private val engine = ImportEngine(FakeSink())

    private fun tripFile(id: String = "t1", name: String = "测试山"): ParsedFile.TripFile {
        val envelope = TripJsonExporter.export(
            FakeSink.makeTrip(id, name).trip, emptyList(), emptyList(), emptyList(), crs = "GCJ-02",
        )
        return ParsedFile.TripFile("GoHiking_$name.json", envelope)
    }

    @Test
    fun `default skip policy keeps existing data and is idempotent`() = runTest {
        val sink = FakeSink().also { it.seedTrip(FakeSink.makeTrip("t1")) }
        val eng = ImportEngine(sink)
        val report = eng.execute(listOf(tripFile()), policy = ConflictPolicy.SKIP)
        assertEquals(0, report.imported)
        assertEquals(1, report.skipped)
        assertEquals(1, sink.trips.size) // F-IO-31 幂等：无重复数据
    }

    @Test
    fun `overwrite replaces existing trip`() = runTest {
        val sink = FakeSink().also { it.seedTrip(FakeSink.makeTrip("t1")) }
        val eng = ImportEngine(sink)
        val report = eng.execute(listOf(tripFile()), policy = ConflictPolicy.OVERWRITE)
        assertEquals(1, report.imported)
        assertEquals(listOf("t1"), sink.overwrites)
        assertEquals(1, sink.trips.size)
    }

    @Test
    fun `duplicate policy creates new id keeping both`() = runTest {
        val sink = FakeSink().also { it.seedTrip(FakeSink.makeTrip("t1")) }
        val eng = ImportEngine(sink)
        val report = eng.execute(listOf(tripFile()), policy = ConflictPolicy.DUPLICATE)
        assertEquals(1, report.imported)
        assertEquals(2, sink.trips.size)
        assertTrue(sink.trips.keys.contains("t1"))
        assertTrue(sink.trips.keys.any { it != "t1" })
    }

    @Test
    fun `ask policy defers per-file decisions`() = runTest {
        val sink = FakeSink()
        val eng = ImportEngine(sink)
        val report = eng.execute(
            listOf(tripFile()),
            policy = ConflictPolicy.ASK,
            decisions = mapOf("GoHiking_测试山.json" to ConflictPolicy.OVERWRITE),
        )
        // 逐文件决策命中 OVERWRITE（文件存在与否不影响 OVERWRITE 分支的 putTrip）
        assertEquals(1, report.imported)
    }

    @Test
    fun `invalid file counts as failure with reason, others unaffected`() = runTest {
        val sink = FakeSink()
        val eng = ImportEngine(sink)
        val report = eng.execute(
            listOf(
                ParsedFile.Invalid("bad.json", "解析失败：schema 缺失"),
                tripFile(id = "t2"),
            ),
        )
        assertEquals(1, report.imported)
        assertEquals(1, report.failed)
        assertEquals(0, report.skipped)
        assertTrue(report.items.first { it.name == "bad.json" }.reason!!.contains("schema"))
        assertEquals(1, sink.trips.size)
    }

    @Test
    fun `suspected duplicate name and startTime is warned not merged`() = runTest {
        val sink = FakeSink().also { it.seedTrip(FakeSink.makeTrip("t1", name = "同名", startMs = 1_000L)) }
        val eng = ImportEngine(sink)
        val file = ParsedFile.TripFile(
            "other.json",
            TripJsonExporter.export(FakeSink.makeTrip("t-other", name = "同名", startMs = 1_000L).trip, emptyList(), emptyList(), emptyList(), crs = "GCJ-02"),
        )
        val preview = eng.preview(listOf(file))
        assertTrue(preview.suspectedDupTrips.contains("t-other")) // F-IO-41
        val report = eng.execute(listOf(file))
        assertEquals(1, report.imported) // 不自动合并，照常导入
        assertTrue(report.warnings.any { it.contains("疑似重复") })
    }

    @Test
    fun `missing crs warns and treats as WGS-84`() = runTest {
        val sink = FakeSink()
        val eng = ImportEngine(sink)
        val envelope = TripJsonExporter.export(FakeSink.makeTrip("t1").trip, emptyList(), emptyList(), emptyList(), crs = "GCJ-02")
        val stripped = envelope.copy(trip = envelope.trip.copy(crs = null))
        val preview = eng.preview(listOf(ParsedFile.TripFile("a.json", stripped)))
        assertTrue(preview.crsValues.contains("WGS-84")) // PRD 7.5：缺失按 WGS-84
        val report = eng.execute(listOf(ParsedFile.TripFile("a.json", stripped)))
        assertTrue(report.warnings.any { it.contains("WGS-84") })
    }

    @Test
    fun `preview reports conflicts and time range`() = runTest {
        val sink = FakeSink().also { it.seedTrip(FakeSink.makeTrip("t1")) }
        val eng = ImportEngine(sink)
        val preview = eng.preview(listOf(tripFile(), tripFile("t2", "另一座山")))
        assertEquals(2, preview.tripCount)
        assertEquals(setOf("t1"), preview.conflictTrips)
        assertTrue(preview.earliestStartMs != null && preview.latestStartMs != null)
    }
}
