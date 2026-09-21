package com.gohiking.app

import android.Manifest
import android.app.Activity
import android.content.pm.PackageManager
import android.content.res.Configuration
import android.os.Build
import android.os.Bundle
import android.widget.Toast
import java.util.Locale
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Image
import androidx.compose.material.icons.filled.Landscape
import androidx.compose.material.icons.filled.Layers
import androidx.compose.material.icons.filled.MyLocation
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Route
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.Button
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import timber.log.Timber
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.amap.api.maps.AMap
import com.amap.api.maps.CameraUpdateFactory
import com.amap.api.maps.MapView
import com.amap.api.maps.MapsInitializer
import com.amap.api.maps.model.LatLng
import com.gohiking.core.data.io.BackupBuilder
import com.gohiking.core.data.io.ConflictPolicy
import com.gohiking.core.data.io.FileNamer
import com.gohiking.core.data.io.GeneratorInfo
import com.gohiking.core.data.io.ImportWarning
import com.gohiking.core.data.io.IoRepository
import com.gohiking.core.data.io.ParsedFile
import com.gohiking.core.data.io.RoomImportSink
import com.gohiking.core.data.media.MediaRepository
import com.gohiking.core.data.recording.RecordingService
import com.gohiking.core.data.recording.RecordingSession
import com.gohiking.core.data.recording.SessionState
import com.gohiking.core.data.repository.TripRepository
import com.gohiking.core.common.format.DisplayUnitProvider
import com.gohiking.core.common.format.DisplayUnits
import com.gohiking.core.datastore.AppSettings
import com.gohiking.core.datastore.SettingsRepository
import com.gohiking.core.datastore.readLanguageBlocking
import com.gohiking.core.database.dao.PlannedRouteDao
import com.gohiking.core.elevation.ElevationRepository
import com.gohiking.core.designsystem.theme.GhColors
import com.gohiking.core.designsystem.theme.GhTheme
import com.gohiking.core.location.LocationProvider
import com.gohiking.core.map.route.RouteSearchClient
import com.gohiking.core.map.search.AmapSearchClient
import com.gohiking.core.resources.R as CoreR
import com.gohiking.feature.history.HistoryListScreen
import com.gohiking.feature.history.TripDetailScreen
import com.gohiking.feature.io.ImportPreviewDialog
import com.gohiking.feature.io.ImportReportDialog
import com.gohiking.feature.io.IoProgressDialog
import com.gohiking.feature.plan.PlanListScreen
import com.gohiking.feature.plan.PlanScreen
import com.gohiking.feature.recording.RecordingScreen
import com.gohiking.feature.settings.IoActions
import com.gohiking.feature.settings.SettingsScreen
import dagger.hilt.android.AndroidEntryPoint
import javax.inject.Inject
import kotlinx.coroutines.Job
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

/**
 * 用户可见错误：携带 stringResource 的 id 与插值参数，而不是已经拼好的中文（H-09）。
 * 这样英文/中文跟随系统 locale，且 lint / checkStringKeys 能覆盖到。
 */
private class UserMessageError(val resId: Int, val args: List<Any?>) : RuntimeException()

/** 抛出一个用户可见错误（文案在 core/resources，%1$s 等占位符由 [args] 填充） */
private fun userError(resId: Int, vararg args: Any?): Nothing = throw UserMessageError(resId, args.toList())

/**
 * M1 壳：①隐私同意门（DEV §1.5 红线：高德 SDK 必须在用户同意后才初始化）；
 * ②首页地图（P-02 雏形：地图 + 开始记录）；③记录页切换（session 状态驱动）。
 * 导航宿主在 M1 后续按 DEV §5.4 落地。
 */
/** N-29：首页「再按一次退出」的确认窗口 */
private const val EXIT_CONFIRM_WINDOW_MS = 2_000L

@AndroidEntryPoint
class MainActivity : ComponentActivity() {

    @Inject lateinit var session: RecordingSession
    @Inject lateinit var tripRepository: TripRepository
    @Inject lateinit var locationProvider: LocationProvider
    @Inject lateinit var plannedRouteDao: PlannedRouteDao
    @Inject lateinit var elevationRepository: ElevationRepository
    @Inject lateinit var settingsRepository: SettingsRepository
    @Inject lateinit var ioRepository: IoRepository
    @Inject lateinit var importSink: RoomImportSink
    @Inject lateinit var mediaRepository: MediaRepository

    /** F-I18N-11：语言在 Activity 重建（重启）时经 attachBaseContext 生效 */
    override fun attachBaseContext(newBase: android.content.Context) {
        val lang = readLanguageBlocking(newBase)
        super.attachBaseContext(
            if (lang == AppSettings.LANGUAGE_SYSTEM) {
                newBase
            } else {
                newBase.createConfigurationContext(
                    Configuration(newBase.resources.configuration).apply {
                        setLocale(Locale.forLanguageTag(lang))
                    },
                )
            },
        )
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            GhTheme {
                Root(
                    session = session,
                    tripRepository = tripRepository,
                    locationProvider = locationProvider,
                    plannedRouteDao = plannedRouteDao,
                    elevationRepository = elevationRepository,
                    settingsRepository = settingsRepository,
                    ioRepository = ioRepository,
                    importSink = importSink,
                    mediaRepository = mediaRepository,
                )
            }
        }
    }
}

@Composable
private fun Root(
    session: RecordingSession,
    tripRepository: TripRepository,
    locationProvider: LocationProvider,
    plannedRouteDao: PlannedRouteDao,
    elevationRepository: ElevationRepository,
    settingsRepository: SettingsRepository,
    ioRepository: IoRepository,
    importSink: RoomImportSink,
    mediaRepository: MediaRepository, // F-HIS-28 照片条（M4-B1）
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    var agreed by remember { mutableStateOf<Boolean?>(null) } // null = 尚未从 DataStore 读取
    var showHistory by rememberSaveable { mutableStateOf(false) }
    var openTripId by rememberSaveable { mutableStateOf<String?>(null) }
    var showPlan by rememberSaveable { mutableStateOf(false) }
    var showPlanList by rememberSaveable { mutableStateOf(false) }
    var showSettings by rememberSaveable { mutableStateOf(false) }
    var showPhotoMap by rememberSaveable { mutableStateOf(false) }
    var showPermGuide by rememberSaveable { mutableStateOf(false) } // P-01 权限引导页 // P-12 照片地图（M4-B2）
    val sessionState by session.state.collectAsStateWithLifecycle()
    val searchClient = remember { AmapSearchClient(context) }
    val routeClient = remember { RouteSearchClient(context) }
    // F-REC-63：气压计探测（海拔打点间隔下限 30m 的提示依据）
    val hasBarometer = remember {
        context.packageManager.hasSystemFeature(PackageManager.FEATURE_SENSOR_BAROMETER)
    }

    // ---- M3-D2：IO 导出/导入状态机（P-16/17/18）----
    val scope = rememberCoroutineScope()
    var ioJob by remember { mutableStateOf<Job?>(null) }
    var ioBusy by remember { mutableStateOf(false) }
    var ioProgress by remember { mutableStateOf<com.gohiking.core.data.io.ImportEngine.Progress?>(null) }
    var ioDoneMsg by remember { mutableStateOf<String?>(null) }
    var ioErrorMsg by remember { mutableStateOf<String?>(null) }
    var ioErrorRes by remember { mutableStateOf<Pair<Int, List<Any?>>?>(null) }
    // H-09：解析告警已改为「原因码 + 参数」的 ImportWarning，不再是本地化后的字符串
    var pendingImport by remember { mutableStateOf<Pair<List<ParsedFile>, List<ImportWarning>>?>(null) }
    var importPreview by remember { mutableStateOf<com.gohiking.core.data.io.ImportEngine.ImportPreview?>(null) }
    var importPolicy by remember { mutableStateOf(ConflictPolicy.SKIP) }
    var importDecisions by remember { mutableStateOf<Map<String, ConflictPolicy>>(emptyMap()) }
    var importReport by remember { mutableStateOf<com.gohiking.core.data.io.ImportEngine.ImportReport?>(null) }
    val generator = GeneratorInfo(versionName = BuildConfig.VERSION_NAME, versionCode = BuildConfig.VERSION_CODE)

    fun ioRun(block: suspend () -> Unit) {
        ioJob = scope.launch {
            ioBusy = true
            ioErrorMsg = null
            ioErrorRes = null
            try {
                block()
            } catch (e: CancellationException) {
                // 用户取消：已导入/已导出的保留（F-IO-11/29）
            } catch (e: UserMessageError) {
                ioErrorRes = e.resId to e.args
            } catch (t: Throwable) {
                // M-10：不把异常类名抛给用户，统一走 error_generic
                Timber.w(t, "IO 失败")
                ioErrorMsg = t::class.java.simpleName
            } finally {
                ioBusy = false
                ioProgress = null
            }
        }
    }

    // M-02：同意状态以 DataStore 为准。此前只用 rememberSaveable：冷启动需重弹，
    // 而进程被杀后由 Bundle 恢复 true 又会绕过同意直接调用隐私 API。
    LaunchedEffect(Unit) {
        agreed = settingsRepository.settings.first().privacyAgreed
        // N-01：PrivacyConsent.agreed 是**进程内内存态**，冷启动必然复位为 false。
        // 而 markAgreed() 只在 PrivacyGate.onAgree 里调用——一旦持久化同意已为 true，
        // PrivacyGate 不再组合，它就永远不会被调用，之后任何定位 SDK 初始化
        // （AmapLocationSource.newClient）都会在 assertAgreed() 的 check() 处抛
        // IllegalStateException。因此必须把持久化态同步回内存态。
        if (agreed == true) com.gohiking.core.location.PrivacyConsent.markAgreed()
    }

    // H-02：单位/配速显示设置此前从不生效（AppSettings 三项除备份序列化外零读取点），
    // 这里把设置同步给 core:common 的展示层单例。
    LaunchedEffect(Unit) {
        settingsRepository.settings.collect { s ->
            DisplayUnitProvider.update(
                DisplayUnits.fromStrings(s.unitDistance, s.unitAltitude, s.unitPaceDisplay)
            )
        }
    }

    // 导出全部记录 → SAF 目录（F-IO-02/05/06/13）
    val exportAllTree = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocumentTree(),
    ) { treeUri ->
        if (treeUri == null) return@rememberLauncherForActivityResult
        ioRun {
            val crs = ioRepository.currentExportCrs()
            val resolver = context.contentResolver
            var done = 0
            val n = ioRepository.exportAllTrips(crs, generator, onTotal = { total ->
                ioProgress = com.gohiking.core.data.io.ImportEngine.Progress(0, total)
            }) { fileName, content ->
                val docUri = android.provider.DocumentsContract.createDocument(resolver, treeUri, "application/json", fileName)
                    ?: userError(CoreR.string.io_error_create_file, fileName)
                resolver.openOutputStream(docUri)?.use { content(it) }
                done++
                ioProgress = com.gohiking.core.data.io.ImportEngine.Progress(done, ioProgress?.total ?: 0)
            }
            ioDoneMsg = if (n == 0) "0" else n.toString()
        }
    }

    // 全量备份 ZIP（F-IO-03/07）
    val backupDoc = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("application/zip"),
    ) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        ioRun {
            val crs = ioRepository.currentExportCrs()
            val resolver = context.contentResolver
            ioProgress = com.gohiking.core.data.io.ImportEngine.Progress(0, 0)
            resolver.openOutputStream(uri)?.use { out ->
                val n = ioRepository.writeBackup(
                    crs = crs,
                    includeSettings = true,
                    generator = generator,
                    readme = BackupBuilder.DEFAULT_README,
                    output = out,
                )
                ioDoneMsg = n.toString()
            } ?: userError(CoreR.string.io_error_write_file)
        }
    }

    // 单条记录导出（F-IO-01，F-HIS-30 详情页按钮）：CreateDocument 输入 = 建议文件名
    var pendingTripExport by rememberSaveable { mutableStateOf<String?>(null) } // tripId
    val tripExportDoc = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("application/json"),
    ) { uri ->
        val tripId = pendingTripExport
        pendingTripExport = null
        if (uri == null || tripId == null) return@rememberLauncherForActivityResult
        ioRun {
            val crs = ioRepository.currentExportCrs()
            val resolver = context.contentResolver
            resolver.openOutputStream(uri)?.use { out ->
                if (!ioRepository.writeTripJson(tripId, crs, generator, out)) {
                    userError(CoreR.string.io_error_trip_missing, tripId)
                }
            } ?: userError(CoreR.string.io_error_write_file)
            ioDoneMsg = "1"
        }
    }

    // 计划线路单文件导出（F-PLAN-43）：CreateDocument 输入 = 建议文件名
    var pendingRouteExport by rememberSaveable { mutableStateOf<String?>(null) } // "routeId"
    val routeExportDoc = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("application/json"),
    ) { uri ->
        val routeId = pendingRouteExport
        pendingRouteExport = null
        if (uri == null || routeId == null) return@rememberLauncherForActivityResult
        ioRun {
            val crs = ioRepository.currentExportCrs()
            val resolver = context.contentResolver
            resolver.openOutputStream(uri)?.use { out ->
                if (!ioRepository.writePlannedRoute(routeId, crs, generator, out)) {
                    userError(CoreR.string.io_error_route_missing, routeId)
                }
            } ?: userError(CoreR.string.io_error_write_file)
            ioDoneMsg = "1"
        }
    }

    // 多选导入（F-IO-20/21/22）→ 先解析预览（F-IO-24）
    val importDocs = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenMultipleDocuments(),
    ) { uris ->
        if (uris.isNullOrEmpty()) return@rememberLauncherForActivityResult
        ioRun {
            val resolver = context.contentResolver
            val sources = uris.map { uri ->
                val name = queryDisplayName(resolver, uri)
                    ?: uri.lastPathSegment
                    ?: context.getString(CoreR.string.common_unknown_file)
                val bytes = resolver.openInputStream(uri)?.use { it.readBytes() }
                    ?: userError(CoreR.string.io_error_open_file, name)
                name to bytes
            }
            val parsed = ioRepository.parseImportSources(sources)
            // H-01：ioImportConflictPolicy 此前从不读取，策略恒为初始 SKIP
            importPolicy = runCatching {
                ConflictPolicy.valueOf(settingsRepository.settings.first().ioImportConflictPolicy.uppercase())
            }.getOrDefault(ConflictPolicy.SKIP)
            importDecisions = emptyMap()
            pendingImport = parsed.files to parsed.warnings
            // F-IO-64：体积随预览一起给出，>500MB 时预览页必须先提示确认
            importPreview = ioRepository.preview(parsed.files, parsed.totalBytes)
        }
    }

    if (agreed == null) {
        // 尚未读到同意状态：不组合任何地图/定位，避免先于同意初始化 SDK（DEV §1.5 红线）
        Box(modifier = modifier.fillMaxSize())
        return
    }

    if (agreed != true) {
        PrivacyGate(
            onAgree = {
                agreed = true
                showPermGuide = true // P-01：首次同意后展示权限引导
                // C-03：定位 SDK 的合规调用必须由明确的用户同意驱动
                com.gohiking.core.location.PrivacyConsent.markAgreed()
                scope.launch { settingsRepository.setPrivacyAgreed(true) }
            },
            onDecline = { (context as? Activity)?.finish() },
        )
        return
    }

    // N-29：C-04 补上了子页面的返回分发，但根页面分支只做 moveTaskToBack——
    // 用户在首页反复按返回键只会一次次退到后台，永远不会退出应用，与平台惯例不符。
    // 这里改成「2 秒内连按两次才真正退出」。
    var lastBackPressAtMs by remember { mutableStateOf(0L) }

    fun goBack() {
        when {
            showPermGuide -> showPermGuide = false
            openTripId != null -> openTripId = null
            showPlanList -> showPlanList = false
            showPlan -> showPlan = false
            showPhotoMap -> showPhotoMap = false
            showSettings -> showSettings = false
            showHistory -> showHistory = false
            else -> {
                val now = System.currentTimeMillis()
                if (now - lastBackPressAtMs < EXIT_CONFIRM_WINDOW_MS) {
                    (context as? Activity)?.finish()
                } else {
                    lastBackPressAtMs = now
                    (context as? Activity)?.moveTaskToBack(true)
                }
            }
        }
    }

    // C-04：系统返回键分发。此前全仓库无 BackHandler，7 个子页面都给了 onBack
    // 却没接在系统返回键上，任意子页返回键 = finish() 整个 App。
    BackHandler {
        if (sessionState !is SessionState.Idle) {
            // 记录中：退到后台即可，前台服务维持采集
            (context as? Activity)?.moveTaskToBack(true)
        } else {
            goBack()
        }
    }

    // C-02（F-REC-09）：崩溃/进程被杀后恢复未结束的记录。
    // 此前 hasRecoverableSession()/restoreFromSnapshot() 从未被调用，快照只写不读。
    var restorePrompt by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) {
        if (session.state.value is SessionState.Idle && session.hasRecoverableSession()) {
            restorePrompt = true
        }
    }
    if (restorePrompt) {
        AlertDialog(
            onDismissRequest = { },
            title = { Text(stringResource(CoreR.string.rec_restore_title)) },
            text = { Text(stringResource(CoreR.string.rec_restore_message)) },
            confirmButton = {
                TextButton(onClick = {
                    restorePrompt = false
                    scope.launch {
                        if (session.restoreFromSnapshot()) {
                            runCatching { RecordingService.start(context) }
                                .onFailure { Timber.w(it, "恢复后启动前台服务失败") }
                        }
                    }
                }) { Text(stringResource(CoreR.string.rec_restore_continue)) }
            },
            dismissButton = {
                TextButton(onClick = {
                    restorePrompt = false
                    scope.launch { session.discardRecoverable() }
                }) { Text(stringResource(CoreR.string.rec_action_discard)) }
            },
        )
    }

    if (showPermGuide) {
        PermissionGuideScreen(onDone = { showPermGuide = false })
    } else if (sessionState !is SessionState.Idle) {
        RecordingScreen(
            session = session,
            tripRepository = tripRepository,
            plannedRouteDao = plannedRouteDao,
            modifier = modifier,
        )
    } else if (openTripId != null) {
        TripDetailScreen(
            tripRepository = tripRepository,
            mediaRepository = mediaRepository,
            tripId = openTripId!!,
            onBack = { openTripId = null },
            onDeleted = { openTripId = null },
            onExportTrip = { tripId, tripName ->
                pendingTripExport = tripId
                tripExportDoc.launch(FileNamer.tripFileName(tripName, System.currentTimeMillis()))
            },
            modifier = modifier,
        )
    } else if (showPlanList) {
        PlanListScreen(
            plannedRouteDao = plannedRouteDao,
            onBack = { showPlanList = false },
            onNewRoute = { showPlanList = false; showPlan = true }, // P-07「+」→ 选点页 P-03
            onExportRoute = { routeId, routeName ->
                pendingRouteExport = routeId
                routeExportDoc.launch(FileNamer.routeFileName(routeName, System.currentTimeMillis()))
            },
            modifier = modifier,
        )
    } else if (showPlan) {
        PlanScreen(
            searchClient = searchClient,
            routeClient = routeClient,
            locationProvider = locationProvider,
            plannedRouteDao = plannedRouteDao,
            elevationRepository = elevationRepository,
            onBack = { showPlan = false },
            modifier = modifier,
        )
    } else if (showPhotoMap) {
        com.gohiking.feature.media.PhotoMapScreen(
            mediaRepository = mediaRepository,
            onBack = { showPhotoMap = false },
            modifier = modifier,
        )
    } else if (showSettings) {
        SettingsScreen(
            settingsRepository = settingsRepository,
            hasBarometer = hasBarometer,
            onLanguageChanged = { lang ->
                // F-I18N-11：偏好已写入 DataStore，重启后 attachBaseContext 生效
                Toast.makeText(context, context.getString(CoreR.string.set_language_restart), Toast.LENGTH_SHORT).show()
                if (lang != AppSettings.LANGUAGE_SYSTEM) (context as? Activity)?.recreate()
            },
            ioActions = IoActions(
                onExportAll = { exportAllTree.launch(null) }, // F-IO-02 批量导出
                onBackup = {
                    backupDoc.launch(FileNamer.backupFileName(System.currentTimeMillis())) // F-IO-07
                },
                onImport = {
                    importDocs.launch(arrayOf("application/json", "application/zip", "application/octet-stream"))
                },
            ),
            onBack = { showSettings = false },
            modifier = modifier,
        )
    } else if (showHistory) {
        HistoryListScreen(
            tripRepository = tripRepository,
            onBack = { showHistory = false },
            onOpenTrip = { openTripId = it },
            onStartRecording = { showHistory = false }, // P-10 空态「开始记录」→ 回首页
            modifier = modifier,
        )
    } else {
        MapVerifyScreen(
            modifier = modifier,
            session = session,
            onOpenHistory = { showHistory = true },
            onOpenPlan = { showPlan = true },
            onOpenPlanList = { showPlanList = true },
            onOpenSettings = { showSettings = true },
            onOpenPhotoMap = { showPhotoMap = true },
        )
    }

    // ---- P-16/17/18：IO 对话框 ----
    if (ioBusy) {
        IoProgressDialog(
            title = stringResource(CoreR.string.io_working),
            done = ioProgress?.done ?: 0,
            total = ioProgress?.total ?: 0,
            onCancel = { ioJob?.cancel() }, // F-IO-11/29 支持取消
        )
    }
    importPreview?.let { preview ->
        ImportPreviewDialog(
            preview = preview,
            extraWarnings = pendingImport?.second ?: emptyList(),
            policy = importPolicy,
            onPolicyChange = { importPolicy = it },
            decisions = importDecisions,
            onDecisionChange = { name, p -> importDecisions = importDecisions + (name to p) },
            conflictFiles = run {
                // 预览里的 conflictTrips/conflictRoutes 是 id，而 decisions 按**文件名**索引
                val fs = pendingImport?.first.orEmpty()
                fs.mapNotNull {
                    when (it) {
                        is ParsedFile.TripFile ->
                            it.name.takeIf { _ -> it.envelope.trip.id in preview.conflictTrips }
                        is ParsedFile.RouteFile ->
                            it.name.takeIf { _ -> (it.envelope.plannedRoute.id ?: "") in preview.conflictRoutes }
                        else -> null
                    }
                }
            },
            onConfirm = {
                val files = pendingImport?.first ?: return@ImportPreviewDialog
                importPreview = null
                ioRun {
                    val report = ioRepository.executeImport(
                        files = files,
                        policy = importPolicy,
                        // H-01：必须把 UI 的逐条决策传下去，否则 ASK 会把全部 trip 判 SKIPPED
                        decisions = importDecisions,
                    ) { done, total ->
                        ioProgress = com.gohiking.core.data.io.ImportEngine.Progress(done, total)
                    }
                    importReport = report
                    pendingImport = null
                }
            },
            onDismiss = {
                importPreview = null
                pendingImport = null
            },
        )
    }
    importReport?.let { report ->
        ImportReportDialog(
            report = report,
            onDismiss = { importReport = null },
        )
    }
    ioDoneMsg?.let { n ->
        AlertDialog(
            onDismissRequest = { ioDoneMsg = null },
            title = { Text(stringResource(CoreR.string.io_done_title)) },
            text = { Text(stringResource(CoreR.string.io_done, n)) },
            confirmButton = {
                TextButton(onClick = { ioDoneMsg = null }) { Text(stringResource(CoreR.string.common_action_confirm)) }
            },
        )
    }
    val errorResPair = ioErrorRes
    if (errorResPair != null) {
        AlertDialog(
            onDismissRequest = { ioErrorRes = null },
            title = { Text(stringResource(CoreR.string.io_error)) },
            // vararg 只收非空 Any：List<Any?> 直接 toTypedArray 得到 Array<Any?>，展开会类型不符
            text = {
                Text(
                    stringResource(
                        errorResPair.first,
                        *errorResPair.second.map { it ?: "" }.toTypedArray(),
                    ),
                )
            },
            confirmButton = {
                TextButton(onClick = { ioErrorRes = null }) {
                    Text(stringResource(CoreR.string.common_action_confirm))
                }
            },
        )
    }
    if (ioErrorMsg != null) {
        AlertDialog(
            onDismissRequest = { ioErrorMsg = null },
            title = { Text(stringResource(CoreR.string.io_error)) },
            // M-10：error_generic 此前定义了却全仓库未引用，异常类名不该给用户看
            text = { Text(stringResource(CoreR.string.error_generic)) },
            confirmButton = {
                TextButton(onClick = { ioErrorMsg = null }) {
                    Text(stringResource(CoreR.string.common_action_confirm))
                }
            },
        )
    }
}

/** SAF 文件名（OpenableColumns.DISPLAY_NAME） */
private fun queryDisplayName(resolver: android.content.ContentResolver, uri: android.net.Uri): String? {
    resolver.query(uri, null, null, null, null)?.use { cursor ->
        val idx = cursor.getColumnIndex(android.provider.OpenableColumns.DISPLAY_NAME)
        if (idx >= 0 && cursor.moveToFirst()) return cursor.getString(idx)
    }
    return null
}

@Composable
private fun PrivacyGate(onAgree: () -> Unit, onDecline: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDecline,
        title = { Text(stringResource(CoreR.string.common_privacy_title)) },
        text = { Text(stringResource(CoreR.string.common_privacy_message)) },
        confirmButton = {
            TextButton(onClick = onAgree) { Text(stringResource(CoreR.string.common_privacy_agree)) }
        },
        dismissButton = {
            TextButton(onClick = onDecline) { Text(stringResource(CoreR.string.common_privacy_decline)) }
        },
    )
}

@Composable
private fun MapVerifyScreen(
    session: RecordingSession,
    modifier: Modifier = Modifier,
    onOpenHistory: () -> Unit = {},
    onOpenPlan: () -> Unit = {},
    onOpenPlanList: () -> Unit = {},
    onOpenSettings: () -> Unit = {},
    onOpenPhotoMap: () -> Unit = {}, // P-12 照片地图（M4-B2）
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current

    // P-02：左下角提示 chip 复用为最近一条地图事件（保留 M0 点击冲突回归观测点）
    var lastEvent by remember { mutableStateOf<String?>(null) }
    var satellite by rememberSaveable { mutableStateOf(false) } // 图层切换（正常/卫星）
    var centerOnMyLocation by remember { mutableStateOf(false) } // 定位按钮 → 蓝点就绪后居中一次
    var lastPoiAtMs by remember { mutableLongStateOf(0L) }
    var lastMapClickAtMs by remember { mutableLongStateOf(0L) }
    var showStartDialog by rememberSaveable { mutableStateOf(false) } // F-REC-02：开始前可选命名
    var startName by rememberSaveable { mutableStateOf("") }

    // L-28：权限状态为局部变量（每次授权回调只触发一次重组）
    fun locationGranted(): Boolean =
        ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION) ==
            PackageManager.PERMISSION_GRANTED

    var showPermissionHint by remember { mutableStateOf(false) }
    // H-05：权限回调共享 ON_START 阶段，此时 startForegroundService 在 Android 12+ 可能被拒。
    // 延到下一个组合帧（ON_RESUME 之后）再启动，降级为「服务起不来但不崩」。
    var pendingServiceStart by remember { mutableStateOf(false) }
    LaunchedEffect(pendingServiceStart) {
        if (!pendingServiceStart) return@LaunchedEffect
        pendingServiceStart = false
        runCatching { RecordingService.start(context) }
            .onFailure { Timber.w(it, "RecordingService.start 失败") }
    }

    fun log(text: String) {
        lastEvent = text
    }

    // 隐私合规必须【早于 MapView 创建】调用（3D SDK ≥9.1.0 强制，否则白屏不渲染瓦片）。
    // 放在 remember 里恰好保证：①先于地图创建；②进程内只调一次。
    val mapView = remember {
        MapsInitializer.updatePrivacyShow(context, true, true)
        MapsInitializer.updatePrivacyAgree(context, true)
        MapView(context).apply {
            onCreate(null)
            map.uiSettings.isZoomControlsEnabled = false // P-02：缩放由右侧地图按钮承担
            // 默认视角：中国全域；定位到当前位置由「蓝点接入」后处理
            map.moveCamera(CameraUpdateFactory.newLatLngZoom(LatLng(35.86, 104.19), 3.8f))

            // M0 实测①：POI 点击时 OnMapClickListener 是否同触发（已真机通过 2026-09-20，保留供回归）
            map.setOnMapClickListener { latLng ->
                lastMapClickAtMs = System.currentTimeMillis()
                log(
                    context.getString(
                        CoreR.string.map_test_log_map,
                        latLng.latitude.toString(),
                        latLng.longitude.toString(),
                    ),
                )
            }
            map.setOnPOIClickListener { poi ->
                lastPoiAtMs = System.currentTimeMillis()
                log(context.getString(CoreR.string.map_test_log_poi, poi.name ?: context.getString(CoreR.string.plan_poi_unnamed)))
                if (lastMapClickAtMs > 0 && kotlin.math.abs(lastPoiAtMs - lastMapClickAtMs) < 600) {
                    log(context.getString(CoreR.string.map_test_log_both))
                }
            }
            map.setOnMyLocationChangeListener { location ->
                if (centerOnMyLocation && location != null) {
                    centerOnMyLocation = false
                    map.moveCamera(
                        CameraUpdateFactory.newLatLngZoom(LatLng(location.latitude, location.longitude), 15f),
                    )
                }
            }
        }
    }

    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_RESUME -> mapView.onResume()
                Lifecycle.Event.ON_PAUSE -> mapView.onPause()
                else -> Unit
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
            // M-01：从详情页返回时 Activity 并未 pause，必须保证 pause→destroy 的调用顺序
            mapView.onPause()
            mapView.onDestroy()
        }
    }

    // 定位权限（F-REC-53 首层：FINE+COARSE；BACKGROUND 在开始记录引导，M1 下一批）
    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions(),
    ) { grants ->
        val granted = grants[Manifest.permission.ACCESS_FINE_LOCATION] == true ||
            grants[Manifest.permission.ACCESS_COARSE_LOCATION] == true
        if (granted) {
            session.start(startName.trim(), plannedRouteId = null) // 空名 → stop() 日期时间命名（F-REC-08）
            startName = ""
            pendingServiceStart = true // H-05：延后到 ON_RESUME 之后
        } else {
            showPermissionHint = true
        }
    }

    Box(modifier = modifier.fillMaxSize()) {
        AndroidView(factory = { mapView }, modifier = Modifier.fillMaxSize())

        // ---- P-02 顶部：搜索条（点击进入选点页 P-03） + 照片地图入口 ----
        Row(
            modifier = Modifier
                .align(Alignment.TopCenter)
                .fillMaxWidth()
                .statusBarsPadding()
                .padding(start = 12.dp, end = 12.dp, top = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Row(
                modifier = Modifier
                    .weight(1f)
                    .height(44.dp)
                    .clip(RoundedCornerShape(12.dp))
                    .background(GhColors.Surface)
                    .clickable(onClick = onOpenPlan)
                    .padding(horizontal = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(
                    Icons.Filled.Search,
                    contentDescription = null,
                    tint = GhColors.TextSecondary,
                    modifier = Modifier.size(18.dp),
                )
                Spacer(Modifier.width(8.dp))
                Text(
                    text = stringResource(CoreR.string.home_search_hint),
                    color = GhColors.TextTertiary,
                    fontSize = 14.sp,
                    maxLines = 1,
                )
            }
            Spacer(Modifier.width(10.dp))
            MapButton(icon = Icons.Filled.Image, contentDescriptionRes = CoreR.string.photo_map_title, onClick = onOpenPhotoMap)
        }

        // ---- P-02 右侧：图层 / 放大 / 定位 ----
        Column(
            modifier = Modifier
                .align(Alignment.TopEnd)
                .statusBarsPadding()
                .padding(top = 62.dp, end = 12.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            MapButton(icon = Icons.Filled.Layers, contentDescriptionRes = CoreR.string.common_layer) {
                satellite = !satellite
                mapView.map.mapType = if (satellite) AMap.MAP_TYPE_SATELLITE else AMap.MAP_TYPE_NORMAL
            }
            MapButton(icon = Icons.Filled.Add, contentDescriptionRes = CoreR.string.common_zoom_in) {
                mapView.map.moveCamera(CameraUpdateFactory.zoomIn())
            }
            MapButton(icon = Icons.Filled.MyLocation, contentDescriptionRes = CoreR.string.common_locate) {
                if (!locationGranted()) {
                    showPermissionHint = true
                } else {
                    centerOnMyLocation = true
                    runCatching { mapView.map.isMyLocationEnabled = true }
                        .onFailure { Timber.w(it, "isMyLocationEnabled 失败") }
                }
            }
        }

        // ---- P-02 左下：提示 chip（有事件时显示最近事件）----
        Box(
            modifier = Modifier
                .align(Alignment.BottomStart)
                .navigationBarsPadding()
                .padding(start = 12.dp, bottom = 72.dp)
                .clip(RoundedCornerShape(6.dp))
                .background(Color.White.copy(alpha = 0.86f))
                .padding(horizontal = 8.dp, vertical = 4.dp),
        ) {
            Text(
                text = lastEvent ?: stringResource(CoreR.string.home_map_hint),
                fontSize = 10.sp,
                color = Color(0xFF5A616B),
                maxLines = 1,
            )
        }

        // ---- P-02 底部：计划线路（白）/ 开始记录（红）----
        Row(
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .fillMaxWidth()
                .navigationBarsPadding()
                .padding(horizontal = 12.dp, vertical = 12.dp),
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            GhPillButton(
                text = stringResource(CoreR.string.plan_entry),
                icon = Icons.Filled.Route,
                container = GhColors.Surface,
                content = GhColors.TextPrimary,
                border = BorderStroke(1.dp, GhColors.Line),
                onClick = onOpenPlanList, // 原型 data-go="p07"：计划线路列表
                modifier = Modifier.weight(1f),
            )
            GhPillButton(
                text = stringResource(CoreR.string.home_start_record),
                icon = Icons.Filled.PlayArrow,
                container = GhColors.Danger,
                content = Color.White,
                onClick = { showStartDialog = true }, // F-REC-02：先弹可选命名，不阻塞
                modifier = Modifier.weight(1f),
            )
        }
    }

    // F-REC-02：开始前可选命名；关联计划线路属 F-PLAN，M2 接入
    if (showStartDialog) {
        AlertDialog(
            onDismissRequest = { showStartDialog = false },
            title = { Text(stringResource(CoreR.string.rec_start_dialog_title)) },
            text = {
                OutlinedTextField(
                    value = startName,
                    onValueChange = { startName = it },
                    placeholder = { Text(stringResource(CoreR.string.rec_start_name_hint)) },
                    singleLine = true,
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    showStartDialog = false
                    val permissions = buildList {
                        add(Manifest.permission.ACCESS_FINE_LOCATION)
                        add(Manifest.permission.ACCESS_COARSE_LOCATION)
                        if (Build.VERSION.SDK_INT >= 33) add(Manifest.permission.POST_NOTIFICATIONS)
                        // N-28：API 29+ 起 ACTIVITY_RECOGNITION 是运行时权限，未授权时计步源会被「选中」但收不到数据
                        if (Build.VERSION.SDK_INT >= 29) add(Manifest.permission.ACTIVITY_RECOGNITION)
                    }.toTypedArray()
                    permissionLauncher.launch(permissions)
                }) { Text(stringResource(CoreR.string.common_action_confirm)) }
            },
            dismissButton = {
                TextButton(onClick = {
                    showStartDialog = false
                    startName = ""
                }) { Text(stringResource(CoreR.string.common_action_cancel)) }
            },
        )
    }

    if (showPermissionHint) {
        AlertDialog(
            onDismissRequest = { showPermissionHint = false },
            title = { Text(stringResource(CoreR.string.common_privacy_title)) },
            text = { Text(stringResource(CoreR.string.rec_need_permission)) },
            confirmButton = {
                TextButton(onClick = { showPermissionHint = false }) {
                    Text(stringResource(CoreR.string.common_action_confirm))
                }
            },
        )
    }
}

/** P-02 地图右上的圆形工具按钮（44dp 白底 12dp 圆角，20dp 图标）。 */
@Composable
private fun MapButton(
    icon: ImageVector,
    contentDescriptionRes: Int,
    onClick: () -> Unit,
) {
    Box(
        modifier = Modifier
            .size(44.dp)
            .clip(RoundedCornerShape(12.dp))
            .background(GhColors.Surface)
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            icon,
            contentDescription = stringResource(contentDescriptionRes),
            tint = GhColors.TextPrimary,
            modifier = Modifier.size(20.dp),
        )
    }
}

/** P-01/P-02 通用胶囊按钮（原型 .btn.pri/.red/.gray：高 48dp、圆角 12dp、图标+文案）。 */
@Composable
private fun GhPillButton(
    text: String,
    icon: ImageVector?,
    container: Color,
    content: Color,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    border: BorderStroke? = null,
) {
    val shape = RoundedCornerShape(12.dp)
    Box(
        modifier = modifier
            .height(48.dp)
            .clip(shape)
            .background(container)
            .then(
                if (border != null) {
                    Modifier.border(border, shape)
                } else {
                    Modifier
                },
            )
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            if (icon != null) {
                Icon(icon, contentDescription = null, tint = content, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(6.dp))
            }
            Text(
                text = text,
                color = content,
                fontSize = 15.sp,
                fontWeight = FontWeight.Medium,
                maxLines = 1,
            )
        }
    }
}

/**
 * P-01 权限引导页（prototype 对齐）：品牌头 + 逐项权限说明卡 + 「全部允许 / 稍后设置」。
 * 仅在首次同意隐私政策后展示一次；定位权限仍会在开始记录时兜底申请（F-REC-53）。
 */
@Composable
private fun PermissionGuideScreen(onDone: () -> Unit) {
    val context = LocalContext.current
    var granted by remember { mutableStateOf(setOf<String>()) }

    fun refresh() {
        fun has(p: String) = ContextCompat.checkSelfPermission(context, p) == PackageManager.PERMISSION_GRANTED
        granted = buildSet {
            if (has(Manifest.permission.ACCESS_FINE_LOCATION) || has(Manifest.permission.ACCESS_COARSE_LOCATION)) {
                add("loc")
            }
            if (Build.VERSION.SDK_INT < 33 || has(Manifest.permission.POST_NOTIFICATIONS)) add("notif")
            if (Build.VERSION.SDK_INT < 29 || has(Manifest.permission.ACTIVITY_RECOGNITION)) add("sensor")
            if (Build.VERSION.SDK_INT >= 33) {
                if (has(Manifest.permission.READ_MEDIA_IMAGES)) add("media")
            } else if (has(Manifest.permission.READ_EXTERNAL_STORAGE)) {
                add("media")
            }
        }
    }

    DisposableEffect(Unit) {
        refresh()
        onDispose { }
    }

    val launcher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions(),
    ) { refresh() }

    data class GuideRow(val key: String, val title: Int, val desc: Int, val required: Boolean, val perms: List<String>)

    val rows = remember {
        buildList {
            add(
                GuideRow(
                    "loc", CoreR.string.guide_loc, CoreR.string.guide_loc_desc, true,
                    listOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION),
                ),
            )
            add(
                GuideRow(
                    "notif", CoreR.string.guide_notif, CoreR.string.guide_notif_desc, false,
                    if (Build.VERSION.SDK_INT >= 33) listOf(Manifest.permission.POST_NOTIFICATIONS) else emptyList(),
                ),
            )
            add(
                GuideRow(
                    "sensor", CoreR.string.guide_sensor, CoreR.string.guide_sensor_desc, false,
                    if (Build.VERSION.SDK_INT >= 29) listOf(Manifest.permission.ACTIVITY_RECOGNITION) else emptyList(),
                ),
            )
            add(
                GuideRow(
                    "media", CoreR.string.guide_media, CoreR.string.guide_media_desc, false,
                    if (Build.VERSION.SDK_INT >= 33) {
                        listOf(Manifest.permission.READ_MEDIA_IMAGES)
                    } else {
                        listOf(Manifest.permission.READ_EXTERNAL_STORAGE)
                    },
                ),
            )
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(GhColors.Bg)
            .verticalScroll(rememberScrollState())
            .navigationBarsPadding(),
    ) {
        // 品牌头
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 34.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Icon(
                Icons.Filled.Landscape,
                contentDescription = null,
                tint = GhColors.Primary,
                modifier = Modifier.size(64.dp),
            )
            Spacer(Modifier.height(14.dp))
            Text(
                text = stringResource(CoreR.string.app_name),
                fontSize = 22.sp,
                fontWeight = FontWeight.Bold,
            )
            Spacer(Modifier.height(6.dp))
            Text(text = "GoHiking", fontSize = 13.sp, color = GhColors.TextSecondary)
        }

        Text(
            text = stringResource(CoreR.string.guide_title),
            fontSize = 13.sp,
            color = GhColors.TextSecondary,
            modifier = Modifier.padding(start = 18.dp, end = 18.dp, top = 18.dp, bottom = 4.dp),
        )
        Text(
            text = stringResource(CoreR.string.guide_subtitle),
            fontSize = 12.sp,
            color = GhColors.TextSecondary,
            lineHeight = 19.sp,
            modifier = Modifier
                .padding(horizontal = 18.dp)
                .padding(bottom = 12.dp),
        )

        // 权限卡
        Surface(
            shape = RoundedCornerShape(12.dp),
            color = GhColors.Surface,
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp),
        ) {
            Column {
                rows.forEachIndexed { index, row ->
                    if (index > 0) HorizontalDivider(color = GhColors.Line2, thickness = 1.dp)
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 13.dp, vertical = 11.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text(text = stringResource(row.title), fontSize = 15.sp, fontWeight = FontWeight.Medium)
                                if (row.required) {
                                    Spacer(Modifier.width(4.dp))
                                    Box(
                                        modifier = Modifier
                                            .clip(RoundedCornerShape(6.dp))
                                            .background(GhColors.TagRedBg)
                                            .padding(horizontal = 6.dp, vertical = 2.dp),
                                    ) {
                                        Text(
                                            text = stringResource(CoreR.string.guide_tag_need_location),
                                            fontSize = 10.sp,
                                            color = GhColors.TagRedFg,
                                        )
                                    }
                                }
                            }
                            Spacer(Modifier.height(3.dp))
                            Text(
                                text = stringResource(row.desc),
                                fontSize = 12.sp,
                                color = GhColors.TextSecondary,
                                lineHeight = 17.sp,
                            )
                        }
                        Spacer(Modifier.width(10.dp))
                        val isGranted = row.key in granted || row.perms.isEmpty()
                        val btnShape = RoundedCornerShape(10.dp)
                        Box(
                            modifier = Modifier
                                .clip(btnShape)
                                .background(if (isGranted) GhColors.Surface2 else GhColors.Surface)
                                .then(
                                    if (isGranted) {
                                        Modifier
                                    } else {
                                        Modifier.border(BorderStroke(1.dp, GhColors.Line), btnShape)
                                    },
                                )
                                .then(
                                    if (isGranted || row.perms.isEmpty()) {
                                        Modifier
                                    } else {
                                        Modifier.clickable { launcher.launch(row.perms.toTypedArray()) }
                                    },
                                )
                                .padding(horizontal = 12.dp, vertical = 9.dp),
                        ) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                if (isGranted) {
                                    Icon(
                                        Icons.Filled.Check,
                                        contentDescription = null,
                                        tint = GhColors.Ok,
                                        modifier = Modifier.size(14.dp),
                                    )
                                    Spacer(Modifier.width(4.dp))
                                }
                                Text(
                                    text = stringResource(
                                        if (isGranted) CoreR.string.guide_allowed else CoreR.string.guide_allow,
                                    ),
                                    fontSize = 13.sp,
                                    color = if (isGranted) GhColors.TextTertiary else GhColors.TextPrimary,
                                )
                            }
                        }
                    }
                }
            }
        }

        // 操作区：全部允许 / 稍后设置
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 18.dp, vertical = 22.dp),
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            GhPillButton(
                text = stringResource(CoreR.string.guide_allow_all),
                icon = null,
                container = GhColors.Primary,
                content = Color.White,
                onClick = {
                    fun isPermGranted(pp: String) = ContextCompat.checkSelfPermission(context, pp) == PackageManager.PERMISSION_GRANTED
                    val pending = rows.flatMap { it.perms }.filterNot { isPermGranted(it) }.toTypedArray()
                    if (pending.isEmpty()) {
                        onDone()
                    } else {
                        launcher.launch(pending)
                    }
                },
                modifier = Modifier.weight(1f),
            )
            GhPillButton(
                text = stringResource(CoreR.string.guide_later),
                icon = null,
                container = GhColors.Surface,
                content = GhColors.TextPrimary,
                border = BorderStroke(1.dp, GhColors.Line),
                onClick = onDone,
                modifier = Modifier.weight(1f),
            )
        }
    }
}