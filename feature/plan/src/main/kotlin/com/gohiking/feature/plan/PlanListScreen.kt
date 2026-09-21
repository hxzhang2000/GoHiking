package com.gohiking.feature.plan

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
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
import androidx.compose.runtime.remember
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
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
import com.gohiking.core.database.entity.UNNAMED_ROUTE_PLACEHOLDER
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
    // L-06：导入的线路缺 name 时库里存的是中性哨兵值，这里替换成本地化文案
    val unnamedRoute = stringResource(CoreR.string.plan_unnamed_route)
    fun displayName(raw: String): String =
        if (raw == UNNAMED_ROUTE_PLACEHOLDER) unnamedRoute else raw
    val viewModel: PlanListViewModel = viewModel(
        factory = viewModelFactory {
            initializer { PlanListViewModel(plannedRouteDao) }
        },
    )
    val items by viewModel.items.collectAsStateWithLifecycle()
    // L-13：只保存 id（PlanListItem 不是 Bundle 可保存类型），重建后按 id 复原弹窗
    var renameId by rememberSaveable { mutableStateOf<String?>(null) }
    var deleteId by rememberSaveable { mutableStateOf<String?>(null) }
    val renameTarget = renameId?.let { id -> items.firstOrNull { it.id == id } }
    val deleteTarget = deleteId?.let { id -> items.firstOrNull { it.id == id } }

    // N-11：targetSdk 35 强制 edge-to-edge，状态栏会压住顶部的返回按钮与标题
    Column(modifier = modifier.fillMaxSize().statusBarsPadding().padding(16.dp)) {
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
                // M：无 key 时列表变化无法差分，行内状态会错位
                items(items = items, key = { it.id }) { item ->
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { renameId = item.id } // F-PLAN-42 重命名入口
                            .padding(vertical = 10.dp),
                    ) {
                        Text(item.name, style = MaterialTheme.typography.titleSmall)
                        // M-11：分隔符与「—」走资源串；H-06：爬升为 null 时显示未知而非 0
                        val ascentText = item.totalAscentM?.let { Formatters.metersText(it) }
                            ?: stringResource(CoreR.string.common_stat_unknown)
                        Text(
                            text = listOf(
                                Formatters.distanceText(item.totalDistanceM),
                                stringResource(CoreR.string.plan_estimated_ascent, ascentText),
                                formatEpoch(item.createdAt, DATE_FMT),
                            ).joinToString(stringResource(CoreR.string.plan_stat_sep)),
                            style = MaterialTheme.typography.bodySmall,
                        )
                        TextButton(onClick = { deleteId = item.id }) {
                            Text(stringResource(CoreR.string.plan_list_delete))
                        }
                        onExportRoute?.let { export ->
                            TextButton(onClick = { export(item.id, displayName(item.name)) }) {
                                Text(stringResource(CoreR.string.plan_export_route))
                            }
                        }
                    }
                    HorizontalDivider()
                }
            }
        }
    }

    // F-PLAN-42：重命名对话框（默认原名）
    renameTarget?.let { target ->
        // 哨兵值不回填到输入框：否则用户一点确定就把「未命名线路」写进库
        var nameInput by remember(target.id) {
            mutableStateOf(if (target.name == UNNAMED_ROUTE_PLACEHOLDER) "" else target.name)
        }
        AlertDialog(
            onDismissRequest = { renameId = null },
            title = { Text(stringResource(CoreR.string.plan_list_rename)) },
            text = {
                OutlinedTextField(
                    value = nameInput,
                    onValueChange = { nameInput = it },
                    singleLine = true,
                )
            },
            confirmButton = {
                // L-14：空输入此前「点了确定却没反应」，改为禁用按钮
                TextButton(
                    onClick = {
                        viewModel.rename(target.id, nameInput)
                        renameId = null
                    },
                    enabled = nameInput.isNotBlank(),
                ) { Text(stringResource(CoreR.string.common_action_confirm)) }
            },
            dismissButton = {
                TextButton(onClick = { renameId = null }) {
                    Text(stringResource(CoreR.string.common_action_cancel))
                }
            },
        )
    }

    // F-PLAN-42：删除确认
    deleteTarget?.let { target ->
        AlertDialog(
            onDismissRequest = { deleteId = null },
            title = { Text(stringResource(CoreR.string.plan_list_delete)) },
            text = { Text(target.name) },
            confirmButton = {
                TextButton(onClick = {
                    viewModel.delete(target.id)
                    deleteId = null
                }) { Text(stringResource(CoreR.string.common_action_confirm)) }
            },
            dismissButton = {
                TextButton(onClick = { deleteId = null }) {
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
