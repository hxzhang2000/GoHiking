package com.gohiking.feature.recording

import android.app.Activity
import android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.amap.api.maps.CameraUpdateFactory
import com.amap.api.maps.MapView
import com.amap.api.maps.MapsInitializer
import com.amap.api.maps.model.LatLng
import com.amap.api.maps.model.Polyline
import com.amap.api.maps.model.PolylineOptions
import com.gohiking.core.common.format.Formatters
import com.gohiking.core.common.geo.PolylineJson
import com.gohiking.core.map.overlay.PolylineArrowTexture
import com.gohiking.core.data.alert.AlertVoice
import com.gohiking.core.data.recording.RecordingSession
import com.gohiking.core.data.recording.SessionState
import com.gohiking.core.data.recording.TripDraft
import com.gohiking.core.data.repository.TripRepository
import com.gohiking.core.database.dao.PlannedRouteDao
import com.gohiking.core.resources.R as CoreR
import kotlinx.coroutines.launch

/**
 * 记录页（M1）：
 * - F-REC-03 地图实时红色轨迹（#E24B4A / 8dp，quality==0 与统计口径一致；暂停即新 segment 不连线）；
 * - F-REC-16 数据每秒刷新（tickJob 驱动 state）；F-REC-17 暂停面板变灰 + 浮层；
 * - F-REC-06/07 停止长按 1.5 秒 → 保存确认弹窗（显示距离/时长/爬升/步数，保存 / 丢弃）；
 * - F-REC-18 屏幕常亮（开关属 F-SET，M3 设置页接入）；
 * - 状态唯一来源是 [RecordingSession]（DEV 冻结决策 12）。
 */
private const val STOP_HOLD_MS = 1500L
private val TRACK_RED = 0xFFE24B4A.toInt() // PRD F-REC-03 权威色值
private val PLANNED_BLUE = 0xFF2F80ED.toInt() // F-PLAN-44 计划线路蓝（同 PlanScreen 选中色）

@Composable
fun RecordingScreen(
    session: RecordingSession,
    tripRepository: TripRepository,
    plannedRouteDao: PlannedRouteDao,
    modifier: Modifier = Modifier,
) {
    val state by session.state.collectAsStateWithLifecycle()
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    var pendingDraft by remember { mutableStateOf<TripDraft?>(null) }
    var saving by remember { mutableStateOf(false) }
    var saveFailed by remember { mutableStateOf(false) }
    var showMarkerDialog by remember { mutableStateOf(false) }
    var markerNote by remember { mutableStateOf("") }
    var holdingStop by remember { mutableStateOf(false) }
    var showPlanPicker by remember { mutableStateOf(false) }
    val savedPlans by plannedRouteDao.observeAllWithLegs().collectAsStateWithLifecycle(initialValue = emptyList())
    val ttsSpeaker = remember { TtsSpeaker(context) }

    val active = state as? SessionState.Active
    // C-01：draft 优先用显式 pendingDraft，缺失时回落到会话的 Finished 状态，
    // 这样旋转/Activity 重建后确认弹窗仍在，不会出现「state=Finished 但界面空白」的死界面。
    val draftToConfirm = pendingDraft ?: (state as? SessionState.Finished)?.draft

    // F-REC-18：记录过程屏幕常亮。
    // N-26：此前无条件 addFlags，设置页的「屏幕常亮」开关（P0）完全不生效。
    // 改为跟随设置；设置变更时即时生效（关掉立刻允许熄屏，省电）。
    val keepScreenOn by session.keepScreenOn.collectAsStateWithLifecycle()
    DisposableEffect(keepScreenOn) {
        val window = (context as? Activity)?.window
        if (keepScreenOn) window?.addFlags(FLAG_KEEP_SCREEN_ON) else window?.clearFlags(FLAG_KEEP_SCREEN_ON)
        onDispose {
            (context as? Activity)?.window?.clearFlags(FLAG_KEEP_SCREEN_ON)
        }
    }

    // F-ALERT-42/43：TTS 播报（引擎不可用静默降级）；离开记录页释放引擎与音频焦点
    DisposableEffect(ttsSpeaker) {
        onDispose { ttsSpeaker.shutdown() }
    }
    // F-ALERT-28/40：播报文案取 strings_tts 模板（与屏幕文案分开，PRD 9.7.2），语言跟随 App
    LaunchedEffect(ttsSpeaker) {
        session.speakEvents.collect { voice ->
            val text = when (voice) {
                is AlertVoice.Distance -> context.getString(CoreR.string.tts_alert_distance, voice.meters)
                is AlertVoice.Ascent -> context.getString(CoreR.string.tts_alert_ascent, voice.meters, voice.altitudeM)
                is AlertVoice.Descent -> context.getString(CoreR.string.tts_alert_descent, voice.meters, voice.altitudeM)
            }
            ttsSpeaker.speak(text)
        }
    }

    // F-REC-03 地图：隐私红线同 M0——updatePrivacy 必须先于 MapView 创建（DEV §1.5）
    val trackWidthPx = with(LocalDensity.current) { 8.dp.toPx() }
    val mapView = remember {
        MapsInitializer.updatePrivacyShow(context, true, true)
        MapsInitializer.updatePrivacyAgree(context, true)
        MapView(context).apply {
            onCreate(null)
            map.uiSettings.isZoomControlsEnabled = false
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
            // N-19：从详情页/其他页返回时 Activity 并未 pause，直接 onDestroy() 会让
            // AMap 内部 GL 资源与监听器释放顺序错乱（地图黑屏/瓦片不刷新/偶发崩溃）。
            // 必须保证 pause → destroy 的调用顺序（与 MainActivity 的修法一致）。
            mapView.onPause()
            mapView.onDestroy()
        }
    }

    // 轨迹绘制：先拉历史点（崩溃恢复 / 重进页面 F-REC-09），再订阅实时采样。
    // 只画 quality==0 点——与统计口径一致，避免「画出的线与保存的数据不一样」。
    LaunchedEffect(active?.tripId) {
        val tripId = active?.tripId ?: return@LaunchedEffect
        val aMap = mapView.map
        aMap.clear()
        val polylines = HashMap<Int, Polyline>()
        fun appendPoint(seg: Int, ll: LatLng, follow: Boolean) {
            val existing = polylines[seg]
            if (existing == null) {
                polylines[seg] = aMap.addPolyline(
                    PolylineOptions().add(ll).width(trackWidthPx).color(TRACK_RED).zIndex(10f),
                )
            } else {
                val pts = ArrayList(existing.points)
                pts.add(ll)
                existing.points = pts
            }
            if (follow) aMap.moveCamera(CameraUpdateFactory.newLatLng(ll))
        }
        val history = tripRepository.pointsOf(tripId).filter { it.quality == 0 }
        history.forEach { p -> appendPoint(p.segmentIndex, LatLng(p.latitude, p.longitude), follow = false) }
        history.lastOrNull()?.let { last ->
            aMap.moveCamera(CameraUpdateFactory.newLatLngZoom(LatLng(last.latitude, last.longitude), 15f))
        }
        session.samples.collect { s ->
            if (s.quality == 0) appendPoint(s.segmentIndex, LatLng(s.lat, s.lng), follow = true)
        }
    }


    // F-PLAN-44：关联计划线路 → 蓝线（6dp）与红线同屏；取消/更换关联即重绘。
    // 不能 aMap.clear()（会清掉红色轨迹），只管理自己的 polyline 引用。
    val planWidthPx = with(LocalDensity.current) { 6.dp.toPx() }
    val planPolylines = remember { mutableListOf<Polyline>() }
    LaunchedEffect(active?.plannedRouteId) {
        val aMap = mapView.map
        planPolylines.forEach { it.remove() }
        planPolylines.clear()
        val routeId = active?.plannedRouteId ?: return@LaunchedEffect
        val withLegs = plannedRouteDao.withLegs(routeId) ?: return@LaunchedEffect
        withLegs.legs.forEach { leg ->
            val pts = PolylineJson.decode(leg.polylineJson).map { LatLng(it.latitude, it.longitude) }
            if (pts.size >= 2) {
                planPolylines += aMap.addPolyline(
                    PolylineOptions().addAll(pts).width(planWidthPx).color(PLANNED_BLUE).zIndex(5f),
                )
                planPolylines += aMap.addPolyline(
                    PolylineOptions().addAll(pts).width(planWidthPx)
                        .setCustomTexture(PolylineArrowTexture.forColor(PLANNED_BLUE)).zIndex(6f),
                )
            }
        }
    }

    Column(modifier = modifier.fillMaxSize()) {
        Box(
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth(),
        ) {
            AndroidView(factory = { mapView }, modifier = Modifier.fillMaxSize())
            // F-REC-17：暂停浮层
            if (active != null && !active.isRecording) {
                Surface(
                    tonalElevation = 6.dp,
                    shadowElevation = 4.dp,
                    shape = RoundedCornerShape(16.dp),
                    modifier = Modifier.align(Alignment.Center),
                ) {
                    Text(
                        text = stringResource(CoreR.string.rec_status_paused),
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                        modifier = Modifier.padding(horizontal = 20.dp, vertical = 10.dp),
                    )
                }
            }
        }

        Column(
            modifier = Modifier
                .fillMaxWidth()
                .background(MaterialTheme.colorScheme.background)
                // N-11：targetSdk 35 起强制 edge-to-edge，导航栏会盖住底部内容。这里的
                // 停止按钮是 1.5 秒长按手势，被遮挡后用户无法结束记录。背景延伸到导航栏、
                // 内容上移。
                .navigationBarsPadding()
                .padding(16.dp)
                .graphicsLayer { alpha = if (active?.isRecording != false) 1f else 0.45f }, // F-REC-17
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Text(
                text = stringResource(
                    if (active?.isRecording != false) CoreR.string.rec_status_recording else CoreR.string.rec_status_paused,
                ),
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.Bold,
            )
            if (active != null) {
                Text(
                    text = stringResource(
                        if (active.alertsEnabled) CoreR.string.rec_alert_on else CoreR.string.rec_alert_off,
                    ),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            if (active != null) {
                // F-PLAN-44：关联计划入口（显示当前关联，点击选择/更换/取消）
                TextButton(onClick = { showPlanPicker = true }) {
                    Text(
                        text = active.plannedRouteId?.let { id ->
                            stringResource(
                                CoreR.string.rec_plan_linked,
                                savedPlans.firstOrNull { it.route.id == id }?.route?.name ?: "",
                            )
                        } ?: stringResource(CoreR.string.rec_plan_associate),
                    )
                }

                listOf(
                    stringResource(CoreR.string.rec_stat_distance) to Formatters.distanceText(active.distanceM),
                    stringResource(CoreR.string.rec_stat_duration) to Formatters.durationText(active.movingDurationSec),
                    stringResource(CoreR.string.rec_stat_climb) to Formatters.metersText(active.ascentM),
                    stringResource(CoreR.string.rec_stat_altitude) to (active.currentAltitudeM?.let { Formatters.metersText(it) }
                        ?: stringResource(CoreR.string.common_stat_unknown)), // fuser 不可用显示「—」，绝不编造（PRD 6.1）
                    stringResource(CoreR.string.rec_stat_steps) to (if (active.stepCount >= 0) active.stepCount.toString() else stringResource(CoreR.string.common_stat_unknown)),
                    stringResource(CoreR.string.rec_stat_points) to active.pointCount.toString(),
                ).chunked(2).forEach { row ->
                    Row(modifier = Modifier.fillMaxWidth()) {
                        row.forEach { (label, value) ->
                            StatCell(label, value, Modifier.weight(1f))
                        }
                    }
                }

                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    Button(
                        onClick = { if (active.isRecording) session.pause() else session.resume() },
                        modifier = Modifier.weight(1f),
                    ) {
                        Text(
                            stringResource(
                                if (active.isRecording) CoreR.string.rec_action_pause else CoreR.string.rec_action_resume,
                            ),
                            maxLines = 1,
                        )
                    }
                    Button(
                        onClick = { session.markSummit(null) },
                        modifier = Modifier.weight(1f),
                        enabled = active.isRecording,
                    ) {
                        Text(stringResource(CoreR.string.rec_action_summit), maxLines = 1)
                    }
                    Button(
                        onClick = { showMarkerDialog = true },
                        modifier = Modifier.weight(1f),
                        enabled = active.isRecording,
                    ) {
                        Text(stringResource(CoreR.string.rec_action_marker), maxLines = 1)
                    }
                }

                // F-REC-06：停止需长按 1.5 秒，避免误触
                val errorColor = MaterialTheme.colorScheme.error
                val onErrorColor = MaterialTheme.colorScheme.onError
                val stopLabel = stringResource(CoreR.string.rec_action_stop)
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        // L-06：height(52.dp) 在字体放大/横屏时无伸缩余量，改最小高度
                        .heightIn(min = 52.dp)
                        .clip(RoundedCornerShape(20.dp))
                        .background(errorColor)
                        // L-15：停止只有「长按 1.5 秒」一条路径，TalkBack 用户完全无法触发。
                        // 补一个无障碍侧的等价动作（双击即停止），触摸行为保持不变。
                        .semantics {
                            role = Role.Button
                            contentDescription = stopLabel
                            onClick {
                                scope.launch { pendingDraft = session.stop() }
                                true
                            }
                        }
                        .pointerInput(Unit) {
                            awaitEachGesture {
                                awaitFirstDown(requireUnconsumed = false)
                                val start = System.currentTimeMillis()
                                var fired = false
                                // L-06：手势协程被取消（组合离开/手势打断）时必须复位，
                                // 否则按钮文案永久卡在「松手停止」
                                try {
                                    holdingStop = true
                                    while (true) {
                                        val event = awaitPointerEvent()
                                        if (!event.changes.any { it.pressed }) break
                                        if (!fired && System.currentTimeMillis() - start >= STOP_HOLD_MS) {
                                            fired = true
                                            scope.launch { pendingDraft = session.stop() }
                                        }
                                    }
                                } finally {
                                    holdingStop = false
                                }
                            }
                        },
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        text = stringResource(
                            if (holdingStop) CoreR.string.rec_stop_holding else CoreR.string.rec_stop_hint,
                        ),
                        color = onErrorColor,
                        style = MaterialTheme.typography.titleMedium,
                    )
                }
            }
        }
    }

    // F-REC-07：停止后保存确认，展示总距离 / 总时长 / 爬升 / 步数
    draftToConfirm?.let { draft ->
        val trip = draft.trip
        AlertDialog(
            onDismissRequest = { },
            title = { Text(stringResource(CoreR.string.rec_stop_confirm)) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(stringResource(CoreR.string.rec_stat_distance) + ": " + Formatters.distanceText(trip.distanceM))
                    Text(stringResource(CoreR.string.hist_overview_total_duration) + ": " + Formatters.durationText(trip.durationSec))
                    Text(stringResource(CoreR.string.rec_stat_climb) + ": " + Formatters.metersText(trip.totalAscentM))
                    Text(
                        stringResource(CoreR.string.rec_stat_steps) + ": " +
                            (if (trip.steps >= 0) trip.steps.toString() else stringResource(CoreR.string.common_stat_unknown)),
                    )
                    // C-01：保存失败时必须让用户看见，否则只会看到按钮悄悄失效
                    if (saveFailed) {
                        Text(
                            text = stringResource(CoreR.string.rec_save_failed),
                            color = MaterialTheme.colorScheme.error,
                            style = MaterialTheme.typography.bodyMedium,
                        )
                    }
                }
            },

            confirmButton = {
                TextButton(
                    onClick = {
                        scope.launch {
                            saving = true
                            // C-01：save() 失败时不许关弹窗，否则 state 停在 Finished 而 draft 已丢 → 死界面
                            val ok = session.save(draft)
                            saving = false
                            saveFailed = !ok
                            if (ok) pendingDraft = null
                        }
                    },
                    enabled = !saving,
                ) {
                    Text(
                        stringResource(
                            if (saveFailed) CoreR.string.common_action_retry else CoreR.string.common_action_save,
                        ),
                    )
                }
            },
            dismissButton = {
                TextButton(onClick = {
                    scope.launch {
                        session.discard(draft)
                        pendingDraft = null
                    }
                }) { Text(stringResource(CoreR.string.rec_action_discard)) }
            },
        )
    }

    // F-PLAN-44：计划线路选择弹窗（关联 / 更换 / 取消关联）
    if (showPlanPicker && active != null) {
        AlertDialog(
            onDismissRequest = { showPlanPicker = false },
            title = { Text(stringResource(CoreR.string.rec_plan_pick_title)) },
            text = {
                if (savedPlans.isEmpty()) {
                    Text(stringResource(CoreR.string.rec_plan_empty))
                } else {
                    Column(Modifier.verticalScroll(rememberScrollState())) {
                        savedPlans.forEach { item ->
                            TextButton(onClick = {
                                session.associatePlan(item.route.id, item.route.name)
                                showPlanPicker = false
                            }) {
                                Text(item.route.name, maxLines = 1)
                            }
                        }
                    }
                }
            },
            confirmButton = {},
            dismissButton = {
                Row {
                    if (active.plannedRouteId != null) {
                        TextButton(onClick = {
                            session.associatePlan(null, null)
                            showPlanPicker = false
                        }) { Text(stringResource(CoreR.string.rec_plan_unlink)) }
                    }
                    TextButton(onClick = { showPlanPicker = false }) {
                        Text(stringResource(CoreR.string.common_action_cancel))
                    }
                }
            },
        )
    }

    // F-REC-40/41：手动打点 + 备注输入（标记以橙色渲染在地图与详情页，F-REC-42）
    if (showMarkerDialog) {
        AlertDialog(
            onDismissRequest = {
                showMarkerDialog = false
                markerNote = ""
            },
            title = { Text(stringResource(CoreR.string.rec_marker_dialog_title)) },
            text = {
                OutlinedTextField(
                    value = markerNote,
                    onValueChange = { markerNote = it },
                    placeholder = { Text(stringResource(CoreR.string.rec_marker_note_hint)) },
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    session.dropMarker(markerNote.trim())
                    markerNote = ""
                    showMarkerDialog = false
                }) { Text(stringResource(CoreR.string.common_action_save)) }
            },
            dismissButton = {
                TextButton(onClick = {
                    showMarkerDialog = false
                    markerNote = ""
                }) { Text(stringResource(CoreR.string.common_action_cancel)) }
            },
        )
    }
}

@Composable
private fun StatCell(label: String, value: String, modifier: Modifier = Modifier) {
    Card(modifier = modifier.padding(horizontal = 3.dp)) {
        Column(modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp)) {
            Text(label, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Text(value, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
        }
    }
}
