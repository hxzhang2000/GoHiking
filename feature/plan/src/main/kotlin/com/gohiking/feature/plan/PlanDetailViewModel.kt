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
import com.gohiking.core.database.entity.PlannedRouteWithLegs
import com.gohiking.core.elevation.RouteEvaluator
import com.gohiking.core.elevation.RouteMetrics
import com.gohiking.core.map.route.RouteSearchClient
import com.gohiking.core.model.LatLngValue
import com.gohiking.core.resources.R as CoreR
import java.util.UUID
import kotlin.math.ceil
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * 计划详情编辑（P-07→详情，需求③）：在地图上查看/调整已存计划——
 * 拖动起终点、重新规划去程/返程（高德步行，多段：起点→途经点们→终点）、
 * 手动加行程中间点、原路返回；保存时同事务替换 legs 并重算汇总（H-06：爬升
 * 不可用落 null，绝不编造；difficulty 由距离+爬升重评）。
 */
data class PlanDetailUiState(
    val loading: Boolean = true,
    val notFound: Boolean = false,
    val routeName: String = "",
    val outboundPoints: List<LatLng> = emptyList(),
    val returnPoints: List<LatLng> = emptyList(),
    val start: LatLng? = null,
    val end: LatLng? = null,
    val waypoints: List<LatLng> = emptyList(),
    val outboundDistM: Double = 0.0,
    val outboundDurS: Long = 0,
    val returnDistM: Double = 0.0,
    val returnDurS: Long = 0,
    val planning: Boolean = false,
    val planFailed: Boolean = false,
    val saving: Boolean = false,
    val dirty: Boolean = false,
) {
    val hasReturn: Boolean get() = returnPoints.isNotEmpty()
}

class PlanDetailViewModel(
    private val routeId: String,
    private val routeDao: PlannedRouteDao,
    private val routeClient: RouteSearchClient,
    private val queryElevation: suspend (List<LatLngValue>) -> GhResult<List<Double?>>,
) : ViewModel() {

    private var original: PlannedRouteEntity? = null
    private var replanJob: Job? = null
    private var saveJob: Job? = null

    private val _state = MutableStateFlow(PlanDetailUiState())
    val state: StateFlow<PlanDetailUiState> = _state

    init {
        load()
    }

    private fun load() {
        viewModelScope.launch {
            val withLegs = routeDao.withLegs(routeId)
            if (withLegs == null) {
                _state.update { it.copy(loading = false, notFound = true) }
                return@launch
            }
            original = withLegs.route
            val out = withLegs.legs.firstOrNull { it.legType == "OUTBOUND" }
            val ret = withLegs.legs.firstOrNull { it.legType == "RETURN" }
            val outPts = out?.let { runCatching { PolylineJson.decode(it.polylineJson) }.getOrNull() }
                .orEmpty()
                .map { LatLng(it.latitude, it.longitude) }
            val retPts = ret?.let { runCatching { PolylineJson.decode(it.polylineJson) }.getOrNull() }
                .orEmpty()
                .map { LatLng(it.latitude, it.longitude) }
            _state.value = PlanDetailUiState(
                loading = false,
                routeName = withLegs.route.name,
                outboundPoints = outPts,
                returnPoints = retPts,
                start = outPts.firstOrNull(),
                end = outPts.lastOrNull(),
                outboundDistM = out?.distanceM ?: 0.0,
                outboundDurS = (out?.estimatedMin ?: 0) * 60L,
                returnDistM = ret?.distanceM ?: 0.0,
                returnDurS = (ret?.estimatedMin ?: 0) * 60L,
            )
        }
    }

    /** 拖动起点/终点（F-PLAN-06 同款交互） */
    fun moveStart(latLng: LatLng) = moveEndpoint { it.copy(start = latLng, dirty = true) }
    fun moveEnd(latLng: LatLng) = moveEndpoint { it.copy(end = latLng, dirty = true) }

    private inline fun moveEndpoint(update: (PlanDetailUiState) -> PlanDetailUiState) {
        if (_state.value.planning || _state.value.saving) return
        _state.update(update)
    }

    /** 需求③：地图点击加行程中间点（waypointMode 由 UI 控制） */
    fun addWaypoint(latLng: LatLng) {
        val s = _state.value
        if (s.planning || s.saving || s.start == null || s.end == null) return
        if (s.waypoints.size >= MAX_WAYPOINTS) return
        _state.update { it.copy(waypoints = it.waypoints + latLng, dirty = true) }
    }

    fun undoWaypoint() {
        _state.update { it.copy(waypoints = it.waypoints.dropLast(1), dirty = true) }
    }

    fun clearWaypoints() {
        replanJob?.cancel()
        _state.update { it.copy(waypoints = emptyList(), dirty = true) }
    }

    /** 重新规划去程：起点 →(各途经点)→ 终点 串行多段（高德 QPS 限制，DEV §4.8） */
    fun replanOutbound() {
        val s = _state.value
        val chain = listOfNotNull(s.start) + s.waypoints + listOfNotNull(s.end)
        if (chain.size < 2 || s.planning || s.saving) return
        replanJob?.cancel()
        replanJob = viewModelScope.launch {
            _state.update { it.copy(planning = true, planFailed = false) }
            var points = listOf(chain.first())
            var dist = 0.0
            var dur = 0L
            for (i in 0 until chain.size - 1) {
                val path = routeClient.walkRoutes(chain[i], chain[i + 1]).firstOrNull()
                if (path == null) {
                    _state.update { it.copy(planning = false, planFailed = true) }
                    return@launch
                }
                points = points + path.points
                dist += path.distanceM
                dur += path.durationS
            }
            _state.update {
                it.copy(
                    planning = false,
                    outboundPoints = points,
                    outboundDistM = dist,
                    outboundDurS = dur,
                    dirty = true,
                )
            }
        }
    }

    /** 原路返回：去程反向（PRD 6.2 语义） */
    fun returnBack() {
        val s = _state.value
        if (s.outboundPoints.size < 2 || s.planning || s.saving) return
        _state.update {
            it.copy(
                returnPoints = s.outboundPoints.reversed(),
                returnDistM = s.outboundDistM,
                returnDurS = s.outboundDurS,
                dirty = true,
            )
        }
    }

    /** 重新规划返程：终点 → 起点（不经过中间点） */
    fun replanReturn() {
        val s = _state.value
        val a = s.end ?: return
        val b = s.start ?: return
        if (s.planning || s.saving) return
        replanJob?.cancel()
        replanJob = viewModelScope.launch {
            _state.update { it.copy(planning = true, planFailed = false) }
            val path = routeClient.walkRoutes(a, b).firstOrNull()
            if (path == null) {
                _state.update { it.copy(planning = false, planFailed = true) }
                return@launch
            }
            _state.update {
                it.copy(
                    planning = false,
                    returnPoints = path.points,
                    returnDistM = path.distanceM.toDouble(),
                    returnDurS = path.durationS,
                    dirty = true,
                )
            }
        }
    }

    /** 保存：重评爬升（DEM，H-06 不可用为 null）→ 同事务替换 legs + 更新主表 */
    fun save() {
        val s = _state.value
        val route = original ?: return
        if (s.saving || s.planning || !s.dirty || s.outboundPoints.size < 2) return
        saveJob?.cancel()
        saveJob = viewModelScope.launch {
            _state.update { it.copy(saving = true) }
            try {
                val outPts = s.outboundPoints.map { LatLngValue(it.latitude, it.longitude) }
                val retPts = s.returnPoints.map { LatLngValue(it.latitude, it.longitude) }
                val outMetrics = runCatching { RouteEvaluator.evaluate(outPts, queryElevation) }.getOrNull()
                val retMetrics = if (retPts.size >= 2) {
                    runCatching { RouteEvaluator.evaluate(retPts, queryElevation) }.getOrNull()
                } else {
                    null
                }
                fun legOf(type: String, points: List<LatLng>, dist: Double, dur: Long, m: RouteMetrics?): PlannedLegEntity {
                    val simplified = PolylineSimplifier.simplify(points.map { LatLngValue(it.latitude, it.longitude) }, POLYLINE_EPSILON_M)
                    return PlannedLegEntity(
                        id = UUID.randomUUID().toString(),
                        plannedRouteId = route.id,
                        legType = type,
                        distanceM = dist,
                        // H-06：高程不可用落 null，绝不编造；difficulty 由距离+爬升重评
                        ascentM = m?.ascentM,
                        descentM = m?.descentM,
                        estimatedMin = ceil(dur / 60.0).toInt(),
                        difficulty = m?.let { mm ->
                            mm.ascentM?.let { asc -> RouteEvaluator.difficultyOf(mm.distanceM, asc).name }
                        },
                        polylineJson = PolylineJson.encode(simplified),
                    )
                }
                val legs = buildList {
                    add(legOf("OUTBOUND", s.outboundPoints, s.outboundDistM, s.outboundDurS, outMetrics))
                    if (retPts.size >= 2) {
                        add(legOf("RETURN", s.returnPoints, s.returnDistM, s.returnDurS, retMetrics))
                    }
                }
                fun sumOf2(a: Double?, b: Double?): Double? =
                    if (a == null && b == null) null else (a ?: 0.0) + (b ?: 0.0)
                val updated = route.copy(
                    totalDistanceM = legs.sumOf { it.distanceM },
                    totalAscentM = sumOf2(legs.getOrNull(0)?.ascentM, legs.getOrNull(1)?.ascentM),
                    totalDescentM = sumOf2(legs.getOrNull(0)?.descentM, legs.getOrNull(1)?.descentM),
                )
                routeDao.replaceLegsAndSave(updated, legs)
                original = updated
                _state.update { it.copy(saving = false, dirty = false) }
            } catch (t: Throwable) {
                if (t is kotlinx.coroutines.CancellationException) throw t
                _state.update { it.copy(saving = false) }
            }
        }
    }

    companion object {
        /** 与 PlanViewModel 同参：途经点上限 50（F-PLAN-28）、抽稀 ε（DEV §4.5） */
        const val MAX_WAYPOINTS = 50
        const val POLYLINE_EPSILON_M = 8.0
    }
}