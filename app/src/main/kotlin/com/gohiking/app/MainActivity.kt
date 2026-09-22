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
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.Image
import androidx.compose.material.icons.filled.Landscape
import androidx.compose.material.icons.filled.Layers
import androidx.compose.material.icons.filled.MyLocation
import androidx.compose.material.icons.filled.LocationOn
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Route
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.Button
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
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
import androidx.compose.ui.unit.Dp
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
import com.amap.api.maps.LocationSource
import com.amap.api.maps.MapView
import com.amap.api.maps.MapsInitializer
import com.amap.api.maps.model.MyLocationStyle
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
import com.gohiking.core.common.format.Formatters
import com.gohiking.core.common.geo.PolylineJson
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
import com.gohiking.feature.plan.PlanDetailScreen
import com.gohiking.feature.plan.PlanWizardScreen
import com.gohiking.feature.plan.PlanListScreen
import com.gohiking.feature.plan.PlanScreen
import com.gohiking.feature.recording.RecordingScreen
import com.gohiking.feature.settings.IoActions
import com.gohiking.feature.settings.AboutScreen
import com.gohiking.feature.settings.SettingsScreen
import dagger.hilt.android.AndroidEntryPoint
import javax.inject.Inject
import kotlinx.coroutines.Job
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withTimeoutOrNull
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
    var openTripId by rememberSaveable { mutableStateOf<String?>(null) }
    var showPlan by rememberSaveable { mutableStateOf(false) }
    var showPhotoMap by rememberSaveable { mutableStateOf(false) }
    // P-02/07/10/14 底部 Tab 导航（原型 tabbar）
    var currentTab by rememberSaveable { mutableStateOf("map") } // map / plan / history / me
    var showPermGuide by rememberSaveable { mutableStateOf(false) }
    var showAbout by rememberSaveable { mutableStateOf(false) } // P-15
    var showPlanDetailId by rememberSaveable { mutableStateOf<String?>(null) } // 需求③：计划详情编辑
    var showWizard by rememberSaveable { mutableStateOf(false) } // 新建计划向导
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
            showPlanDetailId != null -> showPlanDetailId = null
            showWizard -> showWizard = false
            showPlan -> showPlan = false
            showPhotoMap -> showPhotoMap = false
            showAbout -> showAbout = false
            currentTab != "map" -> currentTab = "map" // Tab 页返回 → 先回地图 Tab
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
    } else if (showWizard) {
        PlanWizardScreen(
            routeDao = plannedRouteDao,
            routeClient = routeClient,
            searchClient = searchClient,
            locationProvider = locationProvider,
            elevationRepository = elevationRepository,
            onBack = { showWizard = false },
            onSaved = {
                showWizard = false
                currentTab = "plan"
            },
            modifier = modifier,
        )
    } else if (showPlanDetailId != null) {
        PlanDetailScreen(
            routeId = showPlanDetailId!!,
            routeDao = plannedRouteDao,
            routeClient = routeClient,
            elevationRepository = elevationRepository,
            onBack = { showPlanDetailId = null },
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
    } else if (showAbout) {
        AboutScreen(onBack = { showAbout = false }, modifier = modifier)
    } else {
        // 主 Tab 页（P-02/07/10/14）：底部 tabbar 常驻（原型 tabbar）
        Box(modifier.fillMaxSize()) {
            when (currentTab) {
                "plan" -> PlanListScreen(
                    plannedRouteDao = plannedRouteDao,
                    onNewRoute = { showWizard = true }, // P-07「+」→ 新建计划向导
                    onOpenPlan = { showPlanDetailId = it }, // 需求③：条目 → 详情编辑
                    onExportRoute = { routeId, routeName ->
                        pendingRouteExport = routeId
                        routeExportDoc.launch(FileNamer.routeFileName(routeName, System.currentTimeMillis()))
                    },
                    extraBottomPadding = TAB_BAR_HEIGHT,
                    modifier = Modifier.fillMaxSize(),
                )
                "history" -> HistoryListScreen(
                    tripRepository = tripRepository,
                    onOpenTrip = { openTripId = it },
                    onStartRecording = { currentTab = "map" }, // P-10 空态 → 回地图 Tab
                    extraBottomPadding = TAB_BAR_HEIGHT,
                    modifier = Modifier.fillMaxSize(),
                )
                "me" -> SettingsScreen(
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
                    onOpenAbout = { showAbout = true },
                    extraBottomPadding = TAB_BAR_HEIGHT,
                    modifier = Modifier.fillMaxSize(),
                )
                else -> MapVerifyScreen(
                    locationProvider = locationProvider,
                    plannedRouteDao = plannedRouteDao,
                    session = session,
                    bottomInset = TAB_BAR_HEIGHT,
                    onOpenPlan = { showWizard = true }, // P-02 搜索条 → 向导步骤①
                    onOpenPlanTab = { currentTab = "plan" },
                    onOpenPhotoMap = { showPhotoMap = true },
                    modifier = Modifier.fillMaxSize(),
                )
            }
            MainTabBar(
                current = currentTab,
                onSelect = { currentTab = it },
                modifier = Modifier.align(Alignment.BottomCenter),
            )
        }
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
    onOpenPlan: () -> Unit = {},
    onOpenPlanTab: () -> Unit = {}, // P-02 homebar「计划线路」→ 计划 Tab（原型 data-go=p07）
    onOpenPhotoMap: () -> Unit = {}, // P-12 照片地图（M4-B2）
    locationProvider: LocationProvider, // P-02 定位按钮/蓝点：复用全局 SwitchingLocationProvider
    plannedRouteDao: PlannedRouteDao, // 开始记录弹窗：计划列表 + 最近推荐
    bottomInset: Dp = 0.dp, // Tab 模式：底部 tabbar 高度（homebar/chip 抬升）
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val lifecycleOwner = LocalLifecycleOwner.current

    // P-02：左下角提示 chip 复用为最近一条地图事件（保留 M0 点击冲突回归观测点）
    var lastEvent by remember { mutableStateOf<String?>(null) }
    var mapType by remember { mutableStateOf(AMap.MAP_TYPE_NORMAL) } // P-02 图层：普通/卫星/夜间/导航
    var showLayerMenu by remember { mutableStateOf(false) }
    var locating by remember { mutableStateOf(false) }
    var myLocOn by remember { mutableStateOf(false) } // 蓝点图层开关（定位授权后常显）
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
            // 需求 1：当前位置蓝点（高德 App 同款：蓝点 + 白边 + 淡蓝精度圈 + 方向旋转），
            // 定位源接全局 LocationProvider，避免另起一条 AMap 定位链（合规/省电双重考虑）
            map.setLocationSource(ProviderLocationSource(locationProvider, scope))
            map.myLocationStyle = MyLocationStyle().apply {
                myLocationType(MyLocationStyle.LOCATION_TYPE_LOCATION_ROTATE_NO_CENTER) // 蓝点常显+方向，不自动挪图
                radiusFillColor(android.graphics.Color.argb(18, 47, 128, 237))
                strokeColor(android.graphics.Color.argb(70, 47, 128, 237))
                strokeWidth(2f)
            }
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
    // 蓝点图层随页面存亡（已授权才开；记录页覆盖首页时自动随 dispose 关闭，定位让给会话）
    DisposableEffect(Unit) {
        if (locationGranted()) {
            runCatching { mapView.map.isMyLocationEnabled = true }
                .onFailure { Timber.w(it, "isMyLocationEnabled 失败") }
            myLocOn = true
        }
        onDispose {
            runCatching { mapView.map.isMyLocationEnabled = false }
            myLocOn = false
        }
    }

    // 定位权限（F-REC-53 首层：FINE+COARSE；BACKGROUND 在开始记录引导，M1 下一批）
    // 需求 2：开始记录弹窗的待启动参数（权限通过后回填启动）
    var pendingPlanId by rememberSaveable { mutableStateOf<String?>(null) } // null = 随意记录
    var pendingStartName by rememberSaveable { mutableStateOf("") }

    fun doStart() {
        session.start(pendingStartName.trim(), plannedRouteId = pendingPlanId) // 空名 → stop() 日期时间命名（F-REC-08）
        pendingPlanId = null
        pendingStartName = ""
        pendingServiceStart = true // H-05：延后到 ON_RESUME 之后
    }

    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions(),
    ) { grants ->
        val granted = grants[Manifest.permission.ACCESS_FINE_LOCATION] == true ||
            grants[Manifest.permission.ACCESS_COARSE_LOCATION] == true
        if (granted) {
            doStart()
        } else {
            showPermissionHint = true
        }
    }

    fun launchStart(planId: String?, name: String) {
        pendingPlanId = planId
        pendingStartName = name
        if (locationGranted()) {
            doStart()
        } else {
            val permissions = buildList {
                add(Manifest.permission.ACCESS_FINE_LOCATION)
                add(Manifest.permission.ACCESS_COARSE_LOCATION)
                if (Build.VERSION.SDK_INT >= 33) add(Manifest.permission.POST_NOTIFICATIONS)
                // N-28：API 29+ 起 ACTIVITY_RECOGNITION 是运行时权限，未授权时计步源会被「选中」但收不到数据
                if (Build.VERSION.SDK_INT >= 29) add(Manifest.permission.ACTIVITY_RECOGNITION)
            }.toTypedArray()
            permissionLauncher.launch(permissions)
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
            Box {
                MapButton(icon = Icons.Filled.Layers, contentDescriptionRes = CoreR.string.common_layer) {
                    showLayerMenu = true
                }
                DropdownMenu(expanded = showLayerMenu, onDismissRequest = { showLayerMenu = false }) {
                    DropdownMenuItem(
                        text = { Text(stringResource(CoreR.string.map_layer_normal)) },
                        onClick = {
                            mapType = AMap.MAP_TYPE_NORMAL
                            mapView.map.mapType = AMap.MAP_TYPE_NORMAL
                            showLayerMenu = false
                        },
                    )
                    DropdownMenuItem(
                        text = { Text(stringResource(CoreR.string.map_layer_satellite)) },
                        onClick = {
                            mapType = AMap.MAP_TYPE_SATELLITE
                            mapView.map.mapType = AMap.MAP_TYPE_SATELLITE
                            showLayerMenu = false
                        },
                    )
                    DropdownMenuItem(
                        text = { Text(stringResource(CoreR.string.map_layer_night)) },
                        onClick = {
                            mapType = AMap.MAP_TYPE_NIGHT
                            mapView.map.mapType = AMap.MAP_TYPE_NIGHT
                            showLayerMenu = false
                        },
                    )
                    DropdownMenuItem(
                        text = { Text(stringResource(CoreR.string.map_layer_navi)) },
                        onClick = {
                            mapType = AMap.MAP_TYPE_NAVI
                            mapView.map.mapType = AMap.MAP_TYPE_NAVI
                            showLayerMenu = false
                        },
                    )
                }
            }
            MapButton(icon = Icons.Filled.Add, contentDescriptionRes = CoreR.string.common_zoom_in) {
                mapView.map.moveCamera(CameraUpdateFactory.zoomIn())
            }
            MapButton(icon = Icons.Filled.MyLocation, contentDescriptionRes = CoreR.string.common_locate) {
                if (!locationGranted()) {
                    showPermissionHint = true
                } else if (!locating) {
                    // P-02：定位按钮 = 居中到当前位置（蓝点常显；蓝点未开时临时起一次定位）
                    locating = true
                    Toast.makeText(context, context.getString(CoreR.string.home_locating), Toast.LENGTH_SHORT).show()
                    scope.launch {
                        val fix = try {
                            if (!myLocOn) locationProvider.start(1000)
                            withTimeoutOrNull(8_000) { locationProvider.fixes.first() }
                        } catch (c: kotlinx.coroutines.CancellationException) {
                            throw c
                        } catch (t: Throwable) {
                            null
                        }
                        if (!myLocOn) runCatching { locationProvider.stop() }
                        locating = false
                        if (fix != null) {
                            mapView.map.moveCamera(
                                CameraUpdateFactory.newLatLngZoom(LatLng(fix.lat, fix.lng), 15f),
                            )
                        } else {
                            Toast.makeText(context, context.getString(CoreR.string.home_locate_failed), Toast.LENGTH_SHORT).show()
                        }
                    }
                }
            }
        }

        // ---- P-02 左下：提示 chip（有事件时显示最近事件）----
        Box(
            modifier = Modifier
                .align(Alignment.BottomStart)
                .navigationBarsPadding()
                .padding(start = 12.dp, bottom = 72.dp + bottomInset)
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
                .padding(start = 12.dp, end = 12.dp, bottom = 12.dp + bottomInset),
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            GhPillButton(
                text = stringResource(CoreR.string.plan_entry),
                icon = Icons.Filled.Route,
                container = GhColors.Surface,
                content = GhColors.TextPrimary,
                border = BorderStroke(1.dp, GhColors.Line),
                onClick = onOpenPlanTab, // 原型 data-go="p07"：计划线路列表（Tab 模式切 Tab）
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

    // 需求 2：开始记录 = 列出计划（按定位推荐最近）或随意记录（F-REC-02 命名保留）
    if (showStartDialog) {
        val savedPlans by plannedRouteDao.observeAllWithLegs()
            .collectAsStateWithLifecycle(initialValue = emptyList())
        var selectedPlanId by rememberSaveable { mutableStateOf<String?>(null) } // null = 随意记录
        var defaultPlanId by rememberSaveable { mutableStateOf<String?>(null) }
        var nameInput by rememberSaveable { mutableStateOf("") }
        var planDistances by remember { mutableStateOf<Map<String, Float>>(emptyMap()) }
        var locatingPlan by remember { mutableStateOf(false) }

        // 打开弹窗：已授权则取一次定位，为每个计划算「outbound 首点距您距离」，最近者默认选中
        LaunchedEffect(showStartDialog, savedPlans.size) {
            if (!showStartDialog || !locationGranted() || locatingPlan) return@LaunchedEffect
            locatingPlan = true
            val fix = try {
                if (!myLocOn) locationProvider.start(1000)
                withTimeoutOrNull(6_000) { locationProvider.fixes.first() }
            } catch (c: kotlinx.coroutines.CancellationException) {
                throw c
            } catch (t: Throwable) {
                null
            }
            if (fix != null && savedPlans.isNotEmpty()) {
                val dists = savedPlans.associate { plan ->
                    plan.route.id to nearestDistanceMeters(fix, plan)
                }
                planDistances = dists
                defaultPlanId = dists.minByOrNull { it.value }?.key
                selectedPlanId = defaultPlanId
                defaultPlanId?.let { pid ->
                    savedPlans.firstOrNull { it.route.id == pid }?.let { nameInput = it.route.name }
                }
            }
            if (!myLocOn) runCatching { locationProvider.stop() } // 蓝点没开时回收临时定位
            locatingPlan = false
        }

        AlertDialog(
            onDismissRequest = { showStartDialog = false },
            title = { Text(stringResource(CoreR.string.rec_start_dialog_title)) },
            text = {
                Column(Modifier.verticalScroll(rememberScrollState())) {
                    Text(
                        text = stringResource(CoreR.string.rec_start_pick_plan),
                        fontSize = 12.sp,
                        color = GhColors.TextSecondary,
                    )
                    Spacer(Modifier.height(6.dp))
                    if (savedPlans.isEmpty()) {
                        Text(
                            text = stringResource(CoreR.string.rec_start_plans_empty),
                            fontSize = 12.sp,
                            color = GhColors.TextSecondary,
                        )
                    } else {
                        if (locatingPlan) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                CircularProgressIndicator(modifier = Modifier.size(14.dp), strokeWidth = 2.dp)
                                Spacer(Modifier.width(6.dp))
                                Text(
                                    text = stringResource(CoreR.string.rec_start_locating_plan),
                                    fontSize = 11.sp,
                                    color = GhColors.TextSecondary,
                                )
                            }
                            Spacer(Modifier.height(4.dp))
                        }
                        PlanPickRow(
                            title = stringResource(CoreR.string.rec_start_free),
                            subtitle = null,
                            badge = null,
                            selected = selectedPlanId == null,
                            onClick = {
                                selectedPlanId = null
                                nameInput = ""
                            },
                        )
                        savedPlans.forEach { plan ->
                            val dist = planDistances[plan.route.id]
                            PlanPickRow(
                                title = plan.route.name,
                                subtitle = Formatters.distanceText(plan.route.totalDistanceM),
                                badge = when {
                                    plan.route.id == defaultPlanId -> stringResource(CoreR.string.rec_start_nearest)
                                    dist != null -> stringResource(CoreR.string.rec_start_distance, Formatters.distanceText(dist.toDouble()))
                                    else -> null
                                },
                                selected = selectedPlanId == plan.route.id,
                                onClick = {
                                    selectedPlanId = plan.route.id
                                    nameInput = plan.route.name // 关联计划默认以计划命名（可改）
                                },
                            )
                        }
                    }
                    Spacer(Modifier.height(8.dp))
                    OutlinedTextField(
                        value = nameInput,
                        onValueChange = { nameInput = it },
                        placeholder = { Text(stringResource(CoreR.string.rec_start_name_hint)) },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    showStartDialog = false
                    launchStart(selectedPlanId, nameInput)
                }) { Text(stringResource(CoreR.string.common_action_confirm)) }
            },
            dismissButton = {
                TextButton(onClick = { showStartDialog = false }) {
                    Text(stringResource(CoreR.string.common_action_cancel))
                }
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

/** Tab 高度（内容 56dp + 视觉余量），主 Tab 页按此值抬高底部内容。 */
private val TAB_BAR_HEIGHT = 64.dp

/**
 * P-02/07/10/14 底部 Tab 导航（原型 tabbar：地图/计划/记录/我的）。
 * 仅主 Tab 页显示；子页面（选点/详情/照片地图/关于）不显示。
 */
@Composable
private fun MainTabBar(
    current: String,
    onSelect: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    val tabs = listOf(
        Triple("map", Icons.Filled.LocationOn, CoreR.string.common_tab_map),
        Triple("plan", Icons.Filled.Route, CoreR.string.common_tab_plan),
        Triple("history", Icons.Filled.History, CoreR.string.common_tab_history),
        Triple("me", Icons.Filled.Person, CoreR.string.common_tab_me),
    )
    Surface(color = GhColors.Surface, modifier = modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .navigationBarsPadding()
                .height(56.dp),
        ) {
            tabs.forEach { (key, icon, labelRes) ->
                val selected = current == key
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.Center,
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxSize()
                        .clickable { onSelect(key) },
                ) {
                    Icon(
                        icon,
                        contentDescription = stringResource(labelRes),
                        tint = if (selected) GhColors.Primary else GhColors.TextTertiary,
                        modifier = Modifier.size(22.dp),
                    )
                    Spacer(Modifier.height(2.dp))
                    Text(
                        text = stringResource(labelRes),
                        fontSize = 10.sp,
                        color = if (selected) GhColors.Primary else GhColors.TextTertiary,
                        maxLines = 1,
                    )
                }
            }
        }
    }
}

/** 需求 2：开始记录弹窗的行（计划 / 随意记录），选中态蓝底。 */
@Composable
private fun PlanPickRow(
    title: String,
    subtitle: String?,
    badge: String?,
    selected: Boolean,
    onClick: () -> Unit,
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(10.dp))
            .background(if (selected) GhColors.TagBlueBg else Color.Transparent)
            .clickable(onClick = onClick)
            .padding(horizontal = 10.dp, vertical = 9.dp),
    ) {
        RadioButton(selected = selected, onClick = null)
        Spacer(Modifier.width(8.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = title,
                fontSize = 14.sp,
                fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Medium,
                maxLines = 1,
            )
            if (subtitle != null) {
                Text(
                    text = subtitle,
                    fontSize = 11.sp,
                    color = GhColors.TextSecondary,
                    maxLines = 1,
                )
            }
        }
        if (badge != null) {
            Box(
                modifier = Modifier
                    .clip(RoundedCornerShape(6.dp))
                    .background(GhColors.TagBlueBg)
                    .padding(horizontal = 6.dp, vertical = 2.dp),
            ) {
                Text(text = badge, fontSize = 10.sp, color = GhColors.TagBlueFg)
            }
        }
    }
}

/** 需求 2：计划 outbound 首点到当前位置的距离（推荐「最近」计划的依据）。 */
private fun nearestDistanceMeters(
    fix: com.gohiking.core.location.LocationFix,
    plan: com.gohiking.core.database.entity.PlannedRouteWithLegs,
): Float {
    val leg = plan.legs.firstOrNull { it.legType == "OUTBOUND" } ?: plan.legs.firstOrNull()
    val first = leg?.let { leg ->
        runCatching { PolylineJson.decode(leg.polylineJson) }.getOrNull()
    }?.firstOrNull() ?: return Float.MAX_VALUE
    return distanceMeters(fix.lat, fix.lng, first.latitude, first.longitude).toFloat()
}

/** Haversine 球面距离（米）。GCJ-02 同系坐标直接算，误差远小于「最近」判据粒度。 */
private fun distanceMeters(lat1: Double, lng1: Double, lat2: Double, lng2: Double): Double {
    val r = 6371000.0
    val p1 = Math.toRadians(lat1)
    val p2 = Math.toRadians(lat2)
    val dp = Math.toRadians(lat2 - lat1)
    val dl = Math.toRadians(lng2 - lng1)
    val a = Math.pow(Math.sin(dp / 2), 2.0) + Math.cos(p1) * Math.cos(p2) * Math.pow(Math.sin(dl / 2), 2.0)
    return 2 * r * Math.asin(Math.sqrt(a))
}

/**
 * 需求 1：把全局 LocationProvider 流桥接到高德「我的位置」图层——
 * 蓝点/精度圈样式由 MyLocationStyle 承担（高德 App 同款），定位源只有一条链
 * （SwitchingLocationProvider），避免 SDK 默认源另起 AMapLocationClient 双份耗电。
 */
private class ProviderLocationSource(
    private val provider: LocationProvider,
    private val scope: kotlinx.coroutines.CoroutineScope,
) : LocationSource {
    private var listener: LocationSource.OnLocationChangedListener? = null
    private var job: kotlinx.coroutines.Job? = null

    override fun activate(l: LocationSource.OnLocationChangedListener?) {
        listener = l
        job?.cancel()
        job = scope.launch {
            runCatching { provider.start(1000) }
            provider.fixes.collect { fix ->
                listener?.onLocationChanged(fix.toAndroidLocation())
            }
        }
    }

    override fun deactivate() {
        job?.cancel()
        job = null
        listener = null
        runCatching { provider.stop() }
    }
}

private fun com.gohiking.core.location.LocationFix.toAndroidLocation(): android.location.Location =
    android.location.Location("GoHiking").apply {
        latitude = this@toAndroidLocation.lat
        longitude = this@toAndroidLocation.lng
        altitude = this@toAndroidLocation.altitudeM ?: 0.0
        accuracy = this@toAndroidLocation.accuracyM
        bearing = this@toAndroidLocation.bearing
        speed = this@toAndroidLocation.speedMps
        time = this@toAndroidLocation.timestampMs
    }
