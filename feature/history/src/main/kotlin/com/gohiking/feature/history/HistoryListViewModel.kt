package com.gohiking.feature.history

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.gohiking.core.common.format.DisplayUnitProvider
import com.gohiking.core.common.format.Formatters
import com.gohiking.core.data.repository.TripRepository
import com.gohiking.core.database.entity.TripEntity
import com.gohiking.core.database.entity.TripSummaryRow
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/** epoch millis → 本地时区格式化（DateTimeFormatter 线程安全） */
private fun formatEpoch(ms: Long, fmt: DateTimeFormatter): String =
    fmt.format(Instant.ofEpochMilli(ms).atZone(ZoneId.systemDefault()))

/**
 * P-10 记录列表（DEV §5.2）。M1 最小切片：
 * - 列表 / 汇总 / 按月分组 / 名称搜索 / 删除（级联）；
 * - BatchDelete / BatchExport / Filter 依赖 M3 导出与多选 UI，届时补；
 * - 轨迹缩略图几何（thumbPoints）属 M4 渲染优化，字段占位。
 */
data class TripRowUi(
    val id: String,
    val name: String,
    val dateText: String, // yyyy-MM-dd HH:mm（数字中立格式，M4 i18n 校对）
    val monthKey: String, // yyyy-MM，groupByMonth 时的分组键
    val distanceText: String,
    val durationText: String,
    val ascentText: String,
    val paceText: String?, // null → 「—」（F-HIS-34）
    val thumbPoints: List<Double> = emptyList(), // 占位：缩放后轨迹缩略图折线（lat,lng 交替），M4 实现
)

data class ListSummaryUi(
    val count: Int,
    val totalDistanceText: String,
    val totalDurationText: String,
    val totalAscentText: String,
)

data class HistoryUiState(
    val trips: List<TripRowUi> = emptyList(),
    val summary: ListSummaryUi = ListSummaryUi(0, "0 m", "0:00:00", "0 m"),
    val groupByMonth: Boolean = true,
    val query: String = "",
    val empty: Boolean = true,
    val swipedId: String? = null, // M3 多选/滑动删除时启用
)

sealed interface HistoryListEvent {
    /** 打开详情（P-11 属 M4；M1 阶段无消费方，保留事件占位） */
    data class Open(val id: String) : HistoryListEvent

    data class Delete(val id: String) : HistoryListEvent
    data class Search(val q: String) : HistoryListEvent
    data object ToggleGroupByMonth : HistoryListEvent
}

class HistoryListViewModel(private val repo: TripRepository) : ViewModel() {

    // N-07：缓存**原始 entity** 而不是已格式化的 TripRowUi。此前把 distanceText 等文案
    // 在 init 里一次性算好存进 StateFlow，单位设置变更后没有任何东西触发重算。
    private val rawTrips = MutableStateFlow<List<TripEntity>>(emptyList())
    private val rawSummary = MutableStateFlow<TripSummaryRow?>(null)
    private val query = MutableStateFlow("")
    private val _state = MutableStateFlow(HistoryUiState())
    val state: StateFlow<HistoryUiState> = _state.asStateFlow()

    init {
        viewModelScope.launch {
            repo.observeFinished().collect { list ->
                rawTrips.value = list
                recompute()
            }
        }
        viewModelScope.launch {
            repo.observeSummary().collect { row ->
                rawSummary.value = row
                recompute()
            }
        }
        // N-07：订阅单位变化并重算全部文案。本 VM 挂在 Activity 的 ViewModelStore 上被复用，
        // 单位变更不会 recreate（只有语言变更才会），DB 也不会重新发射——不订阅就永远停在旧单位。
        viewModelScope.launch {
            DisplayUnitProvider.unitsFlow.collect { recompute() }
        }
        viewModelScope.launch {
            query.collect { q ->
                _state.update { it.copy(query = q) }
                recompute()
            }
        }
    }

    fun onEvent(event: HistoryListEvent) {
        when (event) {
            is HistoryListEvent.Open -> Unit // P-11 详情页 M4 接入
            is HistoryListEvent.Delete -> viewModelScope.launch { repo.delete(event.id) }
            is HistoryListEvent.Search -> query.value = event.q
            HistoryListEvent.ToggleGroupByMonth ->
                _state.update { it.copy(groupByMonth = !it.groupByMonth) }
        }
    }

    /** 名称包含（不区分大小写）；分组与否不影响 trips 顺序（startTime DESC，UI 渲染时分组） */
    private fun recompute() {
        val q = query.value.trim()
        val src = rawTrips.value
        val filtered = if (q.isEmpty()) {
            src
        } else {
            src.filter { it.name.contains(q, ignoreCase = true) }
        }
        // N-07：格式化在这里做（而不是缓存结果），单位/配速设置变更才能随 recompute 生效
        val rows = filtered.map { it.toRowUi() }
        val summary = rawSummary.value?.toUi()
        _state.update {
            it.copy(
                trips = rows,
                empty = rows.isEmpty(),
                summary = summary ?: it.summary,
            )
        }
    }

    private fun TripEntity.toRowUi(): TripRowUi = TripRowUi(
        id = id,
        name = name,
        dateText = formatEpoch(startTime, DATE_FORMAT),
        monthKey = formatEpoch(startTime, MONTH_FORMAT),
        distanceText = Formatters.distanceText(distanceM),
        durationText = Formatters.durationText(durationSec),
        ascentText = Formatters.metersText(totalAscentM),
        paceText = Formatters.paceText(avgPaceSecPerKm),
    )

    private fun TripSummaryRow.toUi(): ListSummaryUi = ListSummaryUi(
        count = c,
        totalDistanceText = Formatters.distanceText(d),
        totalDurationText = Formatters.durationText(t),
        totalAscentText = Formatters.metersText(a),
    )

    private companion object {
        // DateTimeFormatter 线程安全且不可变，无需 ThreadLocal（K2 起 SimpleDateFormat?.get() 可空告警）
        val DATE_FORMAT: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm", Locale.ROOT)
        val MONTH_FORMAT: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyy-MM", Locale.ROOT)
    }
}
