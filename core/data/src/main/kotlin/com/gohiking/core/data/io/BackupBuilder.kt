package com.gohiking.core.data.io

import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.security.MessageDigest
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream
import kotlinx.serialization.json.jsonObject

/**
 * ZIP 备份包构建与安全读取（PRD 7.4 / F-IO-60~63）。
 * 读侧四道防线：路径穿越校验（F-IO-60）、总量/条目数上限（F-IO-61）、
 * 流式解压不整包进内存（F-IO-62）、纯数据解析不执行任何内容（F-IO-63）。
 */
object BackupBuilder {

    data class Result(val bytes: ByteArray, val manifest: BackupManifest)

    fun build(
        trips: List<TripEnvelope>,
        routes: List<PlannedRouteEnvelope>,
        settingsJson: String? = null,
        includeChecksums: Boolean = true,
        crs: String = "GCJ-02",
        generator: GeneratorInfo? = null,
        exportedAtMs: Long = System.currentTimeMillis(),
        readme: String = DEFAULT_README,
    ): Result {
        val out = ByteArrayOutputStream()
        val checksums = mutableMapOf<String, String>()
        val digest = if (includeChecksums) MessageDigest.getInstance("SHA-256") else null

        fun putEntry(zip: ZipOutputStream, name: String, data: ByteArray) {
            digest?.let { checksums[name] = it.digest(data).joinToString("") { b -> "%02x".format(b) } }
            zip.putNextEntry(ZipEntry(name))
            zip.write(data)
            zip.closeEntry()
        }

        ZipOutputStream(out).use { zip ->
            // 条目顺序：manifest 最后写（checksums 已收齐）
            settingsJson?.let { putEntry(zip, "settings.json", it.toByteArray(Charsets.UTF_8)) }
            trips.forEach { t -> putEntry(zip, "trips/${t.trip.id}.json", IoCodecs.json.encodeToString(TripEnvelope.serializer(), t).toByteArray(Charsets.UTF_8)) }
            routes.forEach { r -> putEntry(zip, "planned_routes/${r.plannedRoute.id ?: "route"}.json", IoCodecs.json.encodeToString(PlannedRouteEnvelope.serializer(), r).toByteArray(Charsets.UTF_8)) }
            putEntry(zip, "README.txt", readme.toByteArray(Charsets.UTF_8))

            val manifest = BackupManifest(
                exportedAt = IoCodecs.toIso(exportedAtMs),
                generator = generator,
                crs = crs,
                counts = BackupCounts(
                    trips = trips.size,
                    plannedRoutes = routes.size,
                    mediaFiles = 0,
                ),
                options = BackupOptions(
                    containsMedia = false,
                    settingsIncluded = settingsJson != null,
                    checksumsIncluded = includeChecksums,
                ),
                checksumAlgorithm = if (includeChecksums) "SHA-256" else null,
                checksums = checksums,
            )
            putEntry(zip, "manifest.json", IoCodecs.json.encodeToString(BackupManifest.serializer(), manifest).toByteArray(Charsets.UTF_8))
        }
        val manifest = BackupManifest(
            exportedAt = IoCodecs.toIso(exportedAtMs),
            generator = generator,
            crs = crs,
            counts = BackupCounts(trips = trips.size, plannedRoutes = routes.size, mediaFiles = 0),
            options = BackupOptions(containsMedia = false, settingsIncluded = settingsJson != null, checksumsIncluded = includeChecksums),
            checksumAlgorithm = if (includeChecksums) "SHA-256" else null,
            checksums = checksums,
        )
        return Result(out.toByteArray(), manifest)
    }

    /** 包内 README 默认文案（中文兜底；UI 层可传 i18n 版本，PRD 7.4 README 要求） */
    val DEFAULT_README = """
        |GoHiking 备份包说明
        |===================
        |
        |目录结构：
        |- trips/          运动记录，一条记录一个 JSON 文件（schema: gohiking.trip）
        |- planned_routes/ 计划线路，一条一个 JSON（schema: gohiking.planned_route）
        |- settings.json   应用设置备份（导入时默认不生效）
        |- manifest.json   备份元信息与逐文件 SHA-256 校验和
        |
        |坐标系：所有坐标为 GCJ-02（高德坐标系）。GPX 互操作导出恒为 WGS-84，与本包无关。
        |轨迹点编码：trackPoints.fields 数组与 values 每行的列一一对应（紧凑数组编码）。
        |海拔/步数来源枚举：altitudeSource（BAROMETER_FUSED / GPS_ONLY / MANUAL_CALIBRATED）、
        |stepSource（SENSOR_COUNTER / SENSOR_DETECTOR / ACCEL_ALGORITHM / MANUAL / UNAVAILABLE）。
        |
        |本文件为个人运动数据，不含任何账号或设备标识。
        |开源仓库：https://github.com/hxzhang2000/GoHiking
    """.trimIndent()
}

/** 单个 JSON 文件解析（单条 JSON 与 ZIP 内条目共用；PRD 7.5 兼容规则） */
object IoParser {

    fun parse(name: String, bytes: ByteArray): ParsedFile {
        val text = bytes.toString(Charsets.UTF_8)
        return try {
            val obj = IoCodecs.json.parseToJsonElement(text).jsonObject
            val schema = (obj["schema"] as? kotlinx.serialization.json.JsonPrimitive)?.content
            val version = (obj["schemaVersion"] as? kotlinx.serialization.json.JsonPrimitive)?.content ?: "1.0"
            val major = version.substringBefore('.').toIntOrNull()
                ?: return ParsedFile.Invalid(name, "schemaVersion 格式非法: $version")
            if (major > TripEnvelope.SUPPORTED_MAJOR) {
                return ParsedFile.Invalid(name, "文件由更新版本的 GoHiking 导出（schema $version），请升级 App")
            }
            when (schema) {
                TripEnvelope.SCHEMA -> ParsedFile.TripFile(name, IoCodecs.json.decodeFromString(TripEnvelope.serializer(), text))
                PlannedRouteEnvelope.SCHEMA -> ParsedFile.RouteFile(name, IoCodecs.json.decodeFromString(PlannedRouteEnvelope.serializer(), text))
                else -> ParsedFile.Invalid(name, "无法识别的 schema: ${schema ?: "缺失"}，已忽略")
            }
        } catch (t: Throwable) {
            ParsedFile.Invalid(name, "解析失败：${t.message ?: t::class.java.simpleName}")
        }
    }
}

object BackupReader {

    /** F-IO-61：解压后总大小 2 GB / 条目数 10000 上限 */
    const val MAX_TOTAL_BYTES: Long = 2L * 1024 * 1024 * 1024
    const val MAX_ENTRIES: Int = 10_000

    data class Contents(
        val manifest: BackupManifest?,
        val trips: List<ParsedFile.TripFile>,
        val routes: List<ParsedFile.RouteFile>,
        val invalid: List<ParsedFile.Invalid>,
        val settingsJson: String?,
        val mediaFileNames: List<String>,
        val warnings: List<String>,
    )

    /** 读取并解析备份包：ZIP Slip/Bomb 防护 + manifest 校验和核验 + counts 比对（F-IO-25/60/61/62） */
    fun read(input: InputStream): Contents {
        val raw = collectEntries(input)
        val manifest = raw.manifest
        val mismatched = mutableSetOf<String>()
        if (manifest != null && manifest.options.checksumsIncluded &&
            manifest.checksumAlgorithm == "SHA-256" && manifest.checksums.isNotEmpty()
        ) {
            for ((name, expected) in manifest.checksums) {
                val actual = raw.entries[name]?.let { sha256(it) } ?: continue
                if (!actual.equals(expected, ignoreCase = true)) mismatched.add(name)
            }
        }
        val trips = mutableListOf<ParsedFile.TripFile>()
        val routes = mutableListOf<ParsedFile.RouteFile>()
        val invalid = mutableListOf<ParsedFile.Invalid>()
        for ((name, bytes) in raw.entries) {
            if (name in mismatched) {
                invalid.add(ParsedFile.Invalid(name, "SHA-256 校验和不匹配，已跳过该文件"))
                continue
            }
            when (val parsed = IoParser.parse(name, bytes)) {
                is ParsedFile.TripFile -> trips.add(parsed)
                is ParsedFile.RouteFile -> routes.add(parsed)
                is ParsedFile.Invalid -> invalid.add(parsed)
            }
        }
        val warnings = raw.warnings.toMutableList()
        manifest?.let { m ->
            if (m.counts.trips != trips.size || m.counts.plannedRoutes != routes.size) {
                warnings.add("manifest 声明 trips=${m.counts.trips}/plannedRoutes=${m.counts.plannedRoutes} 与实际 ${trips.size}/${routes.size} 不一致（导出可能中断）")
            }
        }
        return Contents(
            manifest = manifest,
            trips = trips,
            routes = routes,
            invalid = invalid + raw.invalid,
            settingsJson = raw.settingsJson,
            mediaFileNames = raw.mediaNames,
            warnings = warnings,
        )
    }

    private class RawEntries(
        val manifest: BackupManifest?,
        val entries: Map<String, ByteArray>,
        val settingsJson: String?,
        val mediaNames: List<String>,
        val invalid: List<ParsedFile.Invalid>,
        val warnings: List<String>,
    )

    private fun collectEntries(input: InputStream): RawEntries {
        var manifest: BackupManifest? = null
        val entries = mutableMapOf<String, ByteArray>()
        val invalid = mutableListOf<ParsedFile.Invalid>()
        val warnings = mutableListOf<String>()
        val mediaNames = mutableListOf<String>()
        var settingsJson: String? = null
        var totalBytes = 0L
        var entryCount = 0

        // Kotlin 2.1 不允许在 inline lambda（use）里 break/continue → 手动管理关闭
        val zip = ZipInputStream(input.buffered())
        try {
            while (true) {
                val entry = zip.nextEntry ?: break
                entryCount++
                if (entryCount > MAX_ENTRIES) {
                    warnings.add("条目数超过上限 $MAX_ENTRIES（疑似 Zip Bomb），已中止读取（F-IO-61）")
                    break
                }
                val name = entry.name
                val pathError = validatePath(name)
                if (pathError != null) {
                    invalid.add(ParsedFile.Invalid(name, "拒绝危险路径条目：$pathError（F-IO-60）"))
                    zip.closeEntry()
                    continue
                }
                if (entry.isDirectory) {
                    zip.closeEntry()
                    continue
                }
                val buf = ByteArrayOutputStream()
                val chunk = ByteArray(64 * 1024)
                var overflow = false
                while (true) {
                    val n = zip.read(chunk)
                    if (n < 0) break
                    totalBytes += n
                    if (totalBytes > MAX_TOTAL_BYTES) {
                        overflow = true
                        break
                    }
                    buf.write(chunk, 0, n)
                }
                if (overflow) {
                    warnings.add("解压后总大小超过上限 2 GB（疑似 Zip Bomb），已中止读取（F-IO-61）")
                    break
                }
                val bytes = buf.toByteArray()
                when {
                    name == "manifest.json" -> try {
                        manifest = IoCodecs.json.decodeFromString(BackupManifest.serializer(), bytes.toString(Charsets.UTF_8))
                    } catch (t: Throwable) {
                        warnings.add("manifest.json 解析失败（${t.message}），按目录结构推断导入")
                    }
                    name == "settings.json" -> settingsJson = bytes.toString(Charsets.UTF_8)
                    name.startsWith("media/") -> mediaNames.add(name.removePrefix("media/"))
                    name.endsWith(".json") -> entries[name] = bytes
                    // 其余忽略
                }
                zip.closeEntry()
            }
        } finally {
            zip.close()
        }
        return RawEntries(manifest, entries, settingsJson, mediaNames, invalid, warnings)
    }

    private fun sha256(bytes: ByteArray): String =
        MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }

    /** F-IO-60：拒绝绝对路径、盘符、反斜杠与 `..` 穿越 */
    internal fun validatePath(name: String): String? = when {
        name.isBlank() -> "空条目名"
        name.startsWith('/') -> "绝对路径"
        name.startsWith("\\\\") -> "UNC 路径"
        name.contains(':') -> "盘符/冒号路径"
        name.split('/', '\\').any { it == ".." } -> "包含 .. 路径穿越"
        else -> null
    }
}
