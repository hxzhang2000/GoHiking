package com.gohiking.feature.recording

import android.app.Activity
import android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.border
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Flag
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
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
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
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
import com.gohiking.core.designsystem.theme.GhColors
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

    Column(modifier = modifier.fillMaxSize().background(GhColors.Bg)) {
        Box(
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth(),
        ) {
            AndroidView(factory = { mapView }, modifier = Modifier.fillMaxSize())

            // ---- P-08 appbar（浮层）：记录名 + 提醒开关标签 ----
            Row(
                modifier = Modifier
                    .align(Alignment.TopCenter)
                    .fillMaxWidth()
                    .statusBarsPadding()
                    .padding(horizontal = 12.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Surface(
                    shape = RoundedCornerShape(999.dp),
                    color = GhColors.Surface,
                    shadowElevation = 2.dp,
                    modifier = Modifier.weight(1f, fill = false),
                ) {
                    Text(
                        text = active?.name?.trim()?.ifEmpty { null }
                            ?: stringResource(CoreR.string.rec_start_dialog_title),
                        fontSize = 14.sp,
                        fontWeight = FontWeight.Medium,
                        maxLines = 1,
                        modifier = Modifier.padding(horizontal = 14.dp, vertical = 8.dp),
                    )
                }
                Spacer(Modifier.width(8.dp))
                if (active != null) {
                    Surface(
                        shape = RoundedCornerShape(999.dp),
                        color = if (active.alertsEnabled) GhColors.TagBlueBg else GhColors.Surface2,
                    ) {
                        Text(
                            text = stringResource(CoreR.string.rec_alert_tag) + ": " +
                                stringResource(
                                    if (active.alertsEnabled) CoreR.string.rec_alert_on else CoreR.string.rec_alert_off,
                                ),
                            fontSize = 12.sp,
                            color = if (active.alertsEnabled) GhColors.TagBlueFg else GhColors.TextSecondary,
                            modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
                        )
                    }
                }
            }

            // F-REC-17：暂停浮层（原型：顶部居中深色胶囊）
            if (active != null && !active.isRecording) {
                Surface(
                    shape = RoundedCornerShape(999.dp),
                    color = Color(0xCC1B1F24),
                    modifier = Modifier
                        .align(Alignment.TopCenter)
                        .padding(top = 64.dp),
                ) {
                    Text(
                        text = stringResource(CoreR.string.rec_status_paused),
                        color = Color.White,
                        fontSize = 12.sp,
                        modifier = Modifier.padding(horizontal = 14.dp, vertical = 6.dp),
                    )
                }
            }
        }

        if (active != null) {
            // ---- P-08 recstats：大数字两列 + 分隔 + 三列 ----
            Surface(
                shape = RoundedCornerShape(12.dp),
                color = GhColors.Surface,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 12.dp),
            ) {
                Column(modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp)) {
                    Row {
                        BigStat(
                            label = stringResource(CoreR.string.rec_stat_distance),
                            value = Formatters.distanceText(active.distanceM),
                            modifier = Modifier.weight(1f),
                        )
                        Box(
                            Modifier
                                .width(1.dp)
                                .height(40.dp)
                                .background(GhColors.Line2),
                        )
                        BigStat(
                            label = stringResource(CoreR.string.rec_stat_duration),
                            value = Formatters.durationText(active.movingDurationSec),
                            modifier = Modifier.weight(1f),
                        )
                    }
                    HorizontalDivider(color = GhColors.Line2, thickness = 1.dp, modifier = Modifier.padding(vertical = 8.dp))
                    Row {
                        SmallStat(
                            label = stringResource(CoreR.string.rec_stat_altitude),
                            value = active.currentAltitudeM?.let { Formatters.metersText(it) }
                                ?: stringResource(CoreR.string.common_stat_unknown), // fuser 不可用显示「—」，绝不编造（PRD 6.1）
                            modifier = Modifier.weight(1f),
                        )
                        SmallStat(
                            label = stringResource(CoreR.string.rec_stat_climb),
                            value = "+" + Formatters.metersText(active.ascentM),
                            modifier = Modifier.weight(1f),
                        )
                        SmallStat(
                            label = stringResource(CoreR.string.rec_stat_steps),
                            value = if (active.stepCount >= 0) active.stepCount.toString() else stringResource(CoreR.string.common_stat_unknown),
                            modifier = Modifier.weight(1f),
                        )
                    }
                }
            }

            // F-PLAN-44：关联计划入口 + 采样点数（弱化为一行次要信息）
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 12.dp, vertical = 2.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                TextButton(onClick = { showPlanPicker = true }) {
                    Text(
                        text = active.plannedRouteId?.let { id ->
                            stringResource(
                                CoreR.string.rec_plan_linked,
                                savedPlans.firstOrNull { it.route.id == id }?.route?.name ?: "",
                            )
                        } ?: stringResource(CoreR.string.rec_plan_associate),
                        fontSize = 12.sp,
                    )
                }
                Spacer(Modifier.weight(1f))
                Text(
                    text = stringResource(CoreR.string.rec_stat_points) + " " + active.pointCount,
                    fontSize = 10.sp,
                    color = GhColors.TextTertiary,
                )
            }

            // ---- P-08 recctrl：标记 / 暂停·继续 / 登顶 / 停止 ----
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .navigationBarsPadding()
                    .padding(horizontal = 12.dp, vertical = 12.dp)
                    .graphicsLayer { alpha = if (active.isRecording) 1f else 0.45f }, // F-REC-17
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                CtrlButton(
                    icon = Icons.Filled.Edit,
                    label = stringResource(CoreR.string.rec_action_marker),
                    enabled = active.isRecording,
                    container = GhColors.Surface,
                    content = GhColors.TextPrimary,
                    border = BorderStroke(1.dp, GhColors.Line),
                    onClick = { showMarkerDialog = true },
                    modifier = Modifier.weight(1f),
                )
                CtrlButton(
                    icon = if (active.isRecording) Icons.Filled.Pause else Icons.Filled.PlayArrow,
                    label = stringResource(
                        if (active.isRecording) CoreR.string.rec_action_pause else CoreR.string.rec_action_resume,
                    ),
                    enabled = true,
                    container = GhColors.Primary,
                    content = Color.White,
                    onClick = { if (active.isRecording) session.pause() else session.resume() },
                    modifier = Modifier.weight(1.25f),
                )
                CtrlButton(
                    icon = Icons.Filled.Flag,
                    label = stringResource(CoreR.string.rec_action_summit),
                    enabled = active.isRecording,
                    container = GhColors.Surface,
                    content = GhColors.TextPrimary,
                    border = BorderStroke(1.dp, GhColors.Line),
                    onClick = { session.markSummit(null) },
                    modifier = Modifier.weight(1f),
                )

                // F-REC-06：停止需长按 1.5 秒，避免误触
                val stopLabel = stringResource(CoreR.string.rec_action_stop)
                CtrlButton(
                    icon = Icons.Filled.Stop,
                    label = stringResource(
                        if (holdingStop) CoreR.string.rec_stop_holding else CoreR.string.rec_stop_hold_short,
                    ),
                    enabled = true,
                    container = GhColors.Danger,
                    content = Color.White,
                    onClick = { /* 长按触发；点击不动作（防误触） */ },
                    modifier = Modifier
                        .weight(1f)
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
                )
            }
        }
    }

    // F-REC-07：停止后保存确认（P-09：汇总卡 + 名称/备注）
    draftToConfirm?.let { draft ->
        val trip = draft.trip
        var nameInput by remember(trip.id) { mutableStateOf(trip.name) }
        var noteInput by remember(trip.id) { mutableStateOf(trip.note.orEmpty()) }
        AlertDialog(
            onDismissRequest = { },
            title = { Text(stringResource(CoreR.string.rec_stop_confirm)) },
            text = {
                Column {
                    // P-09 汇总卡：2×2 指标
                    Surface(shape = RoundedCornerShape(12.dp), color = MaterialTheme.colorScheme.surfaceVariant) {
                        Row(modifier = Modifier.fillMaxWidth().padding(vertical = 10.dp)) {
                            SmallStat(
                                label = stringResource(CoreR.string.rec_stat_distance),
                                value = Formatters.distanceText(trip.distanceM),
                                modifier = Modifier.weight(1f),
                            )
                            SmallStat(
                                label = stringResource(CoreR.string.rec_stat_duration),
                                value = Formatters.durationText(trip.movingDurationSec),
                                modifier = Modifier.weight(1f),
                            )
                        }
                    }
                    Spacer(Modifier.height(6.dp))
                    Surface(shape = RoundedCornerShape(12.dp), color = MaterialTheme.colorScheme.surfaceVariant) {
                        Row(modifier = Modifier.fillMaxWidth().padding(vertical = 10.dp)) {
                            SmallStat(
                                label = stringResource(CoreR.string.rec_stat_climb),
                                value = "+" + Formatters.metersText(trip.totalAscentM),
                                modifier = Modifier.weight(1f),
                            )
                            SmallStat(
                                label = stringResource(CoreR.string.rec_stat_steps),
                                value = if (trip.steps >= 0) trip.steps.toString() else stringResource(CoreR.string.common_stat_unknown),
                                modifier = Modifier.weight(1f),
                            )
                        }
                    }
                    Spacer(Modifier.height(10.dp))
                    OutlinedTextField(
                        value = nameInput,
                        onValueChange = { nameInput = it },
                        placeholder = { Text(stringResource(CoreR.string.rec_start_name_hint)) },
                        singleLine = true,
                    )
                    Spacer(Modifier.height(8.dp))
                    OutlinedTextField(
                        value = noteInput,
                        onValueChange = { noteInput = it },
                        placeholder = { Text(stringResource(CoreR.string.plan_note_hint)) },
                        minLines = 2,
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
                            // P-09：名称/备注以 UI 输入为准（空名称回落原名）
                            val edited = draft.copy(
                                trip = trip.copy(
                                    name = nameInput.trim().ifEmpty { trip.name },
                                    note = noteInput.trim().ifEmpty { null },
                                ),
                            )
                            val ok = session.save(edited)
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
private fun BigStat(label: String, value: String, modifier: Modifier = Modifier) {
    Column(modifier = modifier, horizontalAlignment = Alignment.CenterHorizontally) {
        Text(
            text = value,
            fontSize = 28.sp,
            fontWeight = FontWeight.Bold,
            color = GhColors.TextPrimary,
            textAlign = TextAlign.Center,
            maxLines = 1,
        )
        Text(
            text = label,
            fontSize = 11.sp,
            color = GhColors.TextSecondary,
        )
    }
}

@Composable
private fun SmallStat(label: String, value: String, modifier: Modifier = Modifier) {
    Column(modifier = modifier, horizontalAlignment = Alignment.CenterHorizontally) {
        Text(
            text = value,
            fontSize = 16.sp,
            fontWeight = FontWeight.SemiBold,
            color = GhColors.TextPrimary,
            maxLines = 1,
        )
        Text(
            text = label,
            fontSize = 10.sp,
            color = GhColors.TextSecondary,
        )
    }
}

/** P-08 recctrl 按钮（图标+文案纵排；主键实底、其余描边/红底）。 */
@Composable
private fun CtrlButton(
    icon: ImageVector,
    label: String,
    enabled: Boolean,
    container: Color,
    content: Color,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    border: BorderStroke? = null,
) {
    val shape = RoundedCornerShape(14.dp)
    Box(
        modifier = modifier
            .heightIn(min = 56.dp)
            .clip(shape)
            .background(if (enabled) container else container.copy(alpha = 0.55f))
            .then(if (border != null) Modifier.border(border, shape) else Modifier)
            .clickable(enabled = enabled, onClick = onClick)
            .padding(horizontal = 4.dp, vertical = 8.dp),
        contentAlignment = Alignment.Center,
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Icon(icon, contentDescription = null, tint = content, modifier = Modifier.size(20.dp))
            Spacer(Modifier.height(2.dp))
            Text(
                text = label,
                color = content,
                fontSize = 11.sp,
                maxLines = 1,
            )
        }
    }
}
