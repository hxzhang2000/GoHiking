package com.gohiking.core.data.io

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** ZIP 安全与完整性（F-IO-60/61/62/63 + manifest 校验） */
class BackupSecurityTest {

    private fun sampleTripEnvelope(id: String): TripEnvelope = TripJsonExporter.export(
        FakeSink.makeTrip(id).trip, emptyList(), emptyList(), emptyList(), crs = "GCJ-02",
    )

    @Test
    fun `build then read roundtrip with checksums and counts`() {
        val built = BackupBuilder.build(
            trips = listOf(sampleTripEnvelope("a"), sampleTripEnvelope("b")),
            routes = emptyList(),
            settingsJson = "{\"app_language\":\"zh-CN\"}",
            generator = GeneratorInfo(versionName = "0.3.0", versionCode = 1),
        )
        val contents = BackupReader.read(ByteArrayInputStream(built.bytes))
        assertEquals(2, contents.trips.size)
        assertNotNull(contents.manifest)
        assertEquals(2, contents.manifest!!.counts.trips)
        assertEquals("{\"app_language\":\"zh-CN\"}", contents.settingsJson)
        assertTrue(contents.warnings.isEmpty())
    }

    @Test
    fun `checksum mismatch skips the file into failures`() {
        val built = BackupBuilder.build(trips = listOf(sampleTripEnvelope("a")), routes = emptyList())
        // 篡改 manifest 里 a 的校验和
        val tamperedManifest = built.manifest.copy(
            checksums = built.manifest.checksums.mapValues { (k, v) -> if (k.startsWith("trips/")) "deadbeef" else v },
        )
        // 重建 zip：用篡改后的 manifest 替换
        val out = ByteArrayOutputStream()
        ZipOutputStream(out.buffered()).use { zip ->
            val src = BackupReader.read(ByteArrayInputStream(built.bytes))
            // 重新读取原始条目不可行（read 已解析），直接从原 zip 重建：解出原始 bytes
            val zin = java.util.zip.ZipInputStream(ByteArrayInputStream(built.bytes).buffered())
            while (true) {
                val e = zin.nextEntry ?: break
                val bytes = zin.readBytes()
                if (e.name == "manifest.json") {
                    zip.putNextEntry(ZipEntry(e.name))
                    zip.write(IoCodecs.json.encodeToString(BackupManifest.serializer(), tamperedManifest).toByteArray())
                } else {
                    zip.putNextEntry(ZipEntry(e.name))
                    zip.write(bytes)
                }
                zip.closeEntry()
                zin.closeEntry()
            }
        }
        val contents = BackupReader.read(ByteArrayInputStream(out.toByteArray()))
        assertEquals(0, contents.trips.size)
        assertEquals(1, contents.invalid.size)
        // H-09：core 层只产出原因码，文案由 UI 层本地化
        assertEquals(ImportReasonCode.CHECKSUM_MISMATCH, contents.invalid[0].code)
    }

    @Test
    fun `zip slip path traversal entries are rejected without extraction`() {
        val out = ByteArrayOutputStream()
        ZipOutputStream(out.buffered()).use { zip ->
            listOf("../evil.txt", "/abs/path.json", "trips\\..\\..\\x.json", "ok/trips/a.json").forEach { name ->
                zip.putNextEntry(ZipEntry(name))
                zip.write("{\"schema\":\"gohiking.trip\"}".toByteArray())
                zip.closeEntry()
            }
        }
        val contents = BackupReader.read(ByteArrayInputStream(out.toByteArray()))
        val rejected = contents.invalid.filter { it.code == ImportReasonCode.UNSAFE_ENTRY } // F-IO-60
        assertEquals(3, rejected.size) // 绝对路径、.. 穿越、反斜杠穿越全部拒绝
    }

    @Test
    fun `entry count above limit aborts as suspected zip bomb`() {
        val out = ByteArrayOutputStream()
        ZipOutputStream(out.buffered()).use { zip ->
            repeat(BackupReader.MAX_ENTRIES + 1) { i ->
                zip.putNextEntry(ZipEntry("trips/filler_$i.json"))
                zip.write(ByteArray(8))
                zip.closeEntry()
            }
        }
        val contents = BackupReader.read(ByteArrayInputStream(out.toByteArray()))
        assertTrue(contents.warnings.any { it.code == ImportWarningCode.ENTRY_LIMIT })
    }

    @Test
    fun `non-gohiking json files are ignored`() {
        val out = ByteArrayOutputStream()
        ZipOutputStream(out.buffered()).use { zip ->
            zip.putNextEntry(ZipEntry("trips/real.json"))
            zip.write(IoCodecs.json.encodeToString(TripEnvelope.serializer(), sampleTripEnvelope("a")).toByteArray())
            zip.closeEntry()
            zip.putNextEntry(ZipEntry("notes/whatever.json"))
            zip.write("{\"hello\":1}".toByteArray())
            zip.closeEntry()
        }
        val contents = BackupReader.read(ByteArrayInputStream(out.toByteArray()))
        assertEquals(1, contents.trips.size)
        // L-08：原来这条只断言 trips.size，而 notes/whatever.json 其实进了「失败」列表
        // （测试名写着 ignored，却从未验证过）。补上断言，锁住行为。
        assertTrue(
            "非数据目录的 JSON 不应进入失败列表，实际：${contents.invalid.map { it.name }}",
            contents.invalid.isEmpty(),
        )
    }

    @Test
    fun `returned manifest equals the manifest written inside the package`() {
        val built = BackupBuilder.build(
            trips = listOf(sampleTripEnvelope("a")),
            routes = emptyList(),
            settingsJson = "{\"app_language\":\"zh-CN\"}",
        )
        // 直接从包里把 manifest.json 读出来，与返回值逐项比对（L-10 的重放场景）
        var inPackage: BackupManifest? = null
        java.util.zip.ZipInputStream(ByteArrayInputStream(built.bytes)).use { zip ->
            while (true) {
                val e = zip.nextEntry ?: break
                if (e.name == "manifest.json") {
                    inPackage = IoCodecs.json.decodeFromString(
                        BackupManifest.serializer(),
                        zip.readBytes().toString(Charsets.UTF_8),
                    )
                    break
                }
            }
        }
        val expected = inPackage
        assertNotNull("包内应有 manifest.json", expected)
        assertEquals(expected!!.checksums, built.manifest.checksums)
        assertEquals(expected.exportedAt, built.manifest.exportedAt)
    }

    @Test
    fun `checksum declared but entry missing is reported as unverified`() {
        val built = BackupBuilder.build(trips = listOf(sampleTripEnvelope("a")), routes = emptyList())
        // 把 README.txt 从包里删掉，但 manifest 仍然为它登记了校验和
        val stripped = ByteArrayOutputStream()
        java.util.zip.ZipInputStream(ByteArrayInputStream(built.bytes)).use { zip ->
            ZipOutputStream(stripped).use { out ->
                while (true) {
                    val e = zip.nextEntry ?: break
                    if (e.name == "README.txt") continue
                    out.putNextEntry(ZipEntry(e.name))
                    out.write(zip.readBytes())
                    out.closeEntry()
                }
            }
        }
        val contents = BackupReader.read(ByteArrayInputStream(stripped.toByteArray()))
        assertEquals(1, contents.trips.size) // 数据本身照常导入
        assertTrue(
            "应显式提示 README.txt 未核验，实际 warnings=${contents.warnings.map { it.code }}",
            contents.warnings.any {
                it.code == ImportWarningCode.CHECKSUM_UNVERIFIED &&
                    it.args.firstOrNull()?.contains("README.txt") == true
            },
        )
    }

    @Test
    fun `counts mismatch yields warning but still imports actual content`() {
        val built = BackupBuilder.build(trips = listOf(sampleTripEnvelope("a")), routes = emptyList())
        val tampered = built.manifest.copy(counts = built.manifest.counts.copy(trips = 42))
        val out = ByteArrayOutputStream()
        ZipOutputStream(out.buffered()).use { zip ->
            val zin = java.util.zip.ZipInputStream(ByteArrayInputStream(built.bytes).buffered())
            while (true) {
                val e = zin.nextEntry ?: break
                val bytes = zin.readBytes()
                zip.putNextEntry(ZipEntry(e.name))
                if (e.name == "manifest.json") {
                    zip.write(IoCodecs.json.encodeToString(BackupManifest.serializer(), tampered).toByteArray())
                } else {
                    zip.write(bytes)
                }
                zip.closeEntry()
                zin.closeEntry()
            }
        }
        val contents = BackupReader.read(ByteArrayInputStream(out.toByteArray()))
        assertEquals(1, contents.trips.size)
        assertTrue(contents.warnings.any { it.code == ImportWarningCode.MANIFEST_COUNT_MISMATCH })
    }

    @Test
    fun `settings json survives roundtrip and media names registered`() {
        val out = ByteArrayOutputStream()
        ZipOutputStream(out.buffered()).use { zip ->
            zip.putNextEntry(ZipEntry("settings.json"))
            zip.write("{\"alert_master_enabled\":true}".toByteArray())
            zip.closeEntry()
            zip.putNextEntry(ZipEntry("media/IMG_1.jpg"))
            zip.write(ByteArray(4))
            zip.closeEntry()
        }
        val contents = BackupReader.read(ByteArrayInputStream(out.toByteArray()))
        assertEquals(listOf("IMG_1.jpg"), contents.mediaFileNames) // F-IO-33 P1：登记不还原
        assertNotNull(contents.settingsJson)
    }
}
