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
import com.gohiking.core.common.format.Formatters
import com.gohiking.core.data.io.ConflictPolicy
import com.gohiking.core.data.io.ImportEngine
import com.gohiking.core.data.io.ImportItemMessage
import com.gohiking.core.data.io.ImportReasonCode
import com.gohiking.core.data.io.ImportWarning
import com.gohiking.core.data.io.BackupReader.PathErrorToken
import com.gohiking.core.data.io.ImportWarningCode
import com.gohiking.core.resources.R as CoreR

/**
 * IO 进度 / 预览 / 报告三对话框（P-16/P-17/P-18，F-IO-11/24/27/30）。
 * 由 app 壳层在 SAF 回调后装配；本模块只渲染。
 *
 * H-09：core 层只产出「原因码 + 参数」，本模块负责本地化为 stringResource，
 * 遵守 DEV 决策 8（字符串集中在 core/resources）。
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
                        // L-26：此处已在 total > 0 分支内，无需再判 0
                        progress = { done.coerceAtMost(total).toFloat() / total },
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
    extraWarnings: List<ImportWarning>,
    policy: ConflictPolicy,
    onPolicyChange: (ConflictPolicy) -> Unit,
    decisions: Map<String, ConflictPolicy>,
    onDecisionChange: (String, ConflictPolicy) -> Unit,
    conflictFiles: List<String>,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(CoreR.string.io_import_preview_title)) },
        text = {
            Column(
                verticalArrangement = Arrangement.spacedBy(6.dp),
                modifier = Modifier.heightIn(max = 380.dp).verticalScroll(rememberScrollState()),
            ) {
                Text(
                    stringResource(CoreR.string.io_import_summary, preview.tripCount, preview.routeCount),
                    style = MaterialTheme.typography.bodyMedium,
                )
                if (preview.conflictTrips.isNotEmpty() ||
                    preview.conflictRoutes.isNotEmpty() ||
                    preview.suspectedDupTrips.isNotEmpty()
                ) {
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
                // F-IO-64：> 500MB 的导入必须先告知体积与预估耗时，再由用户确认（预览页本身即确认环节）
                if (preview.oversize) {
                    Text(
                        stringResource(
                            CoreR.string.io_import_oversize,
                            Formatters.bytesText(preview.totalBytes),
                            preview.fileCount,
                            Formatters.durationText(preview.estimatedSec),
                        ),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.error,
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
                        Text(
                            // M-11：分隔符由资源占位符给出，不再 Kotlin 拼接
                            stringResource(CoreR.string.io_report_item, it.name, itemReasonText(it)),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.error,
                        )
                    }
                }
                if (extraWarnings.isNotEmpty()) {
                    extraWarnings.take(3).forEach {
                        Text(
                            warningText(it),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.tertiary,
                        )
                    }
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
                            // L-10：触摸目标 48dp 下限
                            .heightIn(min = 48.dp)
                            .clickable { onPolicyChange(p) },
                    ) {
                        RadioButton(selected = p == policy, onClick = { onPolicyChange(p) })
                        Text(policyLabel(p))
                    }
                }
                // H-01：ASK 策略必须让用户真正逐条选择，否则所有冲突项都会被静默跳过
                if (policy == ConflictPolicy.ASK && conflictFiles.isNotEmpty()) {
                    Text(
                        stringResource(CoreR.string.io_import_ask_hint),
                        style = MaterialTheme.typography.titleSmall,
                        modifier = Modifier.padding(top = 8.dp),
                    )
                    conflictFiles.forEach { name ->
                        val current = decisions[name] ?: ConflictPolicy.SKIP
                        Text(name, style = MaterialTheme.typography.bodySmall)
                        Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                            listOf(
                                ConflictPolicy.SKIP,
                                ConflictPolicy.OVERWRITE,
                                ConflictPolicy.DUPLICATE,
                            ).forEach { p ->
                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    modifier = Modifier.heightIn(min = 48.dp).clickable {
                                        onDecisionChange(name, p)
                                    },
                                ) {
                                    RadioButton(selected = p == current, onClick = { onDecisionChange(name, p) })
                                    Text(policyLabel(p), style = MaterialTheme.typography.labelMedium)
                                }
                            }
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onConfirm) {
                Text(
                    // F-IO-64：超限时按钮文案改成明确的「仍要导入」，避免用户以为只是普通确认
                    stringResource(
                        if (preview.oversize) CoreR.string.io_import_confirm_oversize
                        else CoreR.string.io_import_confirm,
                    ),
                )
            }
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
                report.items
                    .filter { it.status != ImportEngine.ItemResult.Status.IMPORTED || it.reason != null }
                    .take(10)
                    .forEach { item ->
                        val reason = item.reason?.let {
                            itemReasonText(ImportItemMessage(item.name, it, item.arg))
                        } ?: stringResource(CoreR.string.io_report_ok)
                        Text(
                            text = stringResource(CoreR.string.io_report_item, item.name, reason),
                            style = MaterialTheme.typography.bodySmall,
                            color = when (item.status) {
                                ImportEngine.ItemResult.Status.FAILED -> MaterialTheme.colorScheme.error
                                ImportEngine.ItemResult.Status.SKIPPED -> MaterialTheme.colorScheme.tertiary
                                else -> MaterialTheme.colorScheme.onSurfaceVariant
                            },
                        )
                    }
                if (report.warnings.isNotEmpty()) {
                    report.warnings.take(5).forEach {
                        Text(
                            warningText(it),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.tertiary,
                        )
                    }
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

/** 原因码 → 本地化文案（含参数插值） */
@Composable
internal fun itemReasonText(item: ImportItemMessage): String = when (item.code) {
    ImportReasonCode.ROUTE_EXISTS -> stringResource(CoreR.string.imp_reason_route_exists)
    ImportReasonCode.ROUTE_CONFLICT_WAIT -> stringResource(CoreR.string.imp_reason_route_conflict)
    ImportReasonCode.CONFLICT_WAIT -> stringResource(CoreR.string.imp_reason_conflict_wait)
    ImportReasonCode.TRIP_EXISTS -> stringResource(CoreR.string.imp_reason_trip_exists)
    ImportReasonCode.DUPLICATE_SUSPECTED -> stringResource(CoreR.string.imp_reason_duplicate)
    ImportReasonCode.IMPORT_FAILED -> stringResource(CoreR.string.imp_import_failed)
    ImportReasonCode.SCHEMA_VERSION_INVALID ->
        stringResource(CoreR.string.imp_invalid_schema_version, item.arg ?: "")
    ImportReasonCode.SCHEMA_TOO_NEW -> stringResource(CoreR.string.imp_schema_too_new, item.arg ?: "")
    ImportReasonCode.SCHEMA_UNKNOWN -> stringResource(CoreR.string.imp_schema_unknown, item.arg ?: "")
    ImportReasonCode.PARSE_FAILED -> stringResource(CoreR.string.imp_parse_failed, item.arg ?: "")
    ImportReasonCode.CHECKSUM_MISMATCH -> stringResource(CoreR.string.imp_checksum_mismatch)
    ImportReasonCode.UNSAFE_ENTRY ->
        stringResource(CoreR.string.imp_unsafe_entry, pathTokenText(item.arg))
}

@Composable
internal fun warningText(w: ImportWarning): String {
    val prefix = w.fileName
    val body = when (w.code) {
        ImportWarningCode.CRS_MISSING -> stringResource(CoreR.string.imp_reason_crs_missing, prefix ?: "")
        ImportWarningCode.SUSPECTED_DUPLICATE ->
            stringResource(CoreR.string.imp_reason_duplicate_named, prefix ?: "")
        ImportWarningCode.MANIFEST_COUNT_MISMATCH ->
            stringResource(CoreR.string.imp_warn_manifest_count, *w.args.toTypedArray())
        ImportWarningCode.ENTRY_LIMIT ->
            stringResource(CoreR.string.imp_warn_entry_limit, w.args.firstOrNull() ?: "")
        ImportWarningCode.SIZE_LIMIT -> stringResource(CoreR.string.imp_warn_size_limit)
        ImportWarningCode.MANIFEST_PARSE_FAILED ->
            stringResource(CoreR.string.imp_warn_manifest_parse, w.args.firstOrNull() ?: "")
        ImportWarningCode.CHECKSUM_UNVERIFIED ->
            stringResource(CoreR.string.imp_warn_checksum_unverified, w.args.firstOrNull() ?: "")
    }
    return body
}

/** 路径安全 token → 本地化原因（H-09） */
@Composable
private fun pathTokenText(token: String?): String = when (token) {
    // ⚠ PathErrorToken 在 BackupReader 上（ZIP 读侧校验），不在 BackupBuilder 上
    PathErrorToken.ABSOLUTE -> stringResource(CoreR.string.imp_path_absolute)
    PathErrorToken.UNC -> stringResource(CoreR.string.imp_path_unc)
    PathErrorToken.DRIVE -> stringResource(CoreR.string.imp_path_drive)
    PathErrorToken.TRAVERSAL -> stringResource(CoreR.string.imp_path_traversal)
    else -> stringResource(CoreR.string.imp_path_empty)
}
