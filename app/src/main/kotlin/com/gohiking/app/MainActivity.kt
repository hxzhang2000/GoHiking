package com.gohiking.app

import android.Manifest
import android.app.Activity
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
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
import com.gohiking.core.data.recording.RecordingService
import com.gohiking.core.data.recording.RecordingSession
import com.gohiking.core.data.recording.SessionState
import com.gohiking.core.data.repository.TripRepository
import com.gohiking.core.designsystem.theme.GhTheme
import com.gohiking.core.location.LocationProvider
import com.gohiking.core.map.search.AmapSearchClient
import com.gohiking.core.resources.R as CoreR
import com.gohiking.feature.history.HistoryListScreen
import com.gohiking.feature.history.TripDetailScreen
import com.gohiking.feature.plan.PlanScreen
import com.gohiking.feature.recording.RecordingScreen
import dagger.hilt.android.AndroidEntryPoint
import javax.inject.Inject

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

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            GhTheme {
                Root(session = session, tripRepository = tripRepository, locationProvider = locationProvider)
            }
        }
    }
}

@Composable
private fun Root(
    session: RecordingSession,
    tripRepository: TripRepository,
    locationProvider: LocationProvider,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    var agreed by rememberSaveable { mutableStateOf(false) }
    var showHistory by rememberSaveable { mutableStateOf(false) }
    var openTripId by rememberSaveable { mutableStateOf<String?>(null) }
    var showPlan by rememberSaveable { mutableStateOf(false) }
    val sessionState by session.state.collectAsStateWithLifecycle()
    val searchClient = remember { AmapSearchClient(context) }

    if (!agreed) {
        PrivacyGate(
            onAgree = { agreed = true },
            onDecline = { (context as? Activity)?.finish() },
        )
        return
    }

    if (sessionState !is SessionState.Idle) {
        RecordingScreen(session = session, tripRepository = tripRepository, modifier = modifier)
    } else if (openTripId != null) {
        TripDetailScreen(
            tripRepository = tripRepository,
            tripId = openTripId!!,
            onBack = { openTripId = null },
            onDeleted = { openTripId = null },
            modifier = modifier,
        )
    } else if (showPlan) {
        PlanScreen(
            searchClient = searchClient,
            locationProvider = locationProvider,
            onBack = { showPlan = false },
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
        )
    }
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
