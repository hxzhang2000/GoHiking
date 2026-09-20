package com.gohiking.feature.history

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.amap.api.maps.CameraUpdateFactory
import com.amap.api.maps.MapView
import com.amap.api.maps.MapsInitializer
import com.amap.api.maps.model.BitmapDescriptorFactory
import com.amap.api.maps.model.LatLng
import com.amap.api.maps.model.LatLngBounds
import com.amap.api.maps.model.MarkerOptions
import com.amap.api.maps.model.PolylineOptions
import com.gohiking.core.common.format.Formatters
import com.gohiking.core.data.repository.TripRepository
import com.gohiking.core.database.entity.MarkerEntity
import com.gohiking.core.database.entity.TripEntity
import com.gohiking.core.data.stats.LegSummary
import com.gohiking.core.resources.R as CoreR
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

/**
 * 行程详情页（P-11 的 M1 最小切片）：
 * - F-HIS-20 顶部名称（可编辑）+ 日期；天气项可选，暂不做；
 * - F-HIS-21 地图区：红色轨迹（quality==0、按 segment 分段）+ 全部标记（登顶红 / 手动橙 F-REC-42）；
 * - F-HIS-22 概览区 12 项（口径已由保存时落库，DEV 决策 14）；
 * - F-HIS-26 上山/下山分段（无登顶点显示提示）；F-HIS-27 标记列表（点击地图定位）；
 * - F-HIS-30 操作栏：重命名 / 编辑备注 / 删除；导出 / 分享依赖 F-IO，M3 接入（按钮禁用占位）。
 */
@Composable
fun TripDetailScreen(
    tripRepository: TripRepository,
    tripId: String,
    onBack: () -> Unit,
    onDeleted: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val viewModel: TripDetailViewModel = viewModel(
        key = tripId,
        factory = viewModelFactory {
            initializer { TripDetailViewModel(tripRepository, tripId) }
        },
    )
    TripDetailContent(
        viewModel = viewModel,
        onBack = onBack,
        onDeleted = onDeleted,
        modifier = modifier,
    )
}

@Composable
private fun TripDetailContent(
    viewModel: TripDetailViewModel,
    onBack: () -> Unit,
    onDeleted: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    var focusMarker by remember { mutableStateOf<MarkerEntity?>(null) }
    var showRename by remember { mutableStateOf(false) }
    var showNote by remember { mutableStateOf(false) }
    var showDelete by remember { mutableStateOf(false) }

    LaunchedEffect(state.deleted) {
        if (state.deleted) onDeleted()
    }

    Column(
        modifier = modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background),
    ) {
        // F-HIS-20 顶部：返回 + 名称 + 日期（重命名/改备注后 trip Flow 自动刷新）
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 4.dp, vertical = 8.dp),
        ) {
            IconButton(onClick = onBack) {
                Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = null)
            }
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = state.trip?.name ?: stringResource(CoreR.string.hist_detail_loading),
                    style = MaterialTheme.typography.titleLarge,
                    maxLines = 1,
                )
                state.trip?.let { trip ->
                    Text(
                        text = formatEpoch(trip.startTime, DATE_FORMAT),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            IconButton(onClick = { showRename = true }) {
                Icon(Icons.Filled.Edit, contentDescription = stringResource(CoreR.string.hist_detail_action_rename))
            }
        }

        if (state.loading && state.trip == null) {
            Text(
                text = stringResource(CoreR.string.hist_detail_loading),
                style = MaterialTheme.typography.bodyLarge,
                modifier = Modifier.padding(24.dp),
            )
            return@Column
        }
        val trip = state.trip ?: return@Column

        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = androidx.compose.foundation.layout.PaddingValues(bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            // F-HIS-21 地图区
            item {
                TripMap(
                    segments = state.segments,
                    markers = state.markers,
                    focus = focusMarker,
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(260.dp)
                        .padding(horizontal = 12.dp),
                )
            }

            // F-HIS-22 概览区（12 项）
            item { SectionTitle(stringResource(CoreR.string.hist_detail_section_overview)) }
            item { OverviewGrid(trip) }

            // F-HIS-26 上山 / 下山
            item { SectionTitle(stringResource(CoreR.string.hist_detail_section_legs)) }
            item {
                val legs = state.legs
                if (legs == null) {
                    Text(
                        text = stringResource(CoreR.string.hist_legs_none),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(horizontal = 12.dp),
                    )
                } else {
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(10.dp),
                        modifier = Modifier.padding(horizontal = 12.dp),
                    ) {
                        LegCard(
                            title = stringResource(CoreR.string.hist_leg_up),
                            leg = legs.uphill,
                            modifier = Modifier.weight(1f),
                        )
                        LegCard(
                            title = stringResource(CoreR.string.hist_leg_down),
                            leg = legs.downhill,
                            modifier = Modifier.weight(1f),
                        )
                    }
                }
            }

            // F-HIS-27 标记列表（点击 → 地图定位）
            item { SectionTitle(stringResource(CoreR.string.hist_detail_section_markers)) }
            if (state.markers.isEmpty()) {
                item {
                    Text(
                        text = stringResource(CoreR.string.hist_legs_none),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(horizontal = 12.dp),
                    )
                }
            }
            items(state.markers, key = { it.id }) { marker ->
                MarkerRow(marker = marker, onClick = { focusMarker = marker })
            }

            // F-HIS-30 操作栏（导出/分享 M3 接 F-IO，禁用占位）
            item {
                Row(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 12.dp),
                ) {
                    OutlinedButton(onClick = { showNote = true }, modifier = Modifier.weight(1f)) {
                        Text(stringResource(CoreR.string.hist_detail_action_note), maxLines = 1)
                    }
                    OutlinedButton(onClick = { showDelete = true }, modifier = Modifier.weight(1f)) {
                        Text(stringResource(CoreR.string.common_action_delete), maxLines = 1)
                    }
                }
                Row(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 12.dp, vertical = 4.dp),
                ) {
                    // F-IO 导出 / 分享属 M3 里程碑，M1 禁用占位（F-HIS-30 完整版届时补齐）
                    OutlinedButton(enabled = false, onClick = {}, modifier = Modifier.weight(1f)) {
                        Text(stringResource(CoreR.string.hist_detail_action_export), maxLines = 1)
                    }
                    OutlinedButton(enabled = false, onClick = {}, modifier = Modifier.weight(1f)) {
                        Text(stringResource(CoreR.string.hist_detail_action_share), maxLines = 1)
                    }
                }
            }
        }
    }

    if (showRename) {
        var nameInput by remember { mutableStateOf(state.trip?.name ?: "") }
        AlertDialog(
            onDismissRequest = { showRename = false },
            title = { Text(stringResource(CoreR.string.hist_detail_action_rename)) },
            text = {
                OutlinedTextField(
                    value = nameInput,
                    onValueChange = { nameInput = it },
                    placeholder = { Text(stringResource(CoreR.string.hist_detail_rename_hint)) },
                    singleLine = true,
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    viewModel.rename(nameInput)
                    showRename = false
                }) { Text(stringResource(CoreR.string.common_action_save)) }
            },
            dismissButton = {
                TextButton(onClick = { showRename = false }) {
                    Text(stringResource(CoreR.string.common_action_cancel))
                }
            },
        )
    }

    if (showNote) {
        var noteInput by remember { mutableStateOf(state.trip?.note ?: "") }
        AlertDialog(
            onDismissRequest = { showNote = false },
            title = { Text(stringResource(CoreR.string.hist_detail_action_note)) },
            text = {
                OutlinedTextField(
                    value = noteInput,
                    onValueChange = { noteInput = it },
                    placeholder = { Text(stringResource(CoreR.string.hist_detail_note_hint)) },
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    viewModel.updateNote(noteInput)
                    showNote = false
                }) { Text(stringResource(CoreR.string.common_action_save)) }
            },
            dismissButton = {
                TextButton(onClick = { showNote = false }) {
                    Text(stringResource(CoreR.string.common_action_cancel))
                }
            },
        )
    }

    if (showDelete) {
        val tripName = state.trip?.name ?: ""
        AlertDialog(
            onDismissRequest = { showDelete = false },
            title = { Text(stringResource(CoreR.string.common_action_delete)) },
            text = { Text(stringResource(CoreR.string.hist_detail_delete_confirm, tripName)) },
            confirmButton = {
                TextButton(onClick = {
                    showDelete = false
                    viewModel.delete()
                }) { Text(stringResource(CoreR.string.common_action_delete)) }
            },
            dismissButton = {
                TextButton(onClick = { showDelete = false }) {
                    Text(stringResource(CoreR.string.common_action_cancel))
                }
            },
        )
    }
}

/** 地图（F-HIS-21）：红色轨迹按 segment 分段绘制（跨段不连线），标记按类型着色。 */
@Composable
private fun TripMap(
    segments: List<List<com.gohiking.core.model.LatLngValue>>,
    markers: List<MarkerEntity>,
    focus: MarkerEntity?,
    modifier: Modifier = Modifier,
) {
    val context = androidx.compose.ui.platform.LocalContext.current
    val lifecycleOwner = androidx.lifecycle.compose.LocalLifecycleOwner.current
    val trackWidthPx = with(LocalDensity.current) { 8.dp.toPx() } // PRD F-REC-03：线宽 8dp

    // 隐私合规：进入本页前 Root 层 PrivacyGate 已同意；此处调用幂等（DEV §1.5 红线）
    val mapView = remember {
        MapsInitializer.updatePrivacyShow(context, true, true)
        MapsInitializer.updatePrivacyAgree(context, true)
        MapView(context).apply {
            onCreate(null)
            map.uiSettings.isZoomControlsEnabled = false
        }
    }

    androidx.compose.runtime.DisposableEffect(lifecycleOwner) {
        val observer = androidx.lifecycle.LifecycleEventObserver { _, event ->
            when (event) {
                androidx.lifecycle.Lifecycle.Event.ON_RESUME -> mapView.onResume()
                androidx.lifecycle.Lifecycle.Event.ON_PAUSE -> mapView.onPause()
                else -> Unit
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
            mapView.onDestroy()
        }
    }

    // 数据就绪后重建 overlay（每次都全量重建，量级 = 抽稀后点数 + 标记数，无性能问题）
    LaunchedEffect(segments, markers) {
        val aMap = mapView.map
        aMap.clear()
        val all = ArrayList<LatLng>()
        for (seg in segments) {
            val latLngs = seg.map { LatLng(it.latitude, it.longitude) }
            all.addAll(latLngs)
            if (latLngs.size >= 2) {
                aMap.addPolyline(
                    PolylineOptions()
                        .addAll(latLngs)
                        .width(trackWidthPx)
                        .color(TRACK_RED), // 红色轨迹 #E24B4A（F-REC-03 / F-HIS-21）
                )
            }
        }
        for (m in markers) {
            // F-REC-42：手动标记橙色；登顶红旗 M1 用红色 marker 表达（自定义图标 M4 打磨）
            val hue = when (m.type) {
                "SUMMIT" -> BitmapDescriptorFactory.HUE_RED
                "MANUAL" -> BitmapDescriptorFactory.HUE_ORANGE
                else -> BitmapDescriptorFactory.HUE_AZURE
            }
            aMap.addMarker(
                MarkerOptions()
                    .position(LatLng(m.latitude, m.longitude))
                    .icon(BitmapDescriptorFactory.defaultMarker(hue)),
            )
        }
        if (all.isNotEmpty()) {
            val builder = LatLngBounds.Builder()
            all.forEach { builder.include(it) }
            aMap.moveCamera(CameraUpdateFactory.newLatLngBounds(builder.build(), 80))
        }
    }

    // 标记列表点击定位（F-HIS-27）
    LaunchedEffect(focus) {
        focus?.let { m ->
            mapView.map.moveCamera(
                CameraUpdateFactory.newLatLngZoom(LatLng(m.latitude, m.longitude), 16f),
            )
        }
    }

    AndroidView(factory = { mapView }, modifier = modifier)
}

/** F-HIS-22 概览 12 项，两列排布。 */
@Composable
private fun OverviewGrid(trip: TripEntity) {
    val unknown = stringResource(CoreR.string.common_stat_unknown)
    val cells = listOf(
        stringResource(CoreR.string.rec_stat_distance) to Formatters.distanceText(trip.distanceM),
        stringResource(CoreR.string.hist_overview_total_duration) to Formatters.durationText(trip.durationSec),
        stringResource(CoreR.string.rec_stat_duration) to Formatters.durationText(trip.movingDurationSec),
        stringResource(CoreR.string.hist_overview_paused) to Formatters.durationText(trip.pausedDurationSec),
        stringResource(CoreR.string.rec_stat_climb) to Formatters.metersText(trip.totalAscentM),
        stringResource(CoreR.string.hist_overview_descent) to Formatters.metersText(trip.totalDescentM),
        stringResource(CoreR.string.hist_overview_max_alt) to (trip.maxAltitudeM?.let { Formatters.metersText(it) } ?: unknown),
        stringResource(CoreR.string.hist_overview_min_alt) to (trip.minAltitudeM?.let { Formatters.metersText(it) } ?: unknown),
        stringResource(CoreR.string.hist_overview_avg_speed) to (Formatters.speedText(trip.avgSpeedMps) ?: unknown),
        stringResource(CoreR.string.hist_overview_avg_pace) to (Formatters.paceText(trip.avgPaceSecPerKm) ?: unknown),
        stringResource(CoreR.string.rec_stat_steps) to (if (trip.steps >= 0) trip.steps.toString() else unknown),
        stringResource(CoreR.string.hist_overview_calories) to (Formatters.kcalText(trip.caloriesKcal) ?: unknown),
    )
    Surface(
        tonalElevation = 2.dp,
        shape = RoundedCornerShape(12.dp),
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp),
    ) {
        Column(modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp)) {
            cells.chunked(2).forEach { row ->
                Row(modifier = Modifier.fillMaxWidth()) {
                    row.forEach { (label, value) ->
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.SpaceBetween,
                            modifier = Modifier
                                .weight(1f)
                                .padding(vertical = 6.dp, horizontal = 4.dp),
                        ) {
                            Text(
                                text = label,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                            Text(
                                text = value,
                                style = MaterialTheme.typography.bodyMedium,
                                fontWeight = FontWeight.SemiBold,
                            )
                        }
                    }
                    if (row.size == 1) Spacer(Modifier.weight(1f))
                }
            }
        }
    }
}

@Composable
private fun LegCard(title: String, leg: LegSummary, modifier: Modifier = Modifier) {
    Card(modifier = modifier) {
        Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(text = title, style = MaterialTheme.typography.titleSmall)
            Text(
                text = Formatters.distanceText(leg.distanceM),
                style = MaterialTheme.typography.bodySmall,
            )
            Text(
                text = Formatters.durationText(leg.movingSec),
                style = MaterialTheme.typography.bodySmall,
            )
            Text(
                text = "↑" + Formatters.metersText(leg.ascentM) +
                    " ↓" + Formatters.metersText(leg.descentM),
                style = MaterialTheme.typography.bodySmall,
            )
        }
    }
}

/** F-HIS-27 标记行：类型徽章（本地化生成，PRD 7.1 禁用 label 快照）+ 时间 + 备注 + 海拔。 */
@Composable
private fun MarkerRow(marker: MarkerEntity, onClick: () -> Unit) {
    Surface(
        onClick = onClick,
        tonalElevation = 2.dp,
        shape = RoundedCornerShape(10.dp),
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp),
    ) {
        Column(modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = stringResource(markerTypeLabel(marker.type)),
                    style = MaterialTheme.typography.labelMedium,
                    color = markerTypeColor(marker.type),
                )
                Spacer(Modifier.weight(1f))
                Text(
                    text = formatEpoch(marker.timestamp, MARKER_TIME_FORMAT),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                marker.altitude?.let {
                    Text(
                        text = Formatters.metersText(it),
                        style = MaterialTheme.typography.bodySmall,
                    )
                    Spacer(Modifier.weight(1f))
                } ?: Spacer(Modifier.weight(1f))
            }
            marker.note?.takeIf { it.isNotBlank() }?.let {
                Text(text = it, style = MaterialTheme.typography.bodySmall)
            }
        }
    }
}

@Composable
private fun SectionTitle(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.titleMedium,
        modifier = Modifier.padding(horizontal = 12.dp),
    )
}

/** 标记类型 → 本地化文案（PRD 7.1：展示必须按 type 结构化生成本地化文案） */
private fun markerTypeLabel(type: String): Int = when (type) {
    "SUMMIT" -> CoreR.string.hist_marker_type_summit
    "MANUAL" -> CoreR.string.hist_marker_type_manual
    "ALERT_DISTANCE" -> CoreR.string.hist_marker_type_alert_distance
    "ALERT_ASCENT" -> CoreR.string.hist_marker_type_alert_ascent
    "ALERT_DESCENT" -> CoreR.string.hist_marker_type_alert_descent
    else -> CoreR.string.hist_marker_type_unknown
}

private fun markerTypeColor(type: String) = when (type) {
    "SUMMIT" -> androidx.compose.ui.graphics.Color(0xFFE53935)
    "MANUAL" -> androidx.compose.ui.graphics.Color(0xFFF57C00)
    else -> androidx.compose.ui.graphics.Color(0xFF1E88E5)
}

private val TRACK_RED = 0xFFE24B4A.toInt() // PRD F-REC-03 权威色值

// DateTimeFormatter 线程安全且不可变，无需 ThreadLocal（K2 起 SimpleDateFormat?.get() 可空告警）
private val DATE_FORMAT: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm", Locale.ROOT)
private val MARKER_TIME_FORMAT: DateTimeFormatter = DateTimeFormatter.ofPattern("HH:mm:ss", Locale.ROOT)

/** epoch millis → 本地时区格式化（DateTimeFormatter 线程安全） */
private fun formatEpoch(ms: Long, fmt: DateTimeFormatter): String =
    fmt.format(Instant.ofEpochMilli(ms).atZone(ZoneId.systemDefault()))
