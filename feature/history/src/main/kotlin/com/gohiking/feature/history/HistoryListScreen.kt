package com.gohiking.feature.history

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Delete
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
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.gohiking.core.data.repository.TripRepository
import com.gohiking.core.resources.R as CoreR

/**
 * P-10 记录列表（M1 最小切片）。导航宿主落地（DEV §5.4）后由 HistoryList Tab 承载；
 * 当前由 M1 壳临时挂载。滑动删除 / 多选批量操作 / 缩略图在 M3/M4 补齐。
 */
@Composable
fun HistoryListScreen(
    tripRepository: TripRepository,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val viewModel: HistoryListViewModel = viewModel(
        factory = viewModelFactory {
            initializer { HistoryListViewModel(tripRepository) }
        },
    )
    HistoryListContent(viewModel = viewModel, onBack = onBack, modifier = modifier)
}

@Composable
private fun HistoryListContent(
    viewModel: HistoryListViewModel,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    var deleteTarget by remember { mutableStateOf<TripRowUi?>(null) }

    Column(
        modifier = modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background),
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 4.dp, vertical = 8.dp),
        ) {
            IconButton(onClick = onBack) {
                Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = null)
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

        Surface(
            tonalElevation = 3.dp,
            shape = RoundedCornerShape(12.dp),
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp),
        ) {
            Text(
                text = stringResource(
                    CoreR.string.history_summary_format,
                    state.summary.totalDistanceText,
                    state.summary.totalDurationText,
                    state.summary.totalAscentText,
                    state.summary.count,
                ),
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp),
            )
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
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(24.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center,
            ) {
                Text(
                    text = stringResource(CoreR.string.history_empty),
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
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
                horizontal = 12.dp, vertical = 4.dp,
            ),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            items(grouped.size) { i ->
                when (val item = grouped[i]) {
                    is ListItem.Header -> Text(
                        text = item.month,
                        style = MaterialTheme.typography.titleSmall,
                        color = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.padding(top = 8.dp, bottom = 2.dp),
                    )

                    is ListItem.Trip -> Surface(
                        tonalElevation = 2.dp,
                        shape = RoundedCornerShape(12.dp),
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Column(
                                modifier = Modifier
                                    .weight(1f)
                                    .padding(horizontal = 12.dp, vertical = 10.dp),
                            ) {
                                Text(
                                    text = item.trip.name,
                                    style = MaterialTheme.typography.titleMedium,
                                    maxLines = 1,
                                )
                                Text(
                                    text = item.trip.dateText,
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                                Spacer(Modifier.width(4.dp))
                                Text(
                                    text = listOf(
                                        item.trip.distanceText,
                                        item.trip.durationText,
                                        "↑${item.trip.ascentText}",
                                        item.trip.paceText ?: stringResource(CoreR.string.common_stat_unknown),
                                    ).joinToString(" · "),
                                    style = MaterialTheme.typography.bodySmall,
                                )
                            }
                            IconButton(onClick = { deleteTarget = item.trip }) {
                                Icon(
                                    Icons.Filled.Delete,
                                    contentDescription = stringResource(CoreR.string.common_action_delete),
                                )
                            }
                        }
                    }
                }
            }
        }
    }

    deleteTarget?.let { target ->
        AlertDialog(
            onDismissRequest = { deleteTarget = null },
            title = { Text(stringResource(CoreR.string.common_tab_history)) },
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
