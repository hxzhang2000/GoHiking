package com.gohiking.feature.settings

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Switch
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
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.gohiking.core.resources.R as CoreR

/** 数据组操作入口（M3-D2：SAF 导出/备份/导入由 app 壳层实现，设置页只放按钮） */
data class IoActions(
    val onExportAll: () -> Unit,
    val onBackup: () -> Unit,
    val onImport: () -> Unit,
)

/**
 * 设置页（P-14，DEV §5.2）：6 分组 + 分组内 Switch/Select/Number 三种行。
 * 选择项用对话框单选（避免下拉菜单在 TV/手机上的焦点问题）；数字项用 ± 步进（v1 简化，DEV §5.2）。
 * 导出/导入入口由 M3-D feature:io 接入后追加到「数据」分组。
 */
@Composable
fun SettingsScreen(
    settingsRepository: com.gohiking.core.datastore.SettingsRepository,
    hasBarometer: Boolean,
    onLanguageChanged: (String) -> Unit,
    onBack: () -> Unit,
    ioActions: IoActions? = null,
    modifier: Modifier = Modifier,
) {
    val viewModel: SettingsViewModel = viewModel(
        factory = viewModelFactory {
            initializer { SettingsViewModel(settingsRepository, hasBarometer) }
        },
    )
    val state by viewModel.state.collectAsStateWithLifecycle()
    // N-12：语言切换由 Composable 侧消费一次性事件，ViewModel 不再持有 Activity 回调
    LaunchedEffect(viewModel) {
        viewModel.languageChanged.collect { onLanguageChanged(it) }
    }
    var selectTarget by remember { mutableStateOf<SettingItemUi.Select?>(null) }
    var showResetConfirm by remember { mutableStateOf(false) }

    // N-11：targetSdk 35 强制 edge-to-edge，状态栏会压住顶部的返回按钮与标题
    Column(modifier = modifier.fillMaxSize().statusBarsPadding().padding(16.dp)) {
        Row(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text(
                text = stringResource(CoreR.string.set_title),
                style = MaterialTheme.typography.titleMedium,
                modifier = Modifier.weight(1f),
            )
            Button(onClick = onBack) {
                Text(stringResource(CoreR.string.plan_back))
            }
        }

        if (state.barometerHint) {
            Text(
                text = stringResource(CoreR.string.set_barometer_hint),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.tertiary,
                modifier = Modifier.padding(top = 8.dp),
            )
        }

        LazyColumn(modifier = Modifier.padding(top = 8.dp)) {
            state.groups.forEach { group ->
                item(key = "header_${group.titleRes}") {
                    Text(
                        text = stringResource(group.titleRes),
                        style = MaterialTheme.typography.titleSmall,
                        color = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.padding(top = 16.dp, bottom = 4.dp),
                    )
                }
                items(group.items.size) { i ->
                    val item = group.items[i]
                    when (item) {
                        is SettingItemUi.Switch -> SwitchRow(item) { viewModel.toggle(item.key, it) }
                        is SettingItemUi.Select -> SelectRow(item) { selectTarget = item }
                        is SettingItemUi.Number -> NumberRow(item) { delta ->
                            viewModel.numberChanged(
                                item.key,
                                (item.value + delta).coerceIn(item.min, item.max),
                            )
                        }
                    }
                    if (i < group.items.size - 1) HorizontalDivider()
                }
            }
            item(key = "io_actions") {
                ioActions?.let { actions ->
                    Column(modifier = Modifier.padding(top = 8.dp)) {
                        TextButton(onClick = actions.onExportAll) {
                            Text(stringResource(CoreR.string.io_export_all))
                        }
                        TextButton(onClick = actions.onBackup) {
                            Text(stringResource(CoreR.string.io_backup))
                        }
                        TextButton(onClick = actions.onImport) {
                            Text(stringResource(CoreR.string.io_import))
                        }
                    }
                }
            }
            item(key = "reset") {
                TextButton(onClick = { showResetConfirm = true }) {
                    Text(stringResource(CoreR.string.set_reset_default))
                }
            }
        }
    }

    // Select 行 → 单选对话框（F-SET-02 即时写入）
    selectTarget?.let { target ->
        AlertDialog(
            onDismissRequest = { selectTarget = null },
            title = { Text(stringResource(target.titleRes)) },
            text = {
                Column {
                    target.options.forEach { option ->
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable {
                                    viewModel.select(target.key, option.value)
                                    selectTarget = null
                                },
                        ) {
                            RadioButton(
                                selected = option.value == target.selected,
                                onClick = {
                                    viewModel.select(target.key, option.value)
                                    selectTarget = null
                                },
                            )
                            Text(stringResource(option.labelRes))
                        }
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = { selectTarget = null }) {
                    Text(stringResource(CoreR.string.common_action_cancel))
                }
            },
        )
    }

    // F-SET-03 恢复默认确认
    if (showResetConfirm) {
        AlertDialog(
            onDismissRequest = { showResetConfirm = false },
            title = { Text(stringResource(CoreR.string.set_reset_default)) },
            text = { Text(stringResource(CoreR.string.set_reset_confirm)) },
            confirmButton = {
                TextButton(onClick = {
                    viewModel.resetToDefault()
                    showResetConfirm = false
                }) { Text(stringResource(CoreR.string.common_action_confirm)) }
            },
            dismissButton = {
                TextButton(onClick = { showResetConfirm = false }) {
                    Text(stringResource(CoreR.string.common_action_cancel))
                }
            },
        )
    }
}

@Composable
private fun SwitchRow(item: SettingItemUi.Switch, onChecked: (Boolean) -> Unit) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 8.dp),
    ) {
        Text(stringResource(item.titleRes), modifier = Modifier.weight(1f))
        Switch(checked = item.checked, onCheckedChange = onChecked)
    }
}

@Composable
private fun SelectRow(item: SettingItemUi.Select, onClick: () -> Unit) {
    val selectedLabel = item.options
        .firstOrNull { it.value == item.selected }
        ?.let { stringResource(it.labelRes) }
        ?: item.selected
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(vertical = 14.dp),
    ) {
        Text(stringResource(item.titleRes), modifier = Modifier.weight(1f))
        Text(
            text = selectedLabel,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.primary,
        )
    }
}

@Composable
private fun NumberRow(item: SettingItemUi.Number, onDelta: (Int) -> Unit) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 8.dp),
    ) {
        Text(stringResource(item.titleRes), modifier = Modifier.weight(1f))
        TextButton(onClick = { onDelta(-item.step) }, enabled = item.value > item.min) {
            Text("−")
        }
        Text(
            text = "${item.value} ${stringResource(item.suffixRes)}",
            style = MaterialTheme.typography.bodyMedium,
        )
        TextButton(onClick = { onDelta(item.step) }, enabled = item.value < item.max) {
            Text("+")
        }
    }
}
