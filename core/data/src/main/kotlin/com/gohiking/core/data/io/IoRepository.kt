package com.gohiking.core.data.io

import com.gohiking.core.data.repository.TripRepository
import com.gohiking.core.data.stats.GainSplit
import com.gohiking.core.data.stats.KmSplit
import com.gohiking.core.database.GhDatabase
import com.gohiking.core.datastore.SettingsRepository
import java.io.InputStream
import java.io.OutputStream
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.first
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * IO 门面（M3-D2）：组装导出数据 / 解析导入文件 / 执行导入。
 * OutputStream / InputStream 由 UI 层（SAF）提供，本类不碰 ContentResolver；
 * 纯组装与执行，均挂 Dispatchers.IO。
 */
@Singleton
class IoRepository @Inject constructor(
    private val db: GhDatabase,
    private val tripRepository: TripRepository,
    private val settingsRepository: SettingsRepository,
) {

    /** 导出全部 FINISHED 记录（F-IO-02），逐条回调（不整包进内存）；onTotal 先行报告总数（进度条用） */
    suspend fun exportAllTrips(
        crs: String,
        generator: GeneratorInfo?,
        onTotal: (Int) -> Unit = {},
        writeOne: suspend (fileName: String, content: suspend (OutputStream) -> Unit) -> Unit,
    ): Int = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
        val trips = db.tripDao().finishedAll()
        onTotal(trips.size)
        for (trip in trips) {
            val fileName = FileNamer.tripFileName(trip.name, trip.startTime)
            writeOne(fileName) { out -> writeTripJson(trip.id, crs, generator, out) }
        }
        trips.size
    }

    /** 计划线路单文件导出（F-PLAN-43）：gohiking.planned_route 信封；流不关闭 */
    suspend fun writePlannedRoute(
        routeId: String,
        crs: String,
        generator: GeneratorInfo?,
        output: OutputStream,
    ): Boolean = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
        val withLegs = db.plannedRouteDao().withLegs(routeId) ?: return@withContext false
        val waypoints = if (withLegs.legs.isEmpty()) {
            emptyList()
        } else {
            db.plannedRouteDao().waypointsOfLegs(withLegs.legs.map { it.id })
        }
        val envelope = TripJsonExporter.exportPlannedRoute(
            route = withLegs.route,
            legs = withLegs.legs,
            waypoints = waypoints,
            crs = crs,
            generator = generator,
        )
        output.buffered().use {
            it.write(IoCodecs.json.encodeToString(PlannedRouteEnvelope.serializer(), envelope).toByteArray(Charsets.UTF_8))
        }
        true
    }

    /** 导出坐标系取当前设置（GPX 不受此设置影响，冻结决策 15） */
    suspend fun currentExportCrs(): String =
        settingsRepository.settings.first().ioExportCrs

    /** 单条记录 JSON 写入（F-IO-01/08）；流不关闭（调用方管理） */
    suspend fun writeTripJson(
        tripId: String,
        crs: String,
        generator: GeneratorInfo?,
        output: OutputStream,
    ): Boolean = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
        val envelope = tripEnvelope(tripId, crs, generator) ?: return@withContext false
        output.buffered().use {
            it.write(IoCodecs.json.encodeToString(TripEnvelope.serializer(), envelope).toByteArray(Charsets.UTF_8))
        }
        true
    }

    /** GPX 互操作导出（F-IO-04）：文件名 + 文本内容；label 由调用方本地化 */
    suspend fun gpxContent(
        tripId: String,
        labelOf: (com.gohiking.core.database.entity.MarkerEntity) -> String,
    ): Pair<String, String>? = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
        val trip = db.tripDao().byId(tripId) ?: return@withContext null
        val points = db.trackPointDao().allOf(tripId)
        val markers = db.markerDao().allOf(tripId)
        FileNamer.gpxFileName(trip.name, trip.startTime) to GpxExporter.export(trip, points, markers, labelOf)
    }

    /** 全量备份 ZIP（F-IO-03）；v1 顺序组装（条目逐个写入，manifest 最后） */
    suspend fun writeBackup(
        crs: String,
        includeSettings: Boolean,
        generator: GeneratorInfo?,
        readme: String,
        output: OutputStream,
    ): Int = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
        val trips = db.tripDao().finishedAll().mapNotNull { tripEnvelope(it.id, crs, generator) }
        val routes = db.plannedRouteDao().allWithLegs()
        val routeEnvelopes = routes.map { (route, legs) ->
            val waypoints = if (legs.isEmpty()) {
                emptyList()
            } else {
                db.plannedRouteDao().waypointsOfLegs(legs.map { it.id })
            }
            TripJsonExporter.exportPlannedRoute(route, legs, waypoints, crs = crs, generator = generator)
        }
        val settingsJson = if (includeSettings) {
            IoCodecs.json.encodeToString(
                JsonObject.serializer(),
                SettingsBackupMapper.toJson(settingsRepository.settings.first()),
            )
        } else {
            null
        }
        val result = BackupBuilder.build(
            trips = trips,
            routes = routeEnvelopes,
            settingsJson = settingsJson,
            includeChecksums = settingsRepository.settings.first().ioBackupIncludeChecksum,
            crs = crs,
            generator = generator,
            readme = readme,
        )
        output.buffered().use { it.write(result.bytes) }
        trips.size
    }

    /** 解析导入来源（F-IO-20/21/22）：JSON 单文件与 ZIP 备份包共用解析器；字节由调用方读好（SAF 流即时关闭） */
    suspend fun parseImportSources(sources: List<Pair<String, ByteArray>>): ImportSources =
        kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
            val files = mutableListOf<ParsedFile>()
            val warnings = mutableListOf<ImportWarning>()
            for ((name, bytes) in sources) {
                if (name.endsWith(".zip", ignoreCase = true)) {
                    val contents = BackupReader.read(java.io.ByteArrayInputStream(bytes))
                    files += contents.trips
                    files += contents.routes
                    files += contents.invalid
                    warnings += contents.warnings.map { w -> w.copy(fileName = w.fileName ?: name) }
                } else {
                    files += IoParser.parse(name, bytes)
                }
            }
            ImportSources(files, warnings, sources.sumOf { it.second.size.toLong() })
        }

    /**
     * @param totalBytes 实际读入的字节数（`sources` 各 ByteArray 之和），F-IO-64 超限判定用
     */
    data class ImportSources(
        val files: List<ParsedFile>,
        val warnings: List<ImportWarning>,
        val totalBytes: Long = 0L,
    )

    /** 导入预览（F-IO-24 + F-IO-64 体积提示） */
    suspend fun preview(
        files: List<ParsedFile>,
        totalBytes: Long = 0L,
    ): ImportEngine.ImportPreview =
        kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
            ImportEngine(RoomImportSink(db)).preview(files, totalBytes)
        }

    /** 执行导入（F-IO-27~31）：策略与逐条决策由 UI 传入 */
    suspend fun executeImport(
        files: List<ParsedFile>,
        policy: ConflictPolicy,
        decisions: Map<String, ConflictPolicy> = emptyMap(),
        onProgress: suspend (Int, Int) -> Unit = { _, _ -> },
    ): ImportEngine.ImportReport = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
        ImportEngine(RoomImportSink(db)).execute(files, policy, decisions, onProgress)
    }

    // ---- 内部组装 ----

    private suspend fun tripEnvelope(
        tripId: String,
        crs: String,
        generator: GeneratorInfo?,
    ): TripEnvelope? {
        val trip = db.tripDao().byId(tripId) ?: return null
        val points = db.trackPointDao().allOf(tripId)
        val markers = db.markerDao().allOf(tripId)
        val media = db.mediaDao().refsOf(tripId)
        val withLegs = trip.plannedRouteId?.let { db.plannedRouteDao().withLegs(it) }
        val waypoints = withLegs?.legs?.takeIf { it.isNotEmpty() }
            ?.let { db.plannedRouteDao().waypointsOfLegs(it.map { l -> l.id }) }
            ?: emptyList()
        val settings = settingsRepository.settings.first()
        return TripJsonExporter.export(
            trip = trip,
            points = points,
            markers = markers,
            media = media,
            crs = crs,
            plannedRoute = withLegs?.route,
            plannedLegs = withLegs?.legs ?: emptyList(),
            plannedWaypoints = waypoints,
            alertSettings = AlertSettingsJson(
                enabled = settings.alertMasterEnabled,
                voiceEnabled = settings.alertVoiceEnabled,
                distanceAlertEnabled = settings.alertDistanceEnabled,
                distanceIntervalM = settings.alertDistanceIntervalM,
                altitudeAlertEnabled = settings.alertAltitudeEnabled,
                altitudeIntervalM = settings.alertAltitudeIntervalM,
            ),
            splits = splitsJson(tripId, trip.hasBarometer),
            generator = generator,
        )
    }

    /** splits 计算后映射（F-IO-09 默认全含；splits 不落库，按需计算，DEV 决策 14） */
    private suspend fun splitsJson(tripId: String, hasBarometer: Boolean): JsonObject = buildJsonObject {
        val km: List<KmSplit> = tripRepository.kmSplits(tripId)
        put("byKilometer", JsonArray(km.map { s ->
            buildJsonObject {
                put("index", s.index)
                put("distanceM", s.distanceM)
                put("durationSec", s.movingSec)
                s.paceSecPerKm?.let { put("paceSecPerKm", it) }
            }
        }))
        val threshold = if (hasBarometer) 3.0 else 10.0
        val gain: List<GainSplit> = tripRepository.gainSplits(tripId)
        put("byAltitudeGain", JsonArray(gain.map { s ->
            buildJsonObject {
                put("index", s.index)
                put("gainM", s.gainM)
                put("distanceM", s.distanceM)
                put("durationSec", s.movingSec)
                s.paceSecPerKm?.let { put("paceSecPerKm", it) }
            }
        }))
        // threshold 记录在侧（gainSplits 内部阈值），便于下游复算
        put("ascentThresholdM", threshold)
    }
}
