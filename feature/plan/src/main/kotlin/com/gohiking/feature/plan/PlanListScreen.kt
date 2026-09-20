package com.gohiking.feature.plan

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.HorizontalDivider
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
import com.gohiking.core.common.format.Formatters
import com.gohiking.core.resources.R as CoreR
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

/**
 * 计划线路列表页（P-06，F-PLAN-41/42）：名称/总距离/总爬升/创建时间 + 重命名/删除。
 * 点击条目 → 重命名；长按语义用删除按钮表达（Compose 无长按菜单，保持简单）。
 */
@Composable
fun PlanListScreen(
    plannedRouteDao: com.gohiking.core.database.dao.PlannedRouteDao,
    onBack: () -> Unit,
    onExportRoute: ((routeId: String, routeName: String) -> Unit)? = null, // F-PLAN-43（M3-E 接线；文件名由壳层生成）
    modifier: Modifier = Modifier,
) {
    val viewModel: PlanListViewModel = viewModel(
        factory = viewModelFactory {
            initializer { PlanListViewModel(plannedRouteDao) }
        },
    )
    val items by viewModel.items.collectAsStateWithLifecycle()
    var renameTarget by remember { mutableStateOf<PlanListItem?>(null) }
    var deleteTarget by remember { mutableStateOf<PlanListItem?>(null) }

    Column(modifier = modifier.fillMaxSize().padding(16.dp)) {
        Row(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text(
                text = stringResource(CoreR.string.plan_list_title),
                style = MaterialTheme.typography.titleMedium,
                modifier = Modifier.weight(1f),
            )
            Button(onClick = onBack) {
                Text(stringResource(CoreR.string.plan_back))
            }
        }
        if (items.isEmpty()) {
            Text(
                text = stringResource(CoreR.string.plan_list_empty),
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.padding(top = 24.dp),
            )
        } else {
            LazyColumn(modifier = Modifier.padding(top = 8.dp)) {
                items(items.size) { i ->
                    val item = items[i]
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { renameTarget = item } // F-PLAN-42 重命名入口
                            .padding(vertical = 10.dp),
                    ) {
                        Text(item.name, style = MaterialTheme.typography.titleSmall)
                        Text(
                            text = Formatters.distanceText(item.totalDistanceM) +
                                " · " +
                                // F-PLAN-46：计划爬升是估算值
                                stringResource(
                                    CoreR.string.plan_estimated_ascent,
                                    item.totalAscentM.toInt(),
                                ) +
                                " · " + formatEpoch(item.createdAt, DATE_FMT),
                            style = MaterialTheme.typography.bodySmall,
                        )
                        TextButton(onClick = { deleteTarget = item }) {
                            Text(stringResource(CoreR.string.plan_list_delete))
                        }
                        onExportRoute?.let { export ->
                            TextButton(onClick = { export(item.id, item.name) }) {
                                Text(stringResource(CoreR.string.plan_export_route))
                            }
                        }
                    }
                    if (i < items.size - 1) HorizontalDivider()
                }
            }
        }
    }

    // F-PLAN-42：重命名对话框（默认原名）
    renameTarget?.let { target ->
        var nameInput by remember(target.id) { mutableStateOf(target.name) }
        AlertDialog(
            onDismissRequest = { renameTarget = null },
            title = { Text(stringResource(CoreR.string.plan_list_rename)) },
            text = {
                OutlinedTextField(
                    value = nameInput,
                    onValueChange = { nameInput = it },
                    singleLine = true,
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    viewModel.rename(target.id, nameInput)
                    renameTarget = null
                }) { Text(stringResource(CoreR.string.common_action_confirm)) }
            },
            dismissButton = {
                TextButton(onClick = { renameTarget = null }) {
                    Text(stringResource(CoreR.string.common_action_cancel))
                }
            },
        )
    }

    // F-PLAN-42：删除确认
    deleteTarget?.let { target ->
        AlertDialog(
            onDismissRequest = { deleteTarget = null },
            title = { Text(stringResource(CoreR.string.plan_list_delete)) },
            text = { Text(target.name) },
            confirmButton = {
                TextButton(onClick = {
                    viewModel.delete(target.id)
                    deleteTarget = null
                }) { Text(stringResource(CoreR.string.common_action_confirm)) }
            },
            dismissButton = {
                TextButton(onClick = { deleteTarget = null }) {
                    Text(stringResource(CoreR.string.common_action_cancel))
                }
            },
        )
    }
}

// DateTimeFormatter 线程安全且不可变，无需 ThreadLocal（K2 起 SimpleDateFormat?.get() 可空告警）
private val DATE_FMT: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm", Locale.ROOT)

/** epoch millis → 本地时区格式化（DateTimeFormatter 线程安全） */
private fun formatEpoch(ms: Long, fmt: DateTimeFormatter): String =
    fmt.format(Instant.ofEpochMilli(ms).atZone(ZoneId.systemDefault()))
