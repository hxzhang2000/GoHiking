package com.gohiking.feature.plan

import kotlin.math.ceil

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
import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale
import java.util.UUID
import kotlin.math.abs
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

/** 下一次点选 / 搜索落点目标（F-PLAN-03/04：先起点后终点，可手动切） */
enum class SelectTarget { START, END }

/** 搜索提示类型（文案走资源文件，VM 只给语义） */
enum class SearchHint { NONE, NO_RESULT, OFFLINE, LOCATE_FAILED }

/** F-PLAN-24：手动线路连线方式——直线（虚线）/ 路径吸附（失败回退直线） */
enum class ManualConnect { STRAIGHT, SNAP }

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

/** 手动线路单段（直线段虚线渲染、吸附段实线渲染） */
data class ManualSegment(
    val points: List<LatLng>,
    val snapped: Boolean,
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
    // ── 计划确认态（F-PLAN-30/34/36/38）──
    val confirming: Boolean = false, // 选定去程后的确认/编辑态（保存前）
    val returnEnabled: Boolean = true, // F-PLAN-34 原路返回开关
    val returnPath: PlannedPath? = null, // 返程折线（原路返回 = 去程反向；独立规划在 C2b）
    val returnMetrics: RouteMetrics? = null, // 爬升/下降相对去程已互换
    val returnDifficulty: Difficulty? = null,
    val returnPlanning: Boolean = false, // F-PLAN-35 返程独立规划中
    // ── 手动打点（F-PLAN-20~29）──
    val manualMode: Boolean = false,
    val manualWaypoints: List<PlanPoint> = emptyList(),
    val manualConnect: ManualConnect = ManualConnect.STRAIGHT,
    val manualSnapping: Boolean = false,
    val manualSegments: List<ManualSegment> = emptyList(),
    val manualMetrics: RouteMetrics? = null,
    val manualDifficulty: Difficulty? = null,
    val manualSavedRouteId: String? = null,
    val manualLimitHit: Boolean = false, // F-PLAN-28 超上限提示
    // M-05：保存进行中标志，防止连点产生两条相同线路
    val saving: Boolean = false,
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
    /** F-I18N-40 默认名本地化：application context 取 core:resources 字符串（跟随系统语言） */
    private val appContext: android.content.Context,
) : ViewModel() {

    private val _state = MutableStateFlow(PlanUiState())
    val state: StateFlow<PlanUiState> = _state.asStateFlow()

    private var planJob: kotlinx.coroutines.Job? = null
    private var manualJob: kotlinx.coroutines.Job? = null
    // H-11：搜索协程必须可取消，否则每敲一个字符都留一条在飞的请求（乱序覆盖）
    private var searchJob: kotlinx.coroutines.Job? = null
    private var returnJob: kotlinx.coroutines.Job? = null

    /** F-PLAN-03/04：地图空白点选；手动模式下 = 添加途经点（F-PLAN-21） */
    fun onMapTap(latLng: LatLng) {
        if (_state.value.manualMode) {
            addWaypoint(PlanPoint(name = null, latLng = latLng))
        } else {
            assign(PlanPoint(name = null, latLng = latLng))
        }
    }

    /** F-PLAN-08：底图 POI 气泡（双回调同触以 POI 为准）；手动模式下 = 添加途经点 */
    fun onPoiChosen(name: String, latLng: LatLng, target: SelectTarget) {
        if (_state.value.manualMode) {
            addWaypoint(PlanPoint(name = name, latLng = latLng))
        } else {
            assign(PlanPoint(name = name, latLng = latLng), forceTarget = target)
        }
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

    /**
     * 搜索框输入变化。
     * H-11：注释原本写「防抖在 UI 层做」，但 UI 层根本没做 —— 每个字符一条永不取消的协程，
     * 慢的旧响应会覆盖快的新响应。改在 VM 内取消上一条 + 300ms 防抖。
     */
    fun onSearchInput(keyword: String, center: LatLng?) {
        val kw = keyword.trim()
        searchJob?.cancel()
        if (kw.isEmpty()) {
            // N-58：清空搜索框时必须把 searching 一起复位。原实现只清了 suggestions/hint，
            // 若用户在上一次请求返回前删空输入，防抖协程被 cancel（不会走到 search() 里的
            // searching = false），顶部转圈指示器就永远停不下来。
            _state.update { it.copy(suggestions = emptyList(), hint = SearchHint.NONE, searching = false) }
            return
        }
        searchJob = viewModelScope.launch {
            delay(SEARCH_DEBOUNCE_MS)
            search(kw, center)
        }
    }

    /** 联想候选被选中：真实 POI 直接落点；「只是个词」→ 拿词再发一次关键词搜索（PRD 6.2.1 细节 1） */
    fun onSuggestionPicked(s: Suggestion, center: LatLng?) {
        val latLng = s.latLng
        if (latLng != null) {
            if (_state.value.manualMode) {
                addWaypoint(PlanPoint(name = s.name, latLng = latLng)) // 手动模式：搜索结果也是途经点
            } else {
                assign(PlanPoint(name = s.name, latLng = latLng))
            }
        } else {
            searchJob?.cancel()
            searchJob = viewModelScope.launch { search(s.name, center) }
        }
    }

    /** P-03 原型 appbar「互换」：起终点对调；已有候选时清掉并按新方向重新规划 */
    fun swapPoints() {
        val s = _state.value
        if (s.chosenRouteId != null) return // 已落库不互换
        _state.update {
            it.copy(
                start = it.end,
                end = it.start,
                candidates = emptyList(),
                highlightIndex = -1,
                chosenIndex = -1,
                confirming = false,
                planFailed = false,
                returnEnabled = true,
                returnPath = null,
                returnMetrics = null,
                returnDifficulty = null,
                returnPlanning = false,
            )
        }
        val after = _state.value
        if (after.start != null && after.end != null) planRoutes()
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
        cancelAll()
        _state.update { PlanUiState(activeTarget = SelectTarget.START) }
    }

    /** F-PLAN-20：进入手动打点模式（自动规划失败或用户主动）；已选起终点转为初始途经点 */
    fun enterManualMode() {
        cancelAll()
        val s = _state.value
        val carried = listOfNotNull(s.start, s.end)
        _state.update { PlanUiState(manualMode = true, manualWaypoints = carried) }
        rebuildManualSegments()
    }

    /** 退出手动模式，回到选点态 */
    fun exitManualMode() {
        cancelAll()
        _state.update { PlanUiState(activeTarget = SelectTarget.START) }
    }

    /** F-PLAN-13：点击卡片 → 该线路高亮，其余淡出 */
    fun onCandidateClicked(index: Int) {
        _state.update { it.copy(highlightIndex = index) }
    }

    /** F-PLAN-14：选择此线路 → 进入计划确认态（F-PLAN-30~38），保存动作见 savePlan */
    fun chooseCandidate(index: Int) {
        val s = _state.value
        val c = s.candidates.getOrNull(index) ?: return
        if (s.confirming || s.chosenRouteId != null) return
        // F-PLAN-34：返程默认「原路返回」——去程折线反向；同一折线反向不重跑评估，
        // 爬升/下降互换（上坡变下坡）、最高/最低海拔不变
        val returnPath = PlannedPath(
            distanceM = c.path.distanceM,
            durationS = c.path.durationS,
            points = c.path.points.asReversed(),
        )
        val returnMetrics = c.metrics?.let { it.copy(ascentM = it.descentM, descentM = it.ascentM) }
        _state.update {
            it.copy(
                confirming = true,
                chosenIndex = index,
                returnEnabled = true,
                returnPath = returnPath,
                returnMetrics = returnMetrics,
                returnDifficulty = c.difficulty,
            )
        }
    }

    /** F-PLAN-34：原路返回开关（关闭则只保存去程单段） */
    fun setReturnEnabled(enabled: Boolean) {
        _state.update { it.copy(returnEnabled = enabled) }
    }

    /**
     * F-PLAN-35：返程独立规划——起终点对调重新步行规划，取第一条并重估爬升；
     * 规划失败保留原「原路返回」折线不动（不出现空返程）
     */
    fun replanReturn() {
        val s = _state.value
        if (!s.confirming || s.returnPlanning) return
        val a = s.start?.latLng ?: return
        val b = s.end?.latLng ?: return
        returnJob?.cancel()
        returnJob = viewModelScope.launch {
            _state.update { it.copy(returnPlanning = true) }
            // M-06：异常时 returnPlanning 必须复位，否则按钮被转圈图标永久替换
            try {
                replanReturnInternal(b, a)
            } finally {
                _state.update { it.copy(returnPlanning = false) }
            }
        }
    }

    private suspend fun replanReturnInternal(b: LatLng, a: LatLng) {
            val paths = routeClient.walkRoutes(b, a) // 返程：终点 → 起点
            if (paths.isEmpty()) {
                return // 保留原路返回
            }
            val rp = paths.first()
            _state.update {
                it.copy(returnPath = rp, returnMetrics = null, returnDifficulty = null, returnPlanning = false)
            }
            val metrics = RouteEvaluator.evaluate(
                rp.points.map { LatLngValue(it.latitude, it.longitude) },
                queryElevation,
            )
            val difficulty = if (metrics.elevationAvailable) {
                RouteEvaluator.difficultyOf(metrics.distanceM, metrics.ascentM ?: 0.0)
            } else {
                null
            }
            _state.update { it.copy(returnMetrics = metrics, returnDifficulty = difficulty) }
    }

    /** 确认态保存计划（F-PLAN-40 命名由 UI 对话框传入；OUTBOUND + RETURN 两段同事务落库） */
    fun savePlan(name: String?, note: String? = null) {
        val s = _state.value
        val c = s.candidates.getOrNull(s.chosenIndex) ?: return
        if (s.chosenRouteId != null || s.saving) return // M-05：已落库或正在保存 → 忽略重复点击
        _state.update { it.copy(saving = true) }
        viewModelScope.launch {
            try {
                savePlanInternal(c, s, name, note)
            } finally {
                _state.update { it.copy(saving = false) }
            }
        }
    }

    private suspend fun savePlanInternal(
        c: RouteCandidate,
        s: PlanUiState,
        name: String?,
        note: String?,
    ) {
            val routeId = UUID.randomUUID().toString()
            val legs = ArrayList<PlannedLegEntity>(2)
            // 去程（polylineJson 存抽稀后折线，PRD 7.1；记录轨迹「原始点入库」规则不适用于计划线）
            legs += PlannedLegEntity(
                id = UUID.randomUUID().toString(),
                plannedRouteId = routeId,
                legType = "OUTBOUND",
                distanceM = c.path.distanceM.toDouble(),
                // H-06：高程/难度不可用时落 null，绝不用 0 / MODERATE 冒充
                ascentM = c.metrics?.ascentM,
                descentM = c.metrics?.descentM,
                // L-16：耗时向上取整，119s → 2 min（此前 119s 显示 1 min、<60s 显示 0）
                estimatedMin = ceil(c.path.durationS / 60.0).toInt(),
                difficulty = c.difficulty?.name,
                polylineJson = PolylineJson.encode(
                    PolylineSimplifier.simplify(
                        c.path.points.map { LatLngValue(it.latitude, it.longitude) },
                        POLYLINE_EPSILON_M,
                    ),
                ),
            )
            // F-PLAN-30：返程段（原路返回 = 去程折线反向；独立规划返程在 C2b）
            if (s.returnEnabled && s.returnPath != null) {
                val rp = s.returnPath
                legs += PlannedLegEntity(
                    id = UUID.randomUUID().toString(),
                    plannedRouteId = routeId,
                    legType = "RETURN",
                    distanceM = rp.distanceM.toDouble(),
                    ascentM = s.returnMetrics?.ascentM,
                    descentM = s.returnMetrics?.descentM,
                    estimatedMin = ceil(rp.durationS / 60.0).toInt(),
                    difficulty = s.returnDifficulty?.name,
                    polylineJson = PolylineJson.encode(
                        PolylineSimplifier.simplify(
                            rp.points.map { LatLngValue(it.latitude, it.longitude) },
                            POLYLINE_EPSILON_M,
                        ),
                    ),
                )
            }
            val route = PlannedRouteEntity(
                id = routeId,
                name = name ?: routeName(s),
                note = note?.trim()?.ifEmpty { null },
                source = "AUTO",
                createdAt = System.currentTimeMillis(),
                totalDistanceM = legs.sumOf { it.distanceM },
                totalAscentM = sumOrNull(legs.map { it.ascentM }),
                totalDescentM = sumOrNull(legs.map { it.descentM }),
            )
            routeDao.saveWithLegs(route, legs)
            _state.update { it.copy(chosenRouteId = routeId) }
    }

    /** F-PLAN-16：重新规划 */
    fun replan() {
        planJob?.cancel()
        val s = _state.value
        if (s.start != null && s.end != null) planRoutes()
    }

    // ── 手动打点（F-PLAN-20~29）──

    /** F-PLAN-21：添加途经点；F-PLAN-28 上限 50 */
    fun addWaypoint(point: PlanPoint) {
        val s = _state.value
        if (!s.manualMode) return
        if (s.manualWaypoints.size >= MAX_WAYPOINTS) {
            _state.update { it.copy(manualLimitHit = true) }
            return
        }
        _state.update {
            it.copy(manualWaypoints = it.manualWaypoints + point, manualLimitHit = false, manualSavedRouteId = null)
        }
        rebuildManualSegments()
    }

    /** F-PLAN-25：撤销上一个途经点 */
    fun undoWaypoint() {
        val s = _state.value
        if (!s.manualMode || s.manualWaypoints.isEmpty()) return
        _state.update { it.copy(manualWaypoints = it.manualWaypoints.dropLast(1), manualSavedRouteId = null) }
        rebuildManualSegments()
    }

    /** 清空全部途经点 */
    fun clearManual() {
        manualJob?.cancel()
        _state.update {
            it.copy(
                manualWaypoints = emptyList(),
                manualSegments = emptyList(),
                manualMetrics = null,
                manualDifficulty = null,
                manualSavedRouteId = null,
                manualLimitHit = false,
            )
        }
    }

    /** F-PLAN-27：删除指定途经点 */
    fun removeWaypoint(index: Int) {
        val s = _state.value
        if (index !in s.manualWaypoints.indices) return
        _state.update {
            it.copy(manualWaypoints = it.manualWaypoints.filterIndexed { i, _ -> i != index }, manualSavedRouteId = null)
        }
        rebuildManualSegments()
    }

    /** F-PLAN-26：拖动途经点，线路实时更新 */
    fun onWaypointMoved(index: Int, latLng: LatLng) {
        val s = _state.value
        if (index !in s.manualWaypoints.indices) return
        _state.update {
            it.copy(
                manualWaypoints = it.manualWaypoints.mapIndexed { i, p ->
                    if (i == index) p.copy(latLng = latLng) else p
                },
                manualSavedRouteId = null,
            )
        }
        rebuildManualSegments()
    }

    /** F-PLAN-24：连线方式切换（直线 / 路径吸附） */
    fun setManualConnect(mode: ManualConnect) {
        _state.update { it.copy(manualConnect = mode) }
        rebuildManualSegments()
    }

    /** 手动线路保存为计划（F-PLAN-29/40；source=MANUAL，C1 先单段 OUTBOUND） */
    fun saveManual(name: String?, note: String? = null) {
        val s = _state.value
        val segs = s.manualSegments
        if (!s.manualMode || segs.isEmpty() || s.manualSavedRouteId != null) return
        viewModelScope.launch {
            val flat = segs.flatMap { it.points }
            val dist = s.manualMetrics?.distanceM
                ?: RouteEvaluator.polylineDistance(flat.map { LatLngValue(it.latitude, it.longitude) })
            val m = s.manualMetrics
            val routeId = UUID.randomUUID().toString()
            val simplified = PolylineSimplifier.simplify(
                flat.map { LatLngValue(it.latitude, it.longitude) },
                POLYLINE_EPSILON_M,
            )
            val leg = PlannedLegEntity(
                id = UUID.randomUUID().toString(),
                plannedRouteId = routeId,
                legType = "OUTBOUND",
                distanceM = dist,
                ascentM = m?.ascentM,
                descentM = m?.descentM,
                estimatedMin = ceil((m?.estimatedDurationSec ?: 0L) / 60.0).toInt(),
                difficulty = s.manualDifficulty?.name,
                polylineJson = PolylineJson.encode(simplified),
            )
            val route = PlannedRouteEntity(
                id = routeId,
                name = name ?: manualRouteName(s),
                note = note?.trim()?.ifEmpty { null },
                source = "MANUAL",
                createdAt = System.currentTimeMillis(),
                totalDistanceM = dist,
                totalAscentM = leg.ascentM,
                totalDescentM = leg.descentM,
            )
            routeDao.saveWithLegs(route, listOf(leg))
            _state.update { it.copy(manualSavedRouteId = routeId) }
        }
    }

    /** H-06：任一子项未知 → 汇总未知（不得写 0） */
    private fun sumOrNull(values: List<Double?>): Double? =
        if (values.any { it == null }) null else values.sumOf { it ?: 0.0 }

    /** M-04：重置/切模式时必须取消全部在飞协程，否则重置后会被陈旧结果写回 */
    private fun cancelAll() {
        planJob?.cancel(); planJob = null
        manualJob?.cancel(); manualJob = null
        searchJob?.cancel(); searchJob = null
        returnJob?.cancel(); returnJob = null
    }

    private fun manualRouteName(s: PlanUiState): String =
        s.manualWaypoints.lastOrNull { !it.name.isNullOrBlank() }?.name
            ?: appContext.getString(com.gohiking.core.resources.R.string.plan_manual_default, LocalDateTime.now().format(DATE_TIME_FORMAT))

    /** 相邻途经点连线重建（F-PLAN-23/24）：直线直连；吸附逐段步行规划、失败回退直线 */
    private fun rebuildManualSegments() {
        manualJob?.cancel()
        val s = _state.value
        val wps = s.manualWaypoints
        if (wps.size < 2) {
            _state.update {
                it.copy(manualSegments = emptyList(), manualMetrics = null, manualDifficulty = null, manualSnapping = false)
            }
            return
        }
        if (s.manualConnect == ManualConnect.STRAIGHT) {
            val segs = (0 until wps.size - 1).map { i ->
                ManualSegment(points = listOf(wps[i].latLng, wps[i + 1].latLng), snapped = false)
            }
            _state.update { it.copy(manualSegments = segs, manualSnapping = false) }
            evaluateManual(segs)
        } else {
            manualJob = viewModelScope.launch {
                _state.update { it.copy(manualSnapping = true) }
                val segs = ArrayList<ManualSegment>()
                for (i in 0 until wps.size - 1) {
                    val route = routeClient.walkRoutes(wps[i].latLng, wps[i + 1].latLng).firstOrNull()
                    segs.add(
                        if (route != null && route.points.isNotEmpty()) {
                            ManualSegment(points = route.points, snapped = true)
                        } else {
                            ManualSegment(points = listOf(wps[i].latLng, wps[i + 1].latLng), snapped = false)
                        },
                    )
                    _state.update { it.copy(manualSegments = segs.toList()) } // 逐段上屏
                }
                _state.update { it.copy(manualSnapping = false) }
                evaluateManual(segs)
            }
        }
    }

    /** F-PLAN-29：手动线路同样评估——距离/耗时即时，爬升/难度后台补齐（不可用显示「—」） */
    private fun evaluateManual(segs: List<ManualSegment>) {
        manualJob = viewModelScope.launch {
            val flat = segs.flatMap { it.points }
            if (flat.size < 2) {
                // L-15：不清空会把上一轮的 metrics/difficulty 留在界面上
                _state.update { it.copy(manualMetrics = null, manualDifficulty = null) }
                return@launch
            }
            val distance = RouteEvaluator.polylineDistance(flat.map { LatLngValue(it.latitude, it.longitude) })
            _state.update {
                it.copy(
                    manualMetrics = RouteMetrics(
                        distanceM = distance,
                        estimatedDurationSec = (distance / MANUAL_SPEED_MPS).toLong(),
                        ascentM = null, descentM = null, maxAltitudeM = null, minAltitudeM = null,
                        elevationAvailable = false,
                    ),
                    manualDifficulty = null,
                )
            }
            val metrics = RouteEvaluator.evaluate(flat.map { LatLngValue(it.latitude, it.longitude) }, queryElevation)
            val difficulty = if (metrics.elevationAvailable) {
                RouteEvaluator.difficultyOf(metrics.distanceM, metrics.ascentM ?: 0.0)
            } else {
                null
            }
            _state.update { it.copy(manualMetrics = metrics, manualDifficulty = difficulty) }
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
                    confirming = false,
                    returnEnabled = true,
                    returnPath = null,
                    returnMetrics = null,
                    returnDifficulty = null,
                    returnPlanning = false,
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
                // L-01：distanceM 为 0 时会得到 NaN/Inf，比较恒 false，去重静默失效
                val dup = kept.any {
                    it.distanceM > 0f && abs(it.distanceM - p.distanceM) / it.distanceM < DIST_DUP_RATIO
                }
                if (!dup) kept.add(p)
            }
            val shown = kept.take(MAX_CANDIDATES).map { RouteCandidate(path = it) }
            _state.update {
                it.copy(planning = false, candidates = shown, highlightIndex = 0)
            }
            // 后台补齐爬升/难度（评估含高程网络查询，不阻塞首屏）
            for (i in shown.indices) {
                // M-06：单条评估失败不能中断整个循环，否则剩余候选永远停在「—」
                val pair = runCatching {
                    val pts = shown[i].path.points.map { LatLngValue(it.latitude, it.longitude) }
                    val metrics = RouteEvaluator.evaluate(pts, queryElevation)
                    val difficulty = if (metrics.elevationAvailable) {
                        RouteEvaluator.difficultyOf(metrics.distanceM, metrics.ascentM ?: 0.0)
                    } else {
                        null
                    }
                    metrics to difficulty
                }.getOrNull() ?: continue
                val metrics = pair.first
                val difficulty = pair.second
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
        if (a != null && b != null) {
            return appContext.getString(com.gohiking.core.resources.R.string.plan_route_named, a, b)
        }
        return appContext.getString(com.gohiking.core.resources.R.string.plan_route_default, LocalDateTime.now().format(DATE_TIME_FORMAT))
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

        /** H-11：POI 联想防抖（此前完全没做，每敲一个字符发一条请求） */
        const val SEARCH_DEBOUNCE_MS = 300L

        /** DEV §4.8 去重阈值（距离差 <5% 视为重复） */
        const val DIST_DUP_RATIO = 0.05
        const val MAX_CANDIDATES = 3

        /** 计划折线入库前的抽稀容差（与详情页渲染一致） */
        const val POLYLINE_EPSILON_M = 12.0

        /** F-PLAN-28：手动途经点数量上限 */
        const val MAX_WAYPOINTS = 50

        /** 登山平均速度 3.5 km/h = 3.5/3.6 m/s（PRD 6.2.3，与 RouteEvaluator 同口径） */
        const val MANUAL_SPEED_MPS = 3.5 / 3.6
    }
}

// DateTimeFormatter 线程安全且不可变，无需 ThreadLocal（K2 起 SimpleDateFormat?.get() 可空告警）
private val DATE_TIME_FORMAT: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm", Locale.ROOT)
