package com.gohiking.feature.history

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.gohiking.core.common.format.Formatters
import com.gohiking.core.data.stats.GainSplit
import com.gohiking.core.data.stats.KmSplit
import com.gohiking.core.resources.R as CoreR
import com.patrykandpatrick.vico.compose.cartesian.CartesianChartHost
import com.patrykandpatrick.vico.compose.cartesian.axis.rememberBottom
import com.patrykandpatrick.vico.compose.cartesian.axis.rememberStart
import com.patrykandpatrick.vico.compose.cartesian.layer.rememberColumnCartesianLayer
import com.patrykandpatrick.vico.compose.cartesian.layer.rememberLineCartesianLayer
import com.patrykandpatrick.vico.compose.cartesian.rememberCartesianChart
import com.patrykandpatrick.vico.core.cartesian.axis.HorizontalAxis
import com.patrykandpatrick.vico.core.cartesian.axis.VerticalAxis
import com.patrykandpatrick.vico.core.cartesian.data.CartesianChartModelProducer
import com.patrykandpatrick.vico.core.cartesian.data.CartesianValueFormatter
import com.patrykandpatrick.vico.core.cartesian.data.columnSeries
import com.patrykandpatrick.vico.core.cartesian.data.lineSeries

/**
 * 详情页图表（M4-A，F-HIS-23/24/25，DEV §1.3 图表库 = Vico 2.1.4——3.3.1 需 Kotlin 2.4，与 §7.1.1 基线冲突）：
 * - 海拔曲线（line）：x 轴 = 采样序号（等距降采样，近似等时距），y = 海拔 m；
 * - 每公里配速（column）：x = 第 n 公里，y = 配速秒/km（Formatters 格式化轴标）；
 * - 分段表：每公里 / 每爬升两张（纯 Compose 表格，不进图表库）。
 * Vico 要求 modelProducer 事务更新；空数据不渲染图表（F-HIS 口径：海拔不可用显示「暂无数据」）。
 */

@Composable
fun AltitudeChartCard(series: List<Pair<Double, Double>>, modifier: Modifier = Modifier) {
    ChartCard(
        title = stringResource(CoreR.string.hist_chart_altitude),
        modifier = modifier,
    ) {
        if (series.size < 2) {
            ChartEmpty()
        } else {
            val producer = remember { CartesianChartModelProducer() }
            LaunchedEffect(series) {
                producer.runTransaction {
                    lineSeries { series(series.map { it.second }) }
                }
            }
            CartesianChartHost(
                chart = rememberCartesianChart(
                    rememberLineCartesianLayer(),
                    startAxis = VerticalAxis.rememberStart(),
                    bottomAxis = HorizontalAxis.rememberBottom(),
                ),
                modelProducer = producer,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(180.dp),
            )
            // x 轴说明：总距离（序号 × 平均点距 ≈ 距离轴）
            Text(
                text = Formatters.distanceText(series.last().first),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.align(Alignment.End),
            )
        }
    }
}

@Composable
fun PaceChartCard(kmSplits: List<KmSplit>, modifier: Modifier = Modifier) {
    ChartCard(
        title = stringResource(CoreR.string.hist_chart_pace),
        modifier = modifier,
    ) {
        val paces = kmSplits.mapNotNull { it.paceSecPerKm }
        if (paces.size < 2) {
            ChartEmpty()
        } else {
            val producer = remember { CartesianChartModelProducer() }
            LaunchedEffect(paces) {
                producer.runTransaction {
                    columnSeries { series(paces) }
                }
            }
            CartesianChartHost(
                chart = rememberCartesianChart(
                    rememberColumnCartesianLayer(),
                    startAxis = VerticalAxis.rememberStart(
                        valueFormatter = CartesianValueFormatter { _, value, _ ->
                            Formatters.paceText(value.toLong()) ?: ""
                        },
                    ),
                    bottomAxis = HorizontalAxis.rememberBottom(),
                ),
                modelProducer = producer,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(180.dp),
            )
        }
    }
}

@Composable
private fun ChartCard(title: String, modifier: Modifier = Modifier, content: @Composable androidx.compose.foundation.layout.ColumnScope.() -> Unit) {
    Surface(
        tonalElevation = 2.dp,
        shape = androidx.compose.foundation.shape.RoundedCornerShape(12.dp),
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp),
    ) {
        Column(modifier = Modifier.padding(12.dp)) {
            Text(title, style = MaterialTheme.typography.titleSmall)
            content()
        }
    }
}

@Composable
private fun ChartEmpty() {
    Text(
        text = stringResource(CoreR.string.hist_chart_empty),
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(vertical = 32.dp),
    )
}

/** F-HIS-25 每公里分段表 */
@Composable
fun KmSplitsTable(kmSplits: List<KmSplit>, modifier: Modifier = Modifier) {
    SplitsTable(
        headers = listOf(
            stringResource(CoreR.string.hist_splits_col_km),
            stringResource(CoreR.string.hist_splits_col_distance),
            stringResource(CoreR.string.hist_splits_col_time),
            stringResource(CoreR.string.hist_splits_col_pace),
        ),
        rows = kmSplits.map { s ->
            listOf(
                stringResource(CoreR.string.hist_splits_km, s.index + 1),
                Formatters.distanceText(s.distanceM),
                Formatters.durationText(s.movingSec),
                s.paceSecPerKm?.let { Formatters.paceText(it) } ?: "—",
            )
        },
        modifier = modifier,
    )
}

/** F-HIS-25 每爬升分段表 */
@Composable
fun GainSplitsTable(gainSplits: List<GainSplit>, modifier: Modifier = Modifier) {
    SplitsTable(
        headers = listOf(
            stringResource(CoreR.string.hist_splits_col_segment),
            stringResource(CoreR.string.hist_splits_col_gain),
            stringResource(CoreR.string.hist_splits_col_distance),
            stringResource(CoreR.string.hist_splits_col_pace),
        ),
        rows = gainSplits.map { s ->
            listOf(
                stringResource(CoreR.string.hist_splits_gain_no, s.index + 1),
                "+" + Formatters.metersText(s.gainM),
                Formatters.distanceText(s.distanceM),
                s.paceSecPerKm?.let { Formatters.paceText(it) } ?: "—",
            )
        },
        modifier = modifier,
    )
}

@Composable
private fun SplitsTable(headers: List<String>, rows: List<List<String>>, modifier: Modifier = Modifier) {
    Surface(
        tonalElevation = 2.dp,
        shape = androidx.compose.foundation.shape.RoundedCornerShape(12.dp),
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp),
    ) {
        Column(modifier = Modifier.padding(8.dp)) {
            Row(modifier = Modifier.fillMaxWidth()) {
                headers.forEach {
                    Text(
                        text = it,
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.weight(1f),
                    )
                }
            }
            rows.forEach { row ->
                Row(modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
                    row.forEach {
                        Text(
                            text = it,
                            style = MaterialTheme.typography.bodySmall,
                            maxLines = 1,
                            modifier = Modifier.weight(1f),
                        )
                    }
                }
            }
        }
    }
}
