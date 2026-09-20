package com.gohiking.core.map.route

import android.content.Context
import com.amap.api.maps.model.LatLng
import com.amap.api.services.core.LatLonPoint
import com.amap.api.services.route.RouteSearch
import com.amap.api.services.route.RouteSearchV2
import com.amap.api.services.route.WalkPath
import com.amap.api.services.route.WalkRouteResult
import com.amap.api.services.route.WalkRouteResultV2
import kotlin.coroutines.resume
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeout

/** 规划出的单条步行线路（任务 #19/#20 消费；本批先落可用封装） */
data class PlannedPath(
    /** 总距离（米） */
    val distanceM: Float,
    /** 预计耗时（秒） */
    val durationS: Long,
    /** 全程折线：各 step 的 polyline 顺序拼接（GCJ-02，坐标不转换） */
    val points: List<LatLng>,
)

/**
 * 高德步行路径规划封装（F-PLAN-10，DEV §4.8/§4.8.1）。
 * - 策略链：V2 `ALTERNATIVE_ROUTE_THREE`（要三条备选）→ V1 `WALK_MULTI_PATH` 回退；
 * - rCode==1000 才算成功；8s 超时；整体失败返回空列表——上层必须兜底手动打点（F-PLAN-20）；
 * - 山区可能只返回 1 条甚至重复 3 条：差异是否足够由任务 #19 的实测评定，client 只如实返回。
 */
class RouteSearchClient(private val context: Context) {

    suspend fun walkRoutes(from: LatLng, to: LatLng): List<PlannedPath> {
        // V2 优先：与 Web API alternative_route 语义一致，官方原生备选能力
        val byV2 = runCatching { requestByV2(from, to) }.getOrDefault(emptyList())
        if (byV2.isNotEmpty()) return byV2
        // 同 SDK 的退化路径（DEV §4.8.1 路径②）
        return runCatching { requestByV1(from, to) }.getOrDefault(emptyList())
    }

    private suspend fun requestByV2(from: LatLng, to: LatLng): List<PlannedPath> =
        withTimeout(ROUTE_TIMEOUT_MS) {
            suspendCancellableCoroutine { cont ->
                val search = RouteSearchV2(context)
                val query = RouteSearchV2.WalkRouteQuery(
                    RouteSearchV2.FromAndTo(
                        LatLonPoint(from.latitude, from.longitude),
                        LatLonPoint(to.latitude, to.longitude),
                    ),
                )
                query.alternativeRoute = RouteSearchV2.AlternativeRoute.ALTERNATIVE_ROUTE_THREE
                search.setRouteSearchListener(object : RouteSearchV2.OnRouteSearchListener {
                    override fun onWalkRouteSearched(result: WalkRouteResultV2?, rCode: Int) {
                        if (cont.isActive) {
                            cont.resume(
                                if (rCode == RCODE_OK && result != null) result.paths.map { it.toPlannedPath() }
                                else emptyList(),
                            )
                        }
                    }

                    override fun onDriveRouteSearched(result: com.amap.api.services.route.DriveRouteResultV2?, rCode: Int) = Unit
                    override fun onBusRouteSearched(result: com.amap.api.services.route.BusRouteResultV2?, rCode: Int) = Unit
                    override fun onRideRouteSearched(result: com.amap.api.services.route.RideRouteResultV2?, rCode: Int) = Unit
                })
                search.calculateWalkRouteAsyn(query)
            }
        }

    private suspend fun requestByV1(from: LatLng, to: LatLng): List<PlannedPath> =
        withTimeout(ROUTE_TIMEOUT_MS) {
            suspendCancellableCoroutine { cont ->
                val search = RouteSearch(context)
                val query = RouteSearch.WalkRouteQuery(
                    RouteSearch.FromAndTo(
                        LatLonPoint(from.latitude, from.longitude),
                        LatLonPoint(to.latitude, to.longitude),
                    ),
                    RouteSearch.WALK_MULTI_PATH,
                )
                search.setRouteSearchListener(object : RouteSearch.OnRouteSearchListener {
                    override fun onWalkRouteSearched(result: WalkRouteResult?, rCode: Int) {
                        if (cont.isActive) {
                            cont.resume(
                                if (rCode == RCODE_OK && result != null) result.paths.map { it.toPlannedPath() }
                                else emptyList(),
                            )
                        }
                    }

                    override fun onDriveRouteSearched(result: com.amap.api.services.route.DriveRouteResult?, rCode: Int) = Unit
                    override fun onBusRouteSearched(result: com.amap.api.services.route.BusRouteResult?, rCode: Int) = Unit
                    override fun onRideRouteSearched(result: com.amap.api.services.route.RideRouteResult?, rCode: Int) = Unit
                })
                search.calculateWalkRouteAsyn(query)
            }
        }

    private fun WalkPath.toPlannedPath(): PlannedPath = PlannedPath(
        distanceM = distance,
        durationS = duration,
        points = steps.orEmpty().flatMap { step -> step.polyline.orEmpty() }
            .map { LatLng(it.latitude, it.longitude) },
    )

    private companion object {
        /** rCode==1000 才算成功（DEV §4.8） */
        const val RCODE_OK = 1000
        const val ROUTE_TIMEOUT_MS = 8_000L
    }
}
