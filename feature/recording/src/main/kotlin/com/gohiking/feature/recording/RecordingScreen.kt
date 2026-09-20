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
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
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
import com.gohiking.core.data.recording.RecordingSession
import com.gohiking.core.data.recording.SessionState
import com.gohiking.core.data.recording.TripDraft
import com.gohiking.core.data.repository.TripRepository
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

@Composable
fun RecordingScreen(
    session: RecordingSession,
    tripRepository: TripRepository,
    modifier: Modifier = Modifier,
) {
    val state by session.state.collectAsStateWithLifecycle()
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    var pendingDraft by remember { mutableStateOf<TripDraft?>(null) }
    var showMarkerDialog by remember { mutableStateOf(false) }
    var markerNote by remember { mutableStateOf("") }
    var holdingStop by remember { mutableStateOf(false) }

    val active = state as? SessionState.Active

    // F-REC-18：记录过程屏幕常亮
    DisposableEffect(Unit) {
        (context as? Activity)?.window?.addFlags(FLAG_KEEP_SCREEN_ON)
        onDispose {
            (context as? Activity)?.window?.clearFlags(FLAG_KEEP_SCREEN_ON)
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
                    PolylineOptions().add(ll).width(trackWidthPx).color(TRACK_RED),
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
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(52.dp)
                        .clip(RoundedCornerShape(20.dp))
                        .background(errorColor)
                        .pointerInput(Unit) {
                            awaitEachGesture {
                                awaitFirstDown(requireUnconsumed = false)
                                holdingStop = true
                                val start = System.currentTimeMillis()
                                var fired = false
                                while (true) {
                                    val event = awaitPointerEvent()
                                    if (!event.changes.any { it.pressed }) break
                                    if (!fired && System.currentTimeMillis() - start >= STOP_HOLD_MS) {
                                        fired = true
                                        scope.launch { pendingDraft = session.stop() }
                                    }
                                }
                                holdingStop = false
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
    pendingDraft?.let { draft ->
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
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    scope.launch {
                        session.save(draft)
                        pendingDraft = null
                    }
                }) { Text(stringResource(CoreR.string.common_action_save)) }
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
