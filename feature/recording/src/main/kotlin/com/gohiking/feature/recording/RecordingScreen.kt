package com.gohiking.feature.recording

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.gohiking.core.data.recording.RecordingSession
import com.gohiking.core.data.recording.SessionState
import com.gohiking.core.resources.R as CoreR
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import java.util.Locale
import kotlinx.coroutines.launch

/**
 * 记录页（M1 切片）：实时统计（运动时长 / 距离 / 爬升 / 点数）+ 暂停/继续 + 登顶标记 + 停止。
 * 状态唯一来源是 [RecordingSession]（DEV 冻结决策 12）；地图蓝点与轨迹线随 P-06 页面接入。
 */
@Composable
fun RecordingScreen(
    session: RecordingSession,
    modifier: Modifier = Modifier,
) {
    val state by session.state.collectAsStateWithLifecycle()
    val scope = rememberCoroutineScope()
    var pendingDraft by remember { mutableStateOf<com.gohiking.core.data.recording.TripDraft?>(null) }
    var showMarkerDialog by remember { mutableStateOf(false) }
    var markerNote by remember { mutableStateOf("") }

    val active = state as? SessionState.Active

    LaunchedEffect(Unit) {
        // 空态兜底：异常进入本页时返回
        if (state == SessionState.Idle) return@LaunchedEffect
    }

    Scaffold(modifier = modifier.fillMaxSize()) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Text(
                text = stringResource(
                    if (active?.isRecording != false) CoreR.string.rec_status_recording else CoreR.string.rec_status_paused,
                ),
                style = MaterialTheme.typography.headlineSmall,
                fontWeight = FontWeight.Bold,
            )

            if (active != null) {
                StatCard(stringResource(CoreR.string.rec_stat_duration), formatDuration(active.movingDurationSec))
                StatCard(stringResource(CoreR.string.rec_stat_distance), formatKm(active.distanceM))
                StatCard(stringResource(CoreR.string.rec_stat_climb), formatMeters(active.ascentM))
                // 海拔：fuser 不可用时显示「—」（PRD 6.1 降级规则，绝不编造）
                StatCard(
                    stringResource(CoreR.string.rec_stat_altitude),
                    active.currentAltitudeM?.let { formatMeters(it) }
                        ?: stringResource(CoreR.string.common_stat_unknown),
                )
                StatCard(
                    stringResource(CoreR.string.rec_stat_steps),
                    if (active.stepCount >= 0) active.stepCount.toString()
                    else stringResource(CoreR.string.common_stat_unknown),
                )
                StatCard(stringResource(CoreR.string.rec_stat_points), active.pointCount.toString())

                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    Button(
                        onClick = {
                            if (active.isRecording) session.pause() else session.resume()
                        },
                        modifier = Modifier.weight(1f),
                    ) {
                        Text(
                            stringResource(
                                if (active.isRecording) CoreR.string.rec_action_pause else CoreR.string.rec_action_resume,
                            ),
                        )
                    }
                    Button(
                        onClick = { session.markSummit(null) },
                        modifier = Modifier.weight(1f),
                        enabled = active.isRecording,
                    ) {
                        Text(stringResource(CoreR.string.rec_action_summit))
                    }
                    Button(
                        onClick = { showMarkerDialog = true },
                        modifier = Modifier.weight(1f),
                        enabled = active.isRecording,
                    ) {
                        Text(stringResource(CoreR.string.rec_action_marker))
                    }
                }
                Button(
                    onClick = {
                        scope.launch { pendingDraft = session.stop() }
                    },
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text(stringResource(CoreR.string.rec_action_stop))
                }
            }
        }
    }

    // F-REC-40/41：手动打点 + 备注输入；标记以「橙色水滴」渲染在详情页地图（F-REC-42）
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

    pendingDraft?.let { draft ->
        AlertDialog(
            onDismissRequest = { },
            title = { Text(stringResource(CoreR.string.rec_stop_confirm)) },
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
}

@Composable
private fun StatCard(label: String, value: String) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(label, style = MaterialTheme.typography.bodyMedium)
            Text(value, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
        }
    }
}

private fun formatDuration(totalSec: Long): String {
    val h = totalSec / 3600
    val m = (totalSec % 3600) / 60
    val s = totalSec % 60
    return String.format(Locale.US, "%d:%02d:%02d", h, m, s)
}

private fun formatKm(meters: Double): String =
    String.format(Locale.US, "%.2f km", meters / 1000.0)

private fun formatMeters(meters: Double): String =
    String.format(Locale.US, "%.0f m", meters)
