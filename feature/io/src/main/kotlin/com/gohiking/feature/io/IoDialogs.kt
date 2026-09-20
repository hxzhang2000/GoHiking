package com.gohiking.feature.io

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.gohiking.core.data.io.ConflictPolicy
import com.gohiking.core.data.io.ImportEngine
import com.gohiking.core.resources.R as CoreR

/**
 * IO 进度 / 预览 / 报告三对话框（P-16/P-17/P-18，F-IO-11/24/27/30）。
 * 由 app 壳层在 SAF 回调后装配；本模块只渲染。
 */

/** P-16 导出/处理进度（F-IO-11：进度 + 取消）；total<=0 时转圈 */
@Composable
fun IoProgressDialog(
    title: String,
    done: Int,
    total: Int,
    onCancel: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = { /* 进度中不允许点外框关闭，走取消按钮 */ },
        title = { Text(title) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                if (total > 0) {
                    LinearProgressIndicator(
                        progress = { if (total == 0) 0f else done.toFloat() / total },
                        modifier = Modifier.fillMaxWidth(),
                    )
                    Text(stringResource(CoreR.string.io_progress, done, total))
                } else {
                    LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onCancel) { Text(stringResource(CoreR.string.common_action_cancel)) }
        },
    )
}

/** P-17 导入预览（F-IO-24/26）：数量、冲突、坐标系转换告知、策略选择 */
@Composable
fun ImportPreviewDialog(
    preview: ImportEngine.ImportPreview,
    extraWarnings: List<String>,
    policy: ConflictPolicy,
    onPolicyChange: (ConflictPolicy) -> Unit,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(CoreR.string.io_import_preview_title)) },
        text = {
            Column(
                verticalArrangement = Arrangement.spacedBy(6.dp),
                modifier = Modifier.heightIn(max = 360.dp).verticalScroll(rememberScrollState()),
            ) {
                Text(
                    stringResource(CoreR.string.io_import_summary, preview.tripCount, preview.routeCount),
                    style = MaterialTheme.typography.bodyMedium,
                )
                if (preview.conflictTrips.isNotEmpty() || preview.conflictRoutes.isNotEmpty() || preview.suspectedDupTrips.isNotEmpty()) {
                    Text(
                        stringResource(
                            CoreR.string.io_import_conflicts,
                            preview.conflictTrips.size + preview.conflictRoutes.size,
                            preview.suspectedDupTrips.size,
                        ),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.tertiary,
                    )
                }
                if (preview.crsValues.any { it != "GCJ-02" }) {
                    Text(
                        stringResource(CoreR.string.io_import_crs_notice),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.tertiary,
                    )
                }
                if (preview.invalidCount > 0) {
                    Text(
                        stringResource(CoreR.string.io_import_invalid, preview.invalidCount),
                        style = MaterialTheme.typography.bodySmall,
                    )
                    preview.invalidReasons.take(5).forEach {
                        Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
                    }
                }
                extraWarnings.take(3).forEach {
                    Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.tertiary)
                }
                Text(
                    stringResource(CoreR.string.set_io_conflict_policy),
                    style = MaterialTheme.typography.titleSmall,
                    modifier = Modifier.padding(top = 8.dp),
                )
                ConflictPolicy.entries.forEach { p ->
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { onPolicyChange(p) },
                    ) {
                        RadioButton(selected = p == policy, onClick = { onPolicyChange(p) })
                        Text(policyLabel(p))
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onConfirm) { Text(stringResource(CoreR.string.io_import_confirm)) }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(CoreR.string.common_action_cancel)) }
        },
    )
}

/** P-18 导入结果报告（F-IO-30：成功/跳过/失败 + 原因清单） */
@Composable
fun ImportReportDialog(
    report: ImportEngine.ImportReport,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(CoreR.string.io_report_title)) },
        text = {
            Column(
                verticalArrangement = Arrangement.spacedBy(6.dp),
                modifier = Modifier.heightIn(max = 360.dp).verticalScroll(rememberScrollState()),
            ) {
                Text(
                    stringResource(CoreR.string.io_report_summary, report.imported, report.skipped, report.failed),
                    style = MaterialTheme.typography.bodyMedium,
                )
                report.items.filter { it.status != ImportEngine.ItemResult.Status.IMPORTED || it.reason != null }
                    .take(10)
                    .forEach { item ->
                        Text(
                            text = "${item.name}: ${item.reason ?: stringResource(CoreR.string.io_report_ok)}",
                            style = MaterialTheme.typography.bodySmall,
                            color = when (item.status) {
                                ImportEngine.ItemResult.Status.FAILED -> MaterialTheme.colorScheme.error
                                ImportEngine.ItemResult.Status.SKIPPED -> MaterialTheme.colorScheme.tertiary
                                else -> MaterialTheme.colorScheme.onSurfaceVariant
                            },
                        )
                    }
                report.warnings.take(5).forEach {
                    Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.tertiary)
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(CoreR.string.common_action_confirm)) }
        },
    )
}

@Composable
private fun policyLabel(p: ConflictPolicy): String = when (p) {
    ConflictPolicy.SKIP -> stringResource(CoreR.string.set_conflict_skip)
    ConflictPolicy.OVERWRITE -> stringResource(CoreR.string.set_conflict_overwrite)
    ConflictPolicy.DUPLICATE -> stringResource(CoreR.string.set_conflict_duplicate)
    ConflictPolicy.ASK -> stringResource(CoreR.string.set_conflict_ask)
}
