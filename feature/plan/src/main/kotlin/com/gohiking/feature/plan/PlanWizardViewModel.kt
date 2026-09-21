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
import com.gohiking.core.database.entity.PlannedWaypointEntity
import com.gohiking.core.elevation.RouteEvaluator
import com.gohiking.core.elevation.RouteMetrics
import com.gohiking.core.location.LocationProvider
import com.gohiking.core.map.route.PlannedPath
import com.gohiking.core.map.route.RouteSearchClient
import com.gohiking.core.map.search.SearchOutcome
import com.gohiking.core.model.LatLngValue
import com.gohiking.core.resources.R as CoreR
import java.util.UUID
import kotlin.math.ceil
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

/** 新建计划向导步骤（docs/新建计划-交互设计.md §1） */
enum class WizardStep { START, END, OUTBOUND, RETURN, SAVE }

/** 返程方式（§6） */
enum class WizardReturnMode { BACK, SMART, MANUAL, SKIPPED }

/**
 * 新建计划向导（原稿 + 完善版设计）：全部操作只落在会话草稿（§2），
 * 步骤⑤统一落库 planned_route/legs/waypoints；起终点变更触发失效联动（§2）。
 */
data class WizardUiState(
    val step: WizardStep = WizardStep.START,
    val start: PlanPoint? = null,
    val end: PlanPoint? = null,
    // 步骤③ 去程
    val smartMode: Boolean = true,
    val candidates: List<RouteCandidate> = emptyList(),
    val chosenIndex: Int = -1,
    val manualWaypoints: List<PlanPoint> = emptyList(),
    val manualConnect: ManualConnect = ManualConnect.SNAP,
    val manualSegments: List<ManualSegment> = emptyList(),
    val manualSnapping: Boolean = false,
    val outboundPoints: List<LatLng> = emptyList(),
    val outboundDistM: Double = 0.0,
    val outboundDurS: Long = 0,
    val outboundReady: Boolean = false,
    val outboundStale: Boolean = false,
    // 步骤④ 返程
    val returnMode: WizardReturnMode? = null,
    val returnWaypoints: List<PlanPoint> = emptyList(),
    val returnPoints: List<LatLng> = emptyList(),
    val returnDistM: Double = 0.0,
    val returnDurS: Long = 0,
    val returnReady: Boolean = false,
    val returnStale: Boolean = false,
    // 搜索（步骤①/②）
    val query: String = "",
    val suggestions: List<com.gohiking.core.map.search.Suggestion> = emptyList(),
    val searching: Boolean = false,
    val searchHint: String? = null,
    val locating: Boolean = false,
    // 通用
    val planning: Boolean = false,
    val planFailed: Boolean = false,
    val saving: Boolean = false,
    val savedRouteId: String? = null,
    val name: String = "",
    val note: String = "",
) {
    val hasReturn: Boolean get() = returnReady && returnPoints.isNotEmpty()
}

class PlanWizardViewModel(
    private val routeDao: PlannedRouteDao,
    private val routeClient: RouteSearchClient,
    private val searchClient: com.gohiking.core.map.search.AmapSearchClient,
    private val locationProvider: LocationProvider,
    private val queryElevation: suspend (List<LatLngValue>) -> GhResult<List<Double?>>,
    private val appContext: android.content.Context,
) : ViewModel() {

    private val _state = MutableStateFlow(WizardUiState())
    val state: StateFlow<WizardUiState> = _state

    private var planJob: Job? = null
    private var searchJob: Job? = null
    private var snapJob: Job? = null

    fun setStep(step: WizardStep) {
        _state.update { it.copy(step = step) }
    }

    // ── 步骤①/②：选点（四种方式，§3/§4）──

    /** 失效联动（§2）：起/终点变更 → 去程/返程置失效（旧线保留半透明） */
    private fun invalidateOnPointChange(s: WizardUiState): WizardUiState =
        s.copy(outboundReady = false, outboundStale = s.outboundPoints.isNotEmpty(), returnStale = s.returnPoints.isNotEmpty())

    fun pickStart(point: PlanPoint) = pick(WizardStep.START, point)
    fun pickEnd(point: PlanPoint) = pick(WizardStep.END, point)

    private fun pick(step: WizardStep, point: PlanPoint) {
        _state.update { invalidateOnPointChange(if (step == WizardStep.START) it.copy(start = point, step = WizardStep.END) else it.copy(end = point)) }
    }

    fun onSearchInput(keyword: String) {
        _state.update { it.copy(query = keyword) }
        searchJob?.cancel()
        if (keyword.isBlank()) {
            _state.update { it.copy(suggestions = emptyList(), searchHint = null) }
            return
        }
        searchJob = viewModelScope.launch {
            _state.update { it.copy(searching = true, searchHint = null) }
            val outcome = searchClient.inputTips(keyword.trim())
            when (outcome) {
                is SearchOutcome.Hits -> _state.update {
                    it.copy(searching = false, suggestions = outcome.items.filter { s -> s.latLng != null }.take(6))
                }
                SearchOutcome.Empty -> _state.update {
                    it.copy(searching = false, suggestions = emptyList(), searchHint = appContext.getString(CoreR.string.plan_hint_no_result))
                }
                is SearchOutcome.Failed -> _state.update {
                    it.copy(searching = false, suggestions = emptyList(), searchHint = appContext.getString(CoreR.string.plan_hint_offline))
                }
            }
        }
    }

    fun pickSuggestion(index: Int) {
        val s = _state.value
        val sug = s.suggestions.getOrNull(index) ?: return
        val latLng = sug.latLng ?: return
        val point = PlanPoint(sug.name, latLng)
        if (s.step == WizardStep.START) pickStart(point) else pickEnd(point)
        _state.update { it.copy(suggestions = emptyList(), query = "") }
    }

    /** 用当前位置（§3 方式④）；target 由 UI 按当前步骤决定 */
    fun useCurrentLocation() {
        val s = _state.value
        if (s.locating) return
        _state.update { it.copy(locating = true) }
        viewModelScope.launch {
            val fix = runCatching {
                locationProvider.start(1000)
                withTimeoutOrNull(8_000) { locationProvider.fixes.first() }
            }.getOrNull()
            runCatching { locationProvider.stop() }
            _state.update { it.copy(locating = false) }
            if (fix != null) {
                val point = PlanPoint(null, LatLng(fix.lat, fix.lng))
                if (s.step == WizardStep.START) pickStart(point) else pickEnd(point)
            }
        }
    }

    fun copyStartToEnd() {
        val s = _state.value
        s.start?.let { pickEnd(it) }
    }

    // ── 步骤③：去程（§5）──

    fun setOutboundSmart(smart: Boolean) {
        _state.update { it.copy(smartMode = smart) }
    }

    fun setManualConnect(connect: ManualConnect) {
        _state.update { it.copy(manualConnect = connect) }
        rebuildManualSegments()
    }

    /** 智能规划：无途经点 = 起终点多备选（候选 chips 可切换）；有途经点 = 串行多段取首条（§5.1） */
    fun planSmart() {
        val s = _state.value
        val start = s.start?.latLng ?: return
        val end = s.end?.latLng ?: return
        if (s.planning) return
        planJob?.cancel()
        planJob = viewModelScope.launch {
            _state.update { it.copy(planning = true, planFailed = false) }
            val wps = s.manualWaypoints
            if (wps.isEmpty()) {
                val paths = routeClient.walkRoutes(start, end)
                if (paths.isEmpty()) {
                    _state.update { it.copy(planning = false, planFailed = true) }
                    return@launch
                }
                val kept = dedupe(paths)
                _state.update {
                    it.copy(
                        planning = false,
                        candidates = kept.map { p -> RouteCandidate(path = p) },
                        chosenIndex = 0,
                        outboundPoints = kept.first().points,
                        outboundDistM = kept.first().distanceM.toDouble(),
                        outboundDurS = kept.first().durationS,
                    )
                }
            } else {
                val chain = listOf(start) + wps.map { it.latLng } + listOf(end)
                val merged = mergeSegments(chain)
                if (merged == null) {
                    _state.update { it.copy(planning = false, planFailed = true) }
                    return@launch
                }
                _state.update {
                    it.copy(
                        planning = false,
                        candidates = emptyList(),
                        chosenIndex = -1,
                        outboundPoints = merged.points,
                        outboundDistM = merged.distanceM.toDouble(),
                        outboundDurS = merged.durationS,
                    )
                }
            }
        }
    }

    fun chooseCandidate(index: Int) {
        val s = _state.value
        val c = s.candidates.getOrNull(index) ?: return
        _state.update {
            it.copy(chosenIndex = index, outboundPoints = c.path.points, outboundDistM = c.path.distanceM.toDouble(), outboundDurS = c.path.durationS)
        }
    }

    /** 距离去重（DEV §4.8：<5% 视为重复） */
    private fun dedupe(paths: List<PlannedPath>): List<PlannedPath> {
        val kept = ArrayList<PlannedPath>()
        for (p in paths) {
            val dup = kept.any { it.distanceM > 0f && kotlin.math.abs(it.distanceM - p.distanceM) / it.distanceM < 0.05f }
            if (!dup) kept.add(p)
        }
        return kept.ifEmpty { listOf(paths.first()) }
    }

    /** 串行多段规划合并（QPS 约束，DEV §4.8）；任一段失败返回 null */
    private suspend fun mergeSegments(chain: List<LatLng>): PlannedPath? {
        if (chain.size < 2) return null
        var points = listOf(chain.first())
        var dist = 0f
        var dur = 0L
        for (i in 0 until chain.size - 1) {
            val path = routeClient.walkRoutes(chain[i], chain[i + 1]).firstOrNull() ?: return null
            points = points + path.points
            dist += path.distanceM
            dur += path.durationS
        }
        return PlannedPath(distanceM = dist, durationS = dur, points = points)
    }

    // ── 手动模式（§5.2）：点地图加途经点 + 吸附/直线 + 撤销/清空/删除 ──

    fun addWaypoint(latLng: LatLng) {
        val s = _state.value
        if (s.planning || s.manualWaypoints.size >= MAX_WAYPOINTS) return
        _state.update { it.copy(manualWaypoints = it.manualWaypoints + PlanPoint(null, latLng)) }
        rebuildManualSegments()
    }

    fun addReturnWaypoint(latLng: LatLng) {
        val s = _state.value
        if (s.planning || s.returnWaypoints.size >= MAX_WAYPOINTS) return
        _state.update { it.copy(returnWaypoints = it.returnWaypoints + PlanPoint(null, latLng)) }
    }

    fun moveWaypoint(index: Int, latLng: LatLng) {
        val s = _state.value
        if (index !in s.manualWaypoints.indices) return
        _state.update {
            it.copy(manualWaypoints = it.manualWaypoints.mapIndexed { i, p -> if (i == index) PlanPoint(p.name, latLng) else p })
        }
        rebuildManualSegments()
    }

    fun removeWaypoint(index: Int) {
        val s = _state.value
        if (index !in s.manualWaypoints.indices) return
        _state.update { it.copy(manualWaypoints = it.manualWaypoints.filterIndexed { i, _ -> i != index }) }
        rebuildManualSegments()
    }

    fun undoWaypoint() {
        _state.update { it.copy(manualWaypoints = it.manualWaypoints.dropLast(1)) }
        rebuildManualSegments()
    }

    fun clearWaypoints() {
        snapJob?.cancel()
        _state.update { it.copy(manualWaypoints = emptyList(), manualSegments = emptyList()) }
    }

    fun undoReturnWaypoint() {
        _state.update { it.copy(returnWaypoints = it.returnWaypoints.dropLast(1)) }
    }

    fun clearReturnWaypoints() {
        _state.update { it.copy(returnWaypoints = emptyList()) }
    }

    /** 手动连线重建（F-PLAN-23/24 同款）：直线直连；吸附逐段步行规划、失败回退直线 */
    private fun rebuildManualSegments() {
        snapJob?.cancel()
        val s = _state.value
        val wps = listOfNotNull(s.start?.latLng) + s.manualWaypoints.map { it.latLng }
        if (wps.size < 2) {
            _state.update { it.copy(manualSegments = emptyList(), manualSnapping = false) }
            return
        }
        if (s.manualConnect == ManualConnect.STRAIGHT) {
            val segs = (0 until wps.size - 1).map { i -> ManualSegment(listOf(wps[i], wps[i + 1]), snapped = false) }
            _state.update { it.copy(manualSegments = segs, manualSnapping = false) }
        } else {
            _state.update { it.copy(manualSnapping = true) }
            snapJob = viewModelScope.launch {
                val segs = ArrayList<ManualSegment>()
                for (i in 0 until wps.size - 1) {
                    val path = withTimeoutOrNull(8_000) {
                        routeClient.walkRoutes(wps[i], wps[i + 1]).firstOrNull()
                    }
                    if (path != null) {
                        segs += ManualSegment(path.points, snapped = true)
                    } else {
                        segs += ManualSegment(listOf(wps[i], wps[i + 1]), snapped = false)
                    }
                    _state.update { it.copy(manualSegments = segs.toList()) }
                }
                _state.update { it.copy(manualSnapping = false) }
            }
        }
    }

    /** 「完成去程」（§5.3）：智能=选定候选入草稿；手动=把最后途经点连到终点（零途经点则自动智能规划） */
    fun finishOutbound() {
        val s = _state.value
        if (s.smartMode) {
            if (s.candidates.isEmpty() || s.chosenIndex < 0) return
            _state.update { it.copy(outboundReady = true, outboundStale = false) }
            return
        }
        val start = s.start?.latLng ?: return
        val end = s.end?.latLng ?: return
        val wps = s.manualWaypoints.map { it.latLng }
        if (wps.isEmpty()) {
            planSmart() // §5.3 边界：零途经点 → 自动智能规划兜底
            return
        }
        if (s.planning) return
        planJob?.cancel()
        planJob = viewModelScope.launch {
            _state.update { it.copy(planning = true, planFailed = false) }
            val chain = listOf(start) + wps + listOf(end)
            val merged = if (s.manualConnect == ManualConnect.STRAIGHT) {
                PlannedPath(
                    distanceM = chain.zipWithNext { a, b -> haversine(a, b) }.sum().toFloat(),
                    durationS = (chain.zipWithNext { a, b -> haversine(a, b) }.sum() / MANUAL_SPEED_MPS).toLong(),
                    points = chain,
                )
            } else {
                mergeSegments(chain)
            }
            if (merged == null) {
                _state.update { it.copy(planning = false, planFailed = true) }
                return@launch
            }
            _state.update {
                it.copy(
                    planning = false,
                    outboundPoints = merged.points,
                    outboundDistM = merged.distanceM.toDouble(),
                    outboundDurS = merged.durationS,
                    outboundReady = true,
                    outboundStale = false,
                )
            }
        }
    }

    // ── 步骤④：返程（§6）──

    fun setReturnMode(mode: WizardReturnMode) {
        when (mode) {
            WizardReturnMode.BACK -> {
                val s = _state.value
                if (s.outboundPoints.size < 2) return
                _state.update {
                    it.copy(returnMode = mode, returnPoints = s.outboundPoints.reversed(), returnDistM = s.outboundDistM, returnDurS = s.outboundDurS, returnReady = true, returnStale = false)
                }
            }
            WizardReturnMode.SKIPPED -> _state.update {
                it.copy(returnMode = mode, returnPoints = emptyList(), returnReady = true, returnStale = false)
            }
            else -> _state.update { it.copy(returnMode = mode, returnReady = false) }
        }
    }

    fun planReturnSmart() {
        val s = _state.value
        val a = s.end?.latLng ?: return
        val b = s.start?.latLng ?: return
        if (s.planning) return
        planJob?.cancel()
        planJob = viewModelScope.launch {
            _state.update { it.copy(planning = true, planFailed = false) }
            val path = routeClient.walkRoutes(a, b).firstOrNull()
            if (path == null) {
                _state.update { it.copy(planning = false, planFailed = true) }
                return@launch
            }
            _state.update {
                it.copy(planning = false, returnPoints = path.points, returnDistM = path.distanceM.toDouble(), returnDurS = path.durationS, returnReady = true, returnStale = false)
            }
        }
    }

    /** 手动返程「完成」：终点 →(返程途经点)→ 起点（§6 反向） */
    fun finishReturn() {
        val s = _state.value
        val a = s.end?.latLng ?: return
        val b = s.start?.latLng ?: return
        val wps = s.returnWaypoints.map { it.latLng }
        if (s.planning) return
        planJob?.cancel()
        planJob = viewModelScope.launch {
            _state.update { it.copy(planning = true, planFailed = false) }
            val chain = listOf(a) + wps + listOf(b)
            val merged = if (s.manualConnect == ManualConnect.STRAIGHT) {
                PlannedPath(
                    distanceM = chain.zipWithNext { x, y -> haversine(x, y) }.sum().toFloat(),
                    durationS = (chain.zipWithNext { x, y -> haversine(x, y) }.sum() / MANUAL_SPEED_MPS).toLong(),
                    points = chain,
                )
            } else {
                mergeSegments(chain)
            }
            if (merged == null) {
                _state.update { it.copy(planning = false, planFailed = true) }
                return@launch
            }
            _state.update {
                it.copy(planning = false, returnPoints = merged.points, returnDistM = merged.distanceM.toDouble(), returnDurS = merged.durationS, returnReady = true, returnStale = false)
            }
        }
    }

    // ── 步骤⑤：保存（§7）──

    fun setName(name: String) = _state.update { it.copy(name = name) }
    fun setNote(note: String) = _state.update { it.copy(note = note) }

    /** 【完成】校验（§7 完整性校验矩阵）：返回应跳转的步骤；null = 全齐可保存 */
    fun completeCheck(): WizardStep? {
        val s = _state.value
        return when {
            s.start == null -> WizardStep.START
            s.end == null -> WizardStep.END
            !s.outboundReady -> WizardStep.OUTBOUND
            else -> null // 返程缺失合法（单程）
        }
    }

    fun save() {
        val s = _state.value
        val start = s.start ?: return
        val end = s.end ?: return
        if (!s.outboundReady || s.saving) return
        viewModelScope.launch {
            _state.update { it.copy(saving = true) }
            try {
                val routeId = UUID.randomUUID().toString()
                val outPts = s.outboundPoints.map { LatLngValue(it.latitude, it.longitude) }
                val outMetrics = runCatching { RouteEvaluator.evaluate(outPts, queryElevation) }.getOrNull()
                val retPts = s.returnPoints.map { LatLngValue(it.latitude, it.longitude) }
                val retMetrics = if (retPts.size >= 2) runCatching { RouteEvaluator.evaluate(retPts, queryElevation) }.getOrNull() else null
                val outLeg = PlannedLegEntity(
                    id = UUID.randomUUID().toString(),
                    plannedRouteId = routeId,
                    legType = "OUTBOUND",
                    distanceM = s.outboundDistM,
                    ascentM = outMetrics?.ascentM, // H-06：不可用落 null，绝不编造
                    descentM = outMetrics?.descentM,
                    estimatedMin = ceil(s.outboundDurS / 60.0).toInt(),
                    difficulty = outMetrics?.ascentM?.let { asc -> RouteEvaluator.difficultyOf(outMetrics.distanceM, asc).name },
                    polylineJson = PolylineJson.encode(PolylineSimplifier.simplify(outPts, POLYLINE_EPSILON_M)),
                )
                val legs = ArrayList<PlannedLegEntity>(2)
                legs += outLeg
                if (s.hasReturn) {
                    legs += PlannedLegEntity(
                        id = UUID.randomUUID().toString(),
                        plannedRouteId = routeId,
                        legType = "RETURN",
                        distanceM = s.returnDistM,
                        ascentM = retMetrics?.ascentM,
                        descentM = retMetrics?.descentM,
                        estimatedMin = ceil(s.returnDurS / 60.0).toInt(),
                        difficulty = retMetrics?.ascentM?.let { asc -> RouteEvaluator.difficultyOf(retMetrics.distanceM, asc).name },
                        polylineJson = PolylineJson.encode(PolylineSimplifier.simplify(retPts, POLYLINE_EPSILON_M)),
                    )
                }
                val route = PlannedRouteEntity(
                    id = routeId,
                    name = s.name.trim().ifEmpty {
                        listOfNotNull(s.start.name, s.end.name).joinToString("-").ifEmpty {
                            appContext.getString(CoreR.string.plan_route_default, java.time.LocalDateTime.now().format(java.time.format.DateTimeFormatter.ofPattern("MM-dd HH:mm")))
                        }
                    },
                    note = s.note.trim().ifEmpty { null },
                    source = "MANUAL",
                    createdAt = System.currentTimeMillis(),
                    totalDistanceM = legs.sumOf { it.distanceM },
                    totalAscentM = sumOf2(legs.getOrNull(0)?.ascentM, legs.getOrNull(1)?.ascentM),
                    totalDescentM = sumOf2(legs.getOrNull(0)?.descentM, legs.getOrNull(1)?.descentM),
                )
                routeDao.saveWithLegs(route, legs)
                // §7：手动途经点落 waypoints 表（F-IO-21/F-PLAN-43 打通）
                val wps = s.manualWaypoints.mapIndexed { i, p ->
                    PlannedWaypointEntity(
                        id = UUID.randomUUID().toString(),
                        legId = outLeg.id,
                        orderIndex = i,
                        latitude = p.latLng.latitude,
                        longitude = p.latLng.longitude,
                        name = p.name,
                        source = "MANUAL",
                    )
                }
                if (wps.isNotEmpty()) routeDao.insertWaypoints(wps)
                _state.update { it.copy(saving = false, savedRouteId = routeId) }
            } catch (t: Throwable) {
                if (t is kotlinx.coroutines.CancellationException) throw t
                _state.update { it.copy(saving = false) }
            }
        }
    }

    private fun sumOf2(a: Double?, b: Double?): Double? =
        if (a == null && b == null) null else (a ?: 0.0) + (b ?: 0.0)

    private fun haversine(a: LatLng, b: LatLng): Double {
        val r = 6371000.0
        val p1 = Math.toRadians(a.latitude)
        val p2 = Math.toRadians(b.latitude)
        val dp = Math.toRadians(b.latitude - a.latitude)
        val dl = Math.toRadians(b.longitude - a.longitude)
        val h = Math.pow(Math.sin(dp / 2), 2.0) + Math.cos(p1) * Math.cos(p2) * Math.pow(Math.sin(dl / 2), 2.0)
        return 2 * r * Math.asin(Math.sqrt(h))
    }

    companion object {
        const val MAX_WAYPOINTS = 50
        const val POLYLINE_EPSILON_M = 8.0
        const val MANUAL_SPEED_MPS = 3.5 / 3.6 // 登山平均速度（PRD 6.2.3），手动直线段耗时口径
    }
}