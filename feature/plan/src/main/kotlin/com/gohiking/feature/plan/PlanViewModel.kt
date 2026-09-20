package com.gohiking.feature.plan

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.amap.api.maps.model.LatLng
import com.gohiking.core.common.geo.PolylineJson
import com.gohiking.core.common.geo.PolylineSimplifier
import com.gohiking.core.common.result.GhResult
import com.gohiking.core.database.dao.PlannedRouteDao
import com.gohiking.core.database.entity.PlannedLegEntity
import com.gohiking.core.database.entity.PlannedRouteEntity
import com.gohiking.core.elevation.RouteEvaluator
import com.gohiking.core.elevation.RouteMetrics
import com.gohiking.core.location.LocationProvider
import com.gohiking.core.map.route.PlannedPath
import com.gohiking.core.map.route.RouteSearchClient
import com.gohiking.core.map.search.AmapSearchClient
import com.gohiking.core.map.search.SearchOutcome
import com.gohiking.core.map.search.Suggestion
import com.gohiking.core.model.Difficulty
import com.gohiking.core.model.LatLngValue
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.UUID
import kotlin.math.abs
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

/**
 * 推荐候选（F-PLAN-10~12）。metrics 在折线上屏后后台补齐（MA-1：首条 ≤3s 上屏不阻塞）；
 * 高程不可用时 metrics 为 null → 爬升显示「—」，难度不编造。
 */
data class RouteCandidate(
    val path: PlannedPath,
    val metrics: RouteMetrics? = null,
    val difficulty: Difficulty? = null,
)

data class PlanUiState(
    val activeTarget: SelectTarget = SelectTarget.START,
    val start: PlanPoint? = null,
    val end: PlanPoint? = null,
    val searching: Boolean = false,
    val locating: Boolean = false,
    val suggestions: List<Suggestion> = emptyList(),
    val hint: SearchHint = SearchHint.NONE,
    // ── 自动推荐（F-PLAN-10~16）──
    val planning: Boolean = false,
    val planFailed: Boolean = false, // F-PLAN-20 引导入口
    val candidates: List<RouteCandidate> = emptyList(),
    val highlightIndex: Int = -1, // F-PLAN-13 点击卡片高亮
    val chosenIndex: Int = -1, // F-PLAN-14 已选定为计划线路（蓝实线）
    val chosenRouteId: String? = null,
)

/**
 * 计划模式（P-03 选点 + P-04 推荐，F-PLAN-01~16）。
 * 地图点选是唯一不依赖网络的兜底（PRD 6.2.1），永远可用；搜索是加速项，失败只提示不阻塞。
 * 推荐链路（DEV §4.8.1）：起终点齐 → RouteSearchClient（V2 三备选 → V1 回退）→ 折线上屏 →
 * 后台 RouteEvaluator 评估（爬升/难度，DEM 估算）→ 卡片列表。
 */
class PlanViewModel(
    private val searchClient: AmapSearchClient,
    private val routeClient: RouteSearchClient,
    private val locationProvider: LocationProvider,
    private val routeDao: PlannedRouteDao,
    private val queryElevation: suspend (List<LatLngValue>) -> GhResult<List<Double?>>,
) : ViewModel() {

    private val _state = MutableStateFlow(PlanUiState())
    val state: StateFlow<PlanUiState> = _state.asStateFlow()

    private var planJob: kotlinx.coroutines.Job? = null

    /** F-PLAN-03/04：地图空白点选，落到当前激活目标 */
    fun onMapTap(latLng: LatLng) {
        assign(PlanPoint(name = null, latLng = latLng))
    }

    /** F-PLAN-08：底图 POI 气泡「设为起点 / 设为终点」（双回调同触以 POI 为准） */
    fun onPoiChosen(name: String, latLng: LatLng, target: SelectTarget) {
        assign(PlanPoint(name = name, latLng = latLng), forceTarget = target)
    }

    /** F-PLAN-06：拖动起终点调整位置（保留名称，只更新坐标；改动后重规划） */
    fun onPointMoved(target: SelectTarget, latLng: LatLng) {
        _state.update { s ->
            when (target) {
                SelectTarget.START -> s.start?.let { s.copy(start = it.copy(latLng = latLng)) } ?: s
                SelectTarget.END -> s.end?.let { s.copy(end = it.copy(latLng = latLng)) } ?: s
            }
        }
        replan()
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

    /** 重选：清空起终点与全部候选，回到起点 */
    fun reset() {
        planJob?.cancel()
        _state.update { PlanUiState(activeTarget = SelectTarget.START) }
    }

    /** F-PLAN-13：点击卡片 → 该线路高亮，其余淡出 */
    fun onCandidateClicked(index: Int) {
        _state.update { it.copy(highlightIndex = index) }
    }

    /** F-PLAN-14：选择此线路 → 落库为计划线路（蓝实线，F-PLAN-15） */
    fun chooseCandidate(index: Int) {
        val s = _state.value
        val c = s.candidates.getOrNull(index) ?: return
        if (s.chosenRouteId != null) return // 已选定，防重复落库
        viewModelScope.launch {
            val routeId = UUID.randomUUID().toString()
            val legId = UUID.randomUUID().toString()
            val now = System.currentTimeMillis()
            // polylineJson 存抽稀后折线（PRD 7.1）；记录轨迹的「原始点入库」规则不适用于计划线
            val simplified = PolylineSimplifier.simplify(
                c.path.points.map { LatLngValue(it.latitude, it.longitude) },
                POLYLINE_EPSILON_M,
            )
            val leg = PlannedLegEntity(
                id = legId,
                plannedRouteId = routeId,
                legType = "OUTBOUND",
                distanceM = c.path.distanceM.toDouble(),
                ascentM = c.metrics?.ascentM ?: 0.0,
                descentM = c.metrics?.descentM ?: 0.0,
                estimatedMin = (c.path.durationS / 60).toInt(),
                difficulty = (c.difficulty ?: Difficulty.MODERATE).name,
                polylineJson = PolylineJson.encode(simplified),
            )
            val route = PlannedRouteEntity(
                id = routeId,
                name = routeName(s),
                note = null,
                source = "AUTO",
                createdAt = now,
                totalDistanceM = c.path.distanceM.toDouble(),
                totalAscentM = c.metrics?.ascentM ?: 0.0,
                totalDescentM = c.metrics?.descentM ?: 0.0,
            )
            routeDao.saveWithLegs(route, listOf(leg))
            _state.update { it.copy(chosenIndex = index, chosenRouteId = routeId) }
        }
    }

    /** F-PLAN-16：重新规划 */
    fun replan() {
        planJob?.cancel()
        val s = _state.value
        if (s.start != null && s.end != null) planRoutes()
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

    /**
     * F-PLAN-10：起终点齐后自动规划。折线拿到立即上屏（MA-1 首条 ≤3s），爬升/难度后台补齐；
     * 全部失败 → planFailed（引导 F-PLAN-20 手动打点，不出现空白页）。
     */
    private fun planRoutes() {
        val s = _state.value
        val start = s.start?.latLng ?: return
        val end = s.end?.latLng ?: return
        planJob = viewModelScope.launch {
            _state.update {
                it.copy(
                    planning = true,
                    planFailed = false,
                    candidates = emptyList(),
                    highlightIndex = -1,
                    chosenIndex = -1,
                    chosenRouteId = null,
                )
            }
            val paths = routeClient.walkRoutes(start, end)
            if (paths.isEmpty()) {
                _state.update { it.copy(planning = false, planFailed = true) }
                return@launch
            }
            // 距离去重（DEV §4.8：差 <5% 视为重复；山区「同一条返回三遍」实测高发）
            val kept = ArrayList<PlannedPath>()
            for (p in paths) {
                val dup = kept.any { abs(it.distanceM - p.distanceM) / it.distanceM < DIST_DUP_RATIO }
                if (!dup) kept.add(p)
            }
            val shown = kept.take(MAX_CANDIDATES).map { RouteCandidate(path = it) }
            _state.update {
                it.copy(planning = false, candidates = shown, highlightIndex = 0)
            }
            // 后台补齐爬升/难度（评估含高程网络查询，不阻塞首屏）
            for (i in shown.indices) {
                val pts = shown[i].path.points.map { LatLngValue(it.latitude, it.longitude) }
                val metrics = RouteEvaluator.evaluate(pts, queryElevation)
                val difficulty = if (metrics.elevationAvailable) {
                    RouteEvaluator.difficultyOf(metrics.distanceM, metrics.ascentM ?: 0.0)
                } else {
                    null
                }
                _state.update { st ->
                    val updated = st.candidates.mapIndexed { j, c ->
                        if (j == i) c.copy(metrics = metrics, difficulty = difficulty) else c
                    }
                    st.copy(candidates = updated)
                }
            }
        }
    }

    private fun routeName(s: PlanUiState): String {
        val a = s.start?.name
        val b = s.end?.name
        if (a != null && b != null) return "$a → $b"
        return "计划线路 " + DATE_TIME_FORMAT.get()!!.format(Date())
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
        // F-PLAN-10：起终点确定后自动规划
        val after = _state.value
        if (after.start != null && after.end != null && after.candidates.isEmpty() && after.chosenRouteId == null) {
            planRoutes()
        }
    }

    private companion object {
        const val LOCATE_INTERVAL_MS = 1_000L
        const val LOCATE_TIMEOUT_MS = 10_000L
        const val MAX_SUGGESTIONS = 6

        /** DEV §4.8 去重阈值（距离差 <5% 视为重复） */
        const val DIST_DUP_RATIO = 0.05
        const val MAX_CANDIDATES = 3

        /** 计划折线入库前的抽稀容差（与详情页渲染一致） */
        const val POLYLINE_EPSILON_M = 12.0
    }
}

private val DATE_TIME_FORMAT = ThreadLocal.withInitial {
    SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.ROOT)
}
