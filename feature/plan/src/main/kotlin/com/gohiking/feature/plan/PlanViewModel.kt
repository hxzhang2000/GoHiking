package com.gohiking.feature.plan

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.amap.api.maps.model.LatLng
import com.gohiking.core.location.LocationProvider
import com.gohiking.core.map.search.AmapSearchClient
import com.gohiking.core.map.search.SearchOutcome
import com.gohiking.core.map.search.Suggestion
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

/** 下一次点选 / 搜索落点目标（F-PLAN-03/04：先起点后终点，可手动切） */
enum class SelectTarget { START, END }

/** 搜索提示类型（文案走资源文件，VM 只给语义） */
enum class SearchHint { NONE, NO_RESULT, OFFLINE, LOCATE_FAILED }

/** 已选定的点（POI 选点带名称，空白点选 / 当前位置为 null） */
data class PlanPoint(
    val name: String?,
    val latLng: LatLng,
)

data class PlanUiState(
    val activeTarget: SelectTarget = SelectTarget.START,
    val start: PlanPoint? = null,
    val end: PlanPoint? = null,
    val searching: Boolean = false,
    val locating: Boolean = false,
    val suggestions: List<Suggestion> = emptyList(),
    val hint: SearchHint = SearchHint.NONE,
)

/**
 * 计划模式选点（P-03 最小切片，F-PLAN-02~05/07/08）。
 * 地图点选是唯一不依赖网络的兜底（PRD 6.2.1），永远可用；搜索是加速项，失败只提示不阻塞。
 */
class PlanViewModel(
    private val searchClient: AmapSearchClient,
    private val locationProvider: LocationProvider,
) : ViewModel() {

    private val _state = MutableStateFlow(PlanUiState())
    val state: StateFlow<PlanUiState> = _state.asStateFlow()

    /** F-PLAN-03/04：地图空白点选，落到当前激活目标 */
    fun onMapTap(latLng: LatLng) {
        assign(PlanPoint(name = null, latLng = latLng))
    }

    /** F-PLAN-08：底图 POI 气泡「设为起点 / 设为终点」（双回调同触以 POI 为准） */
    fun onPoiChosen(name: String, latLng: LatLng, target: SelectTarget) {
        assign(PlanPoint(name = name, latLng = latLng), forceTarget = target)
    }

    /** F-PLAN-06：拖动起终点调整位置（保留名称，只更新坐标） */
    fun onPointMoved(target: SelectTarget, latLng: LatLng) {
        _state.update { s ->
            when (target) {
                SelectTarget.START -> s.start?.let { s.copy(start = it.copy(latLng = latLng)) } ?: s
                SelectTarget.END -> s.end?.let { s.copy(end = it.copy(latLng = latLng)) } ?: s
            }
        }
    }

    /** 搜索框输入变化（防抖在 UI 层做，VM 只负责发请求） */
    fun onSearchInput(keyword: String, center: LatLng?) {
        val kw = keyword.trim()
        if (kw.isEmpty()) {
            _state.update { it.copy(suggestions = emptyList(), hint = SearchHint.NONE) }
            return
        }
        viewModelScope.launch { search(kw, center) }
    }

    /** 联想候选被选中：真实 POI 直接落点；「只是个词」→ 拿词再发一次关键词搜索（PRD 6.2.1 细节 1） */
    fun onSuggestionPicked(s: Suggestion, center: LatLng?) {
        val latLng = s.latLng
        if (latLng != null) {
            assign(PlanPoint(name = s.name, latLng = latLng))
        } else {
            viewModelScope.launch { search(s.name, center) }
        }
    }

    fun setActiveTarget(target: SelectTarget) {
        _state.update { it.copy(activeTarget = target) }
    }

    /** F-PLAN-07：以当前位置为起点 */
    fun useCurrentLocationAsStart() {
        viewModelScope.launch {
            _state.update { it.copy(locating = true, hint = SearchHint.NONE) }
            try {
                locationProvider.start(LOCATE_INTERVAL_MS)
                val fix = withTimeoutOrNull(LOCATE_TIMEOUT_MS) { locationProvider.fixes.first() }
                if (fix != null) {
                    assign(
                        PlanPoint(name = null, latLng = LatLng(fix.lat, fix.lng)),
                        forceTarget = SelectTarget.START,
                    )
                } else {
                    _state.update { it.copy(hint = SearchHint.LOCATE_FAILED) }
                }
            } catch (e: Exception) {
                _state.update { it.copy(hint = SearchHint.LOCATE_FAILED) }
            } finally {
                locationProvider.stop()
                _state.update { it.copy(locating = false) }
            }
        }
    }

    /** 重选：清空起终点，回到起点（「重选」按钮 + F-PLAN-09 换城市入口的前置） */
    fun reset() {
        _state.update {
            PlanUiState(activeTarget = SelectTarget.START)
        }
    }

    /** 输入联想 + 周边检索组合（F-PLAN-05/09）。失败 → OFFLINE（降级地图点选），无结果 → NO_RESULT */
    private suspend fun search(keyword: String, center: LatLng?) {
        _state.update { it.copy(searching = true, hint = SearchHint.NONE) }
        val outcome = searchClient.inputTips(keyword)
        val hits = when (outcome) {
            is SearchOutcome.Hits -> outcome.items
            SearchOutcome.Empty -> {
                _state.update { it.copy(searching = false, suggestions = emptyList(), hint = SearchHint.NO_RESULT) }
                return
            }
            is SearchOutcome.Failed -> {
                _state.update { it.copy(searching = false, suggestions = emptyList(), hint = SearchHint.OFFLINE) }
                return
            }
        }
        val real = hits.filter { it.latLng != null }
        val words = hits.filter { it.latLng == null }
        // 联想里只有「词」没有真实 POI：拿第一个词再发一次周边关键词检索（PRD 6.2.1 细节 1）
        val merged = if (real.isEmpty() && words.isNotEmpty()) {
            when (val poiOutcome = searchClient.poiSearch(words.first().name, center = center)) {
                is SearchOutcome.Hits -> poiOutcome.items
                else -> emptyList()
            }
        } else {
            real
        }
        _state.update {
            if (merged.isEmpty()) {
                it.copy(searching = false, suggestions = emptyList(), hint = SearchHint.NO_RESULT)
            } else {
                it.copy(searching = false, suggestions = merged.take(MAX_SUGGESTIONS), hint = SearchHint.NONE)
            }
        }
    }

    /** 落点：按激活目标写入；写完自动切到另一个未设置的目标（F-PLAN-03→04 顺序引导） */
    private fun assign(point: PlanPoint, forceTarget: SelectTarget? = null) {
        _state.update { s ->
            val target = forceTarget ?: s.activeTarget
            when (target) {
                SelectTarget.START -> s.copy(
                    start = point,
                    activeTarget = if (s.end == null) SelectTarget.END else s.activeTarget,
                    suggestions = emptyList(),
                    hint = SearchHint.NONE,
                )
                SelectTarget.END -> s.copy(
                    end = point,
                    activeTarget = if (s.start == null) SelectTarget.START else s.activeTarget,
                    suggestions = emptyList(),
                    hint = SearchHint.NONE,
                )
            }
        }
    }

    private companion object {
        const val LOCATE_INTERVAL_MS = 1_000L
        const val LOCATE_TIMEOUT_MS = 10_000L
        const val MAX_SUGGESTIONS = 6
    }
}
