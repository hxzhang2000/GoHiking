package com.gohiking.feature.plan

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
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.Route
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.gohiking.core.common.format.Formatters
import com.gohiking.core.database.entity.UNNAMED_ROUTE_PLACEHOLDER
import com.gohiking.core.designsystem.theme.GhColors
import com.gohiking.core.resources.R as CoreR
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

/**
 * 计划线路列表页（P-07，F-PLAN-41/42/43）：原型结构对齐——
 * appbar（返回 + 标题 + 新建）、非空态「新建线路」主按钮 + 白卡列表（缩略图/名称/时间/距离/爬升/难度），
 * 空态居中插画 + 新建入口。行点击 → 重命名；删除/导出为行内次要操作。
 */
@Composable
fun PlanListScreen(
    plannedRouteDao: com.gohiking.core.database.dao.PlannedRouteDao,
    onBack: () -> Unit,
    onNewRoute: (() -> Unit)? = null, // P-07：appbar「+」/ 新建线路 → 选点页 P-03
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
    val routes by viewModel.items.collectAsStateWithLifecycle()
    // L-13：只保存 id（PlanListItem 不是 Bundle 可保存类型），重建后按 id 复原弹窗
    var renameId by rememberSaveable { mutableStateOf<String?>(null) }
    var deleteId by rememberSaveable { mutableStateOf<String?>(null) }
    val renameTarget = renameId?.let { id -> routes.firstOrNull { it.id == id } }
    val deleteTarget = deleteId?.let { id -> routes.firstOrNull { it.id == id } }

    Column(
        modifier = modifier
            .fillMaxSize()
            .background(GhColors.Bg)
            .statusBarsPadding()
            .navigationBarsPadding(),
    ) {
        // ---- P-07 appbar：返回 + 标题 + 新建 ----
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(
                modifier = Modifier
                    .size(36.dp)
                    .clip(CircleShape)
                    .clickable(onClick = onBack),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    Icons.AutoMirrored.Filled.ArrowBack,
                    contentDescription = stringResource(CoreR.string.plan_back),
                    tint = GhColors.TextPrimary,
                    modifier = Modifier.size(20.dp),
                )
            }
            Spacer(Modifier.width(8.dp))
            Text(
                text = stringResource(CoreR.string.plan_list_title),
                fontSize = 17.sp,
                fontWeight = FontWeight.Bold,
                modifier = Modifier.weight(1f),
            )
            if (onNewRoute != null) {
                Box(
                    modifier = Modifier
                        .size(36.dp)
                        .clip(CircleShape)
                        .clickable(onClick = onNewRoute),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(
                        Icons.Filled.Add,
                        contentDescription = stringResource(CoreR.string.plan_new_route),
                        tint = GhColors.TextPrimary,
                        modifier = Modifier.size(22.dp),
                    )
                }
            }
        }

        if (routes.isEmpty()) {
            // ---- P-07 空态：居中图标 + 标题/说明 + 新建入口 ----
            Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Box(
                        modifier = Modifier
                            .size(72.dp)
                            .clip(CircleShape)
                            .background(GhColors.Surface2),
                        contentAlignment = Alignment.Center,
                    ) {
                        Icon(
                            Icons.Filled.Route,
                            contentDescription = null,
                            tint = GhColors.TextTertiary,
                            modifier = Modifier.size(34.dp),
                        )
                    }
                    Spacer(Modifier.height(14.dp))
                    Text(
                        text = stringResource(CoreR.string.plan_list_empty_title),
                        fontSize = 15.sp,
                        fontWeight = FontWeight.Medium,
                    )
                    Spacer(Modifier.height(6.dp))
                    Text(
                        text = stringResource(CoreR.string.plan_list_empty_desc),
                        fontSize = 12.sp,
                        color = GhColors.TextSecondary,
                        modifier = Modifier.padding(horizontal = 40.dp),
                    )
                    if (onNewRoute != null) {
                        Spacer(Modifier.height(22.dp))
                        Row(
                            modifier = Modifier
                                .clip(RoundedCornerShape(12.dp))
                                .background(GhColors.Primary)
                                .clickable(onClick = onNewRoute)
                                .padding(horizontal = 18.dp, vertical = 12.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Icon(
                                Icons.Filled.Add,
                                contentDescription = null,
                                tint = Color.White,
                                modifier = Modifier.size(16.dp),
                            )
                            Spacer(Modifier.width(6.dp))
                            Text(
                                text = stringResource(CoreR.string.plan_new_route),
                                color = Color.White,
                                fontSize = 15.sp,
                                fontWeight = FontWeight.Medium,
                            )
                        }
                    }
                }
            }
        } else {
            // ---- P-07 非空：新建主按钮 + 白卡列表 ----
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 12.dp, vertical = 4.dp)
                    .clip(RoundedCornerShape(12.dp))
                    .background(GhColors.Primary)
                    .clickable { onNewRoute?.invoke() }
                    .padding(horizontal = 14.dp, vertical = 12.dp),
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        Icons.Filled.Add,
                        contentDescription = null,
                        tint = Color.White,
                        modifier = Modifier.size(16.dp),
                    )
                    Spacer(Modifier.width(6.dp))
                    Text(
                        text = stringResource(CoreR.string.plan_new_route),
                        color = Color.White,
                        fontSize = 15.sp,
                        fontWeight = FontWeight.Medium,
                    )
                }
            }
            Surface(
                shape = RoundedCornerShape(12.dp),
                color = GhColors.Surface,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(12.dp),
            ) {
                LazyColumn(modifier = Modifier.fillMaxWidth()) {
                    items(items = routes, key = { it.id }) { item ->
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable { renameId = item.id } // F-PLAN-42 重命名入口
                                .padding(horizontal = 12.dp, vertical = 12.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            RouteThumb(modifier = Modifier.size(56.dp))
                            Spacer(Modifier.width(12.dp))
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    text = displayName(item.name),
                                    fontSize = 15.sp,
                                    fontWeight = FontWeight.SemiBold,
                                    maxLines = 1,
                                )
                                Spacer(Modifier.height(2.dp))
                                Text(
                                    text = formatEpoch(item.createdAt, DATE_FMT),
                                    fontSize = 11.sp,
                                    color = GhColors.TextTertiary,
                                )
                                Spacer(Modifier.height(6.dp))
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Text(
                                        text = Formatters.distanceText(item.totalDistanceM),
                                        fontSize = 11.sp,
                                        color = GhColors.TextSecondary,
                                    )
                                    Spacer(Modifier.width(10.dp))
                                    // M-11/H-06：爬升未知显示「—」，绝不用 0 冒充
                                    val ascentText = item.totalAscentM?.let { Formatters.metersText(it) }
                                        ?: stringResource(CoreR.string.common_stat_unknown)
                                    Text(
                                        text = stringResource(CoreR.string.plan_estimated_ascent, ascentText),
                                        fontSize = 11.sp,
                                        color = GhColors.TextSecondary,
                                    )
                                    item.difficulty?.let { diff ->
                                        Spacer(Modifier.width(10.dp))
                                        DifficultyTag(diff)
                                    }
                                }
                            }
                            Icon(
                                Icons.Filled.KeyboardArrowRight,
                                contentDescription = null,
                                tint = GhColors.TextTertiary,
                                modifier = Modifier.size(20.dp),
                            )
                        }
                        // 次要操作：导出 / 删除（F-PLAN-42/43；原型无此项，弱化为次要文字按钮）
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(start = 80.dp, end = 12.dp, bottom = 10.dp),
                            horizontalArrangement = Arrangement.spacedBy(16.dp),
                        ) {
                            onExportRoute?.let { export ->
                                Text(
                                    text = stringResource(CoreR.string.plan_export_route),
                                    fontSize = 12.sp,
                                    color = GhColors.TextSecondary,
                                    modifier = Modifier.clickable { export(item.id, displayName(item.name)) },
                                )
                            }
                            Text(
                                text = stringResource(CoreR.string.plan_list_delete),
                                fontSize = 12.sp,
                                color = GhColors.Danger,
                                modifier = Modifier.clickable { deleteId = item.id },
                            )
                        }
                        if (item.id != routes.last().id) {
                            HorizontalDivider(color = GhColors.Line2, thickness = 1.dp)
                        }
                    }
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
            text = { Text(displayName(target.name)) },
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

/** P-07 列表缩略图：去程蓝实线 + 返程深蓝虚线（原型 list-item .thumb SVG 同款走势）。 */
@Composable
private fun RouteThumb(modifier: Modifier = Modifier) {
    Canvas(modifier = modifier) {
        val w = size.width
        val h = size.height
        val out = listOf(
            Offset(0.08f * w, 0.92f * h),
            Offset(0.30f * w, 0.74f * h),
            Offset(0.48f * w, 0.62f * h),
            Offset(0.62f * w, 0.50f * h),
            Offset(0.74f * w, 0.42f * h),
        )
        val ret = listOf(
            Offset(0.74f * w, 0.42f * h),
            Offset(0.86f * w, 0.56f * h),
            Offset(0.92f * w, 0.70f * h),
        )
        val stroke = 2.5.dp.toPx()
        drawPath(
            path = Path().apply {
                moveTo(out[0].x, out[0].y)
                out.drop(1).forEach { lineTo(it.x, it.y) }
            },
            color = GhColors.PlanOutbound,
            style = Stroke(width = stroke),
        )
        drawPath(
            path = Path().apply {
                moveTo(ret[0].x, ret[0].y)
                ret.drop(1).forEach { lineTo(it.x, it.y) }
            },
            color = GhColors.PlanReturn,
            style = Stroke(
                width = stroke,
                pathEffect = PathEffect.dashPathEffect(floatArrayOf(8f, 6f)),
            ),
        )
    }
}

/** P-07 难度标签（原型 tag.g/tag.o：绿底深绿字 / 橙底深橙字）。 */
@Composable
private fun DifficultyTag(diff: String) {
    val (bg, fg, label) = when (diff) {
        "EASY", "MODERATE" -> Triple(GhColors.TagGreenBg, GhColors.TagGreenFg, difficultyText(diff))
        else -> Triple(GhColors.TagOrangeBg, GhColors.TagOrangeFg, difficultyText(diff))
    }
    Box(
        modifier = Modifier
            .clip(RoundedCornerShape(6.dp))
            .background(bg)
            .padding(horizontal = 6.dp, vertical = 2.dp),
    ) {
        Text(text = label, fontSize = 10.sp, color = fg)
    }
}

@Composable
private fun difficultyText(diff: String): String = when (diff) {
    "EASY" -> stringResource(CoreR.string.diff_easy)
    "MODERATE" -> stringResource(CoreR.string.diff_moderate)
    "HARD" -> stringResource(CoreR.string.diff_hard)
    "CHALLENGING" -> stringResource(CoreR.string.diff_challenging)
    else -> stringResource(CoreR.string.common_stat_unknown)
}

// DateTimeFormatter 线程安全且不可变，无需 ThreadLocal（K2 起 SimpleDateFormat?.get() 可空告警）
private val DATE_FMT: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm", Locale.ROOT)

/** epoch millis → 本地时区格式化（DateTimeFormatter 线程安全） */
private fun formatEpoch(ms: Long, fmt: DateTimeFormatter): String =
    fmt.format(Instant.ofEpochMilli(ms).atZone(ZoneId.systemDefault()))