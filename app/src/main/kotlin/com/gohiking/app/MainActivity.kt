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
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
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
import com.gohiking.core.data.io.IoRepository
import com.gohiking.core.data.io.ParsedFile
import com.gohiking.core.data.io.RoomImportSink
import com.gohiking.core.data.recording.RecordingService
import com.gohiking.core.data.recording.RecordingSession
import com.gohiking.core.data.recording.SessionState
import com.gohiking.core.data.repository.TripRepository
import com.gohiking.core.datastore.AppSettings
import com.gohiking.core.datastore.SettingsRepository
import com.gohiking.core.datastore.readLanguageBlocking
import com.gohiking.core.database.dao.PlannedRouteDao
import com.gohiking.core.elevation.ElevationRepository
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
import kotlinx.coroutines.launch

/**
 * M1 壳：①隐私同意门（DEV §1.5 红线：高德 SDK 必须在用户同意后才初始化）；
 * ②首页地图（P-02 雏形：地图 + 开始记录）；③记录页切换（session 状态驱动）。
 * 导航宿主在 M1 后续按 DEV §5.4 落地。
 */
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
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    var agreed by rememberSaveable { mutableStateOf(false) }
    var showHistory by rememberSaveable { mutableStateOf(false) }
    var openTripId by rememberSaveable { mutableStateOf<String?>(null) }
    var showPlan by rememberSaveable { mutableStateOf(false) }
    var showPlanList by rememberSaveable { mutableStateOf(false) }
    var showSettings by rememberSaveable { mutableStateOf(false) }
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
    var pendingImport by remember { mutableStateOf<Pair<List<ParsedFile>, List<String>>?>(null) }
    var importPreview by remember { mutableStateOf<com.gohiking.core.data.io.ImportEngine.ImportPreview?>(null) }
    var importPolicy by remember { mutableStateOf(ConflictPolicy.SKIP) }
    var importReport by remember { mutableStateOf<com.gohiking.core.data.io.ImportEngine.ImportReport?>(null) }
    val generator = GeneratorInfo(versionName = BuildConfig.VERSION_NAME, versionCode = BuildConfig.VERSION_CODE)

    fun ioRun(block: suspend () -> Unit) {
        ioJob = scope.launch {
            ioBusy = true
            try {
                block()
            } catch (e: CancellationException) {
                // 用户取消：已导入/已导出的保留（F-IO-11/29）
            } catch (t: Throwable) {
                ioErrorMsg = t.message ?: t::class.java.simpleName
            } finally {
                ioBusy = false
                ioProgress = null
            }
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
                    ?: error("无法在所选目录创建文件：$fileName")
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
            } ?: error("无法写入所选文件")
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
                val name = queryDisplayName(resolver, uri) ?: uri.lastPathSegment ?: "unknown"
                val bytes = resolver.openInputStream(uri)?.use { it.readBytes() }
                    ?: error("无法打开：$name")
                name to bytes
            }
            val parsed = ioRepository.parseImportSources(sources)
            pendingImport = parsed.files to parsed.warnings
            importPreview = ioRepository.preview(parsed.files)
        }
    }

    if (!agreed) {
        PrivacyGate(
            onAgree = { agreed = true },
            onDecline = { (context as? Activity)?.finish() },
        )
        return
    }

    if (sessionState !is SessionState.Idle) {
        RecordingScreen(
            session = session,
            tripRepository = tripRepository,
            plannedRouteDao = plannedRouteDao,
            modifier = modifier,
        )
    } else if (openTripId != null) {
        TripDetailScreen(
            tripRepository = tripRepository,
            tripId = openTripId!!,
            onBack = { openTripId = null },
            onDeleted = { openTripId = null },
            modifier = modifier,
        )
    } else if (showPlanList) {
        PlanListScreen(
            plannedRouteDao = plannedRouteDao,
            onBack = { showPlanList = false },
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
            onConfirm = {
                val files = pendingImport?.first ?: return@ImportPreviewDialog
                importPreview = null
                ioRun {
                    val report = ioRepository.executeImport(files, importPolicy) { done, total ->
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
            title = { Text(stringResource(CoreR.string.io_working)) },
            text = { Text(stringResource(CoreR.string.io_done, n)) },
            confirmButton = {
                TextButton(onClick = { ioDoneMsg = null }) { Text(stringResource(CoreR.string.common_action_confirm)) }
            },
        )
    }
    ioErrorMsg?.let { msg ->
        AlertDialog(
            onDismissRequest = { ioErrorMsg = null },
            title = { Text(stringResource(CoreR.string.io_error)) },
            text = { Text(msg) }, // F-IO-34：给出具体原因
            confirmButton = {
                TextButton(onClick = { ioErrorMsg = null }) { Text(stringResource(CoreR.string.common_action_confirm)) }
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
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current

    // 事件日志（新事件插到最前），最多保留 6 条
    val events = remember { mutableStateListOf<String>() }
    var isChinese by rememberSaveable { mutableStateOf(true) }
    var lastPoiAtMs by remember { mutableLongStateOf(0L) }
    var lastMapClickAtMs by remember { mutableLongStateOf(0L) }
    var showStartDialog by rememberSaveable { mutableStateOf(false) } // F-REC-02：开始前可选命名
    var startName by rememberSaveable { mutableStateOf("") }
    var locationGranted by remember {
        mutableStateOf(
            ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION) ==
                PackageManager.PERMISSION_GRANTED,
        )
    }
    var showPermissionHint by remember { mutableStateOf(false) }

    fun log(text: String) {
        events.add(0, text)
        if (events.size > 6) events.removeAt(events.lastIndex)
    }

    // 隐私合规必须【早于 MapView 创建】调用（3D SDK ≥8.1.0 强制，否则白屏不渲染瓦片）。
    // 放在 remember 里恰好保证：①先于地图创建；②进程内只调一次；③rememberSaveable 记住同意状态的
    // 二次启动也覆盖（此时不走弹窗，但「已同意」状态成立，红线只要求同意后调用）。
    val mapView = remember {
        MapsInitializer.updatePrivacyShow(context, true, true)
        MapsInitializer.updatePrivacyAgree(context, true)
        MapView(context).apply {
            onCreate(null)
            map.uiSettings.isZoomControlsEnabled = true
            // 默认视角：中国全域；定位到当前位置由记录页/蓝点接入后处理
            map.moveCamera(CameraUpdateFactory.newLatLngZoom(LatLng(35.86, 104.19), 3.8f))

            // M0 实测②：POI 点击时 OnMapClickListener 是否同触发（已真机通过 2026-09-20，保留供回归）
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
                log(context.getString(CoreR.string.map_test_log_poi, poi.name ?: "?"))
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
            mapView.onDestroy()
        }
    }

    // 定位权限（F-REC-53 首层：FINE+COARSE；BACKGROUND 在开始记录引导，M1 下一批）
    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions(),
    ) { grants ->
        locationGranted = grants[Manifest.permission.ACCESS_FINE_LOCATION] == true ||
            grants[Manifest.permission.ACCESS_COARSE_LOCATION] == true
        if (locationGranted) {
            session.start(startName.trim(), plannedRouteId = null) // 空名 → stop() 日期时间命名（F-REC-08）
            startName = ""
            RecordingService.start(context)
        } else {
            showPermissionHint = true
        }
    }

    Box(modifier = modifier.fillMaxSize()) {
        AndroidView(factory = { mapView }, modifier = Modifier.fillMaxSize())

        Surface(
            tonalElevation = 4.dp,
            shadowElevation = 2.dp,
            shape = RoundedCornerShape(16.dp),
            modifier = Modifier
                .align(Alignment.TopCenter)
                .padding(top = 48.dp),
        ) {
            Text(
                text = stringResource(CoreR.string.map_verify_badge),
                modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
            )
        }

        Column(
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .fillMaxWidth()
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Surface(
                tonalElevation = 4.dp,
                shadowElevation = 2.dp,
                shape = RoundedCornerShape(12.dp),
                modifier = Modifier.fillMaxWidth(),
            ) {
                Column(modifier = Modifier.padding(12.dp)) {
                    Text(stringResource(CoreR.string.map_test_hint), modifier = Modifier.padding(bottom = 4.dp))
                    events.forEachIndexed { index, line ->
                        Text(
                            text = line,
                            modifier = Modifier.padding(top = if (index == 0) 4.dp else 0.dp),
                        )
                    }
                }
            }
            Button(
                onClick = { showStartDialog = true }, // F-REC-02：先弹可选命名，不阻塞
                modifier = Modifier.align(Alignment.CenterHorizontally),
            ) {
                Text(stringResource(CoreR.string.home_start_record))
            }
            Button(
                onClick = {
                    isChinese = !isChinese
                    // M0 实测①：setMapLanguage 真机已验证可切换（2026-09-20），保留供回归
                    mapView.map.setMapLanguage(if (isChinese) AMap.CHINESE else AMap.ENGLISH)
                    log(context.getString(CoreR.string.map_test_log_language))
                },
                modifier = Modifier.align(Alignment.CenterHorizontally),
            ) {
                Text(stringResource(CoreR.string.map_test_switch_language))
            }
            Button(
                onClick = onOpenHistory,
                modifier = Modifier.align(Alignment.CenterHorizontally),
            ) {
                Text(stringResource(CoreR.string.common_tab_history))
            }
            Button(
                onClick = onOpenPlan,
                modifier = Modifier.align(Alignment.CenterHorizontally),
            ) {
                Text(stringResource(CoreR.string.plan_entry))
            }
            Button(
                onClick = onOpenPlanList, // F-PLAN-41 计划列表管理
                modifier = Modifier.align(Alignment.CenterHorizontally),
            ) {
                Text(stringResource(CoreR.string.plan_list_title))
            }
            Button(
                onClick = onOpenSettings, // P-14 设置页（M3-C）
                modifier = Modifier.align(Alignment.CenterHorizontally),
            ) {
                Text(stringResource(CoreR.string.set_title))
            }
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
