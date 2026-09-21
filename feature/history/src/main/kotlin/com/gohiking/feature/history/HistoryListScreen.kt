package com.gohiking.feature.history

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.lazy.LazyColumn
// L-16：LazyListScope 的 items(count) 是成员方法，而带 key 的 items(List) 是扩展 —— 必须导入
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Landscape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.sp
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.gohiking.core.data.repository.TripRepository
import com.gohiking.core.designsystem.theme.GhColors
import com.gohiking.core.resources.R as CoreR

/**
 * P-10 记录列表（M1 最小切片）。导航宿主落地（DEV §5.4）后由 HistoryList Tab 承载；
 * 当前由 M1 壳临时挂载。滑动删除 / 多选批量操作 / 缩略图在 M3/M4 补齐。
 */
@Composable
fun HistoryListScreen(
    tripRepository: TripRepository,
    onBack: (() -> Unit)? = null, // Tab 模式无返回（P-10 appbar）
    onOpenTrip: (String) -> Unit = {}, // M1 详情页接入；默认空实现保持兼容
    onStartRecording: (() -> Unit)? = null,
    extraBottomPadding: Dp = 0.dp, // Tab 模式：底部 tabbar 高度（空态「开始记录」回地图 Tab）
    modifier: Modifier = Modifier,
) {
    val viewModel: HistoryListViewModel = viewModel(
        factory = viewModelFactory {
            initializer { HistoryListViewModel(tripRepository) }
        },
    )
    HistoryListContent(
        viewModel = viewModel,
        onBack = onBack,
        onOpenTrip = onOpenTrip,
        onStartRecording = onStartRecording,
        extraBottomPadding = extraBottomPadding,
        modifier = modifier,
    )
}

@Composable
private fun HistoryListContent(
    viewModel: HistoryListViewModel,
    onBack: (() -> Unit)? = null, // Tab 模式无返回（P-10 appbar）
    onOpenTrip: (String) -> Unit,
    onStartRecording: (() -> Unit)? = null,
    extraBottomPadding: Dp = 0.dp,
    modifier: Modifier = Modifier,
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    var deleteTarget by remember { mutableStateOf<TripRowUi?>(null) }

    Column(
        modifier = modifier
            .fillMaxSize()
            // N-11：targetSdk 35 强制 edge-to-edge，状态栏会压住顶部的返回按钮与标题
            .statusBarsPadding()
            .background(MaterialTheme.colorScheme.background),
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 4.dp, vertical = 8.dp),
        ) {
            if (onBack != null) {
            IconButton(onClick = onBack) {
                // L-15：contentDescription = null 会让 TalkBack 只念「按钮」，无法操作
                Icon(
                    Icons.AutoMirrored.Filled.ArrowBack,
                    contentDescription = stringResource(CoreR.string.common_action_back),
                )
            }
            }
            Text(
                text = stringResource(CoreR.string.common_tab_history),
                style = MaterialTheme.typography.titleLarge,
                modifier = Modifier.weight(1f),
            )
            FilterChip(
                selected = state.groupByMonth,
                onClick = { viewModel.onEvent(HistoryListEvent.ToggleGroupByMonth) },
                label = { Text(stringResource(CoreR.string.history_group_by_month)) },
                modifier = Modifier.padding(end = 8.dp),
            )
        }

        // P-10 汇总卡：次数 / 总距离 / 总时长 / 总爬升 四列指标
        Surface(
            shape = RoundedCornerShape(12.dp),
            color = GhColors.Surface,
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp),
        ) {
            Row(modifier = Modifier.fillMaxWidth().padding(vertical = 10.dp)) {
                SummaryCell(stringResource(CoreR.string.hist_stat_count), state.summary.count.toString(), Modifier.weight(1f))
                SummaryCell(stringResource(CoreR.string.rec_stat_distance), state.summary.totalDistanceText, Modifier.weight(1f))
                SummaryCell(stringResource(CoreR.string.rec_stat_duration), state.summary.totalDurationText, Modifier.weight(1f))
                SummaryCell(stringResource(CoreR.string.rec_stat_climb), state.summary.totalAscentText, Modifier.weight(1f))
            }
        }

        OutlinedTextField(
            value = state.query,
            onValueChange = { viewModel.onEvent(HistoryListEvent.Search(it)) },
            placeholder = { Text(stringResource(CoreR.string.history_search_hint)) },
            singleLine = true,
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 8.dp),
        )

        if (state.empty) {
            // P-10 空态：居中图标 + 标题 + 说明 + 开始记录入口
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(24.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center,
            ) {
                Box(
                    modifier = Modifier
                        .size(72.dp)
                        .clip(RoundedCornerShape(36.dp))
                        .background(GhColors.Surface2),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(
                        Icons.Filled.Landscape,
                        contentDescription = null,
                        tint = GhColors.TextTertiary,
                        modifier = Modifier.size(34.dp),
                    )
                }
                Spacer(Modifier.height(14.dp))
                Text(
                    // L-14：有搜索词时显示「无结果」，不骗人说「还没有行程」
                    text = if (state.query.isBlank()) {
                        stringResource(CoreR.string.history_empty)
                    } else {
                        stringResource(CoreR.string.history_no_result, state.query)
                    },
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Medium,
                )
                if (state.query.isBlank()) {
                    Spacer(Modifier.height(6.dp))
                    Text(
                        text = stringResource(CoreR.string.history_empty_desc),
                        fontSize = 12.sp,
                        color = GhColors.TextSecondary,
                        textAlign = TextAlign.Center,
                    )
                    if (onStartRecording != null) {
                        Spacer(Modifier.height(22.dp))
                        Row(
                            modifier = Modifier
                                .clip(RoundedCornerShape(12.dp))
                                .background(GhColors.Danger)
                                .clickable(onClick = onStartRecording)
                                .padding(horizontal = 18.dp, vertical = 12.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Icon(
                                Icons.Filled.PlayArrow,
                                contentDescription = null,
                                tint = Color.White,
                                modifier = Modifier.size(16.dp),
                            )
                            Spacer(Modifier.width(6.dp))
                            Text(
                                text = stringResource(CoreR.string.home_start_record),
                                color = Color.White,
                                fontSize = 15.sp,
                                fontWeight = FontWeight.Medium,
                            )
                        }
                    }
                }
            }
            return@Column
        }

        val grouped: List<ListItem> = remember(state.trips, state.groupByMonth) {
            buildList {
                if (state.groupByMonth) {
                    var lastMonth: String? = null
                    for (trip in state.trips) {
                        if (trip.monthKey != lastMonth) {
                            add(ListItem.Header(trip.monthKey))
                            lastMonth = trip.monthKey
                        }
                        add(ListItem.Trip(trip))
                    }
                } else {
                    state.trips.forEach { add(ListItem.Trip(it)) }
                }
            }
        }

        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = androidx.compose.foundation.layout.PaddingValues(
                horizontal = 12.dp, vertical = 4.dp + extraBottomPadding,
            ),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            // L-16：无 key 时列表无法差分，删除一条后行内状态（展开/选中）会错位；
            // 同项目 PlanListScreen 已有正确先例（items(items, key = { it.id })）
            items(
                items = grouped,
                key = { item ->
                    when (item) {
                        is ListItem.Header -> "month:${item.month}"
                        is ListItem.Trip -> "trip:${item.trip.id}"
                    }
                },
            ) { item ->
                when (item) {
                    is ListItem.Header -> Text(
                        text = item.month,
                        style = MaterialTheme.typography.titleSmall,
                        color = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.padding(top = 8.dp, bottom = 2.dp),
                    )

                    is ListItem.Trip -> Surface(
                        onClick = { onOpenTrip(item.trip.id) },
                        shape = RoundedCornerShape(12.dp),
                        color = GhColors.Surface,
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            TripThumb(modifier = Modifier.padding(start = 12.dp).size(52.dp))
                            Column(
                                modifier = Modifier
                                    .weight(1f)
                                    .padding(horizontal = 10.dp, vertical = 10.dp),
                            ) {
                                Text(
                                    text = item.trip.name,
                                    fontSize = 15.sp,
                                    fontWeight = FontWeight.SemiBold,
                                    maxLines = 1,
                                )
                                Text(
                                    text = item.trip.dateText,
                                    fontSize = 11.sp,
                                    color = GhColors.TextTertiary,
                                )
                                Spacer(Modifier.height(4.dp))
                                Text(
                                    text = listOf(
                                        item.trip.durationText,
                                        item.trip.distanceText,
                                        "+" + item.trip.ascentText,
                                        item.trip.paceText ?: stringResource(CoreR.string.common_stat_unknown),
                                    ).joinToString(" · "),
                                    fontSize = 11.sp,
                                    color = GhColors.TextSecondary,
                                    maxLines = 1,
                                )
                            }
                            IconButton(onClick = { deleteTarget = item.trip }) {
                                Icon(
                                    Icons.Filled.Delete,
                                    contentDescription = stringResource(CoreR.string.common_action_delete),
                                    tint = GhColors.TextTertiary,
                                    modifier = Modifier.size(18.dp),
                                )
                            }
                            Icon(
                                Icons.Filled.KeyboardArrowRight,
                                contentDescription = null,
                                tint = GhColors.TextTertiary,
                                modifier = Modifier.padding(end = 8.dp).size(20.dp),
                            )
                        }
                    }
                }
            }
        }
    }

    deleteTarget?.let { target ->
        AlertDialog(
            onDismissRequest = { deleteTarget = null },
            // L-14：弹窗标题原本复用「记录」（底部 tab 名），语义完全不对
            title = { Text(stringResource(CoreR.string.history_delete_title)) },
            text = { Text(stringResource(CoreR.string.history_delete_confirm, target.name)) },
            confirmButton = {
                TextButton(
                    onClick = {
                        viewModel.onEvent(HistoryListEvent.Delete(target.id))
                        deleteTarget = null
                    },
                ) { Text(stringResource(CoreR.string.common_action_delete)) }
            },
            dismissButton = {
                TextButton(onClick = { deleteTarget = null }) {
                    Text(stringResource(CoreR.string.common_action_cancel))
                }
            },
        )
    }
}

/** 列表扁平化元素：月分组头 或 行程行 */
private sealed interface ListItem {
    data class Header(val month: String) : ListItem
    data class Trip(val trip: TripRowUi) : ListItem
}

/** P-10 汇总卡单元（值 + 标签，居中）。 */
@Composable
private fun SummaryCell(label: String, value: String, modifier: Modifier = Modifier) {
    Column(modifier = modifier, horizontalAlignment = Alignment.CenterHorizontally) {
        Text(
            text = value,
            fontSize = 16.sp,
            fontWeight = FontWeight.SemiBold,
            color = GhColors.TextPrimary,
            maxLines = 1,
        )
        Text(text = label, fontSize = 10.sp, color = GhColors.TextSecondary, maxLines = 1)
    }
}

/** P-10 行程缩略图：简化轨迹（红实线，原型 miniTrack 同款走势）。 */
@Composable
private fun TripThumb(modifier: Modifier = Modifier) {
    Canvas(modifier = modifier) {
        val w = size.width
        val h = size.height
        val pts = listOf(
            Offset(0.10f * w, 0.88f * h),
            Offset(0.26f * w, 0.72f * h),
            Offset(0.44f * w, 0.60f * h),
            Offset(0.58f * w, 0.46f * h),
            Offset(0.72f * w, 0.34f * h),
            Offset(0.88f * w, 0.20f * h),
        )
        drawPath(
            path = Path().apply {
                moveTo(pts[0].x, pts[0].y)
                pts.drop(1).forEach { lineTo(it.x, it.y) }
            },
            color = GhColors.Track,
            style = Stroke(width = 2.5.dp.toPx()),
        )
    }
}
