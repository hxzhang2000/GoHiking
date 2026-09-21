package com.gohiking.core.map.search

import android.content.Context
import android.util.Log
import com.amap.api.maps.model.LatLng
import com.amap.api.services.core.LatLonPoint
import com.amap.api.services.core.PoiItem
import com.amap.api.services.core.ServiceSettings
import com.amap.api.services.geocoder.GeocodeSearch
import com.amap.api.services.geocoder.RegeocodeQuery
import com.amap.api.services.geocoder.RegeocodeResult
import com.amap.api.services.help.Inputtips
import com.amap.api.services.help.InputtipsQuery
import com.amap.api.services.help.Tip
import com.amap.api.services.poisearch.PoiResult
import com.amap.api.services.poisearch.PoiSearch
import kotlin.coroutines.resume
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeout

/**
 * 选点候选（F-PLAN-05）。
 * [latLng] 为 null 表示「只是个词，不是真实 POI」——上层应拿 [name] 再发一次关键词搜索
 * （PRD 6.2.1 细节 1 的三态规则；公交线路名已在 client 内过滤）。
 */
data class Suggestion(
    val name: String,
    val district: String,
    val latLng: LatLng?,
)

/** 搜索结果三态：有结果 / 确实无结果（F-PLAN-09 提示换关键词）/ 失败（离线等，降级地图点选） */
sealed interface SearchOutcome {
    data class Hits(val items: List<Suggestion>) : SearchOutcome
    data object Empty : SearchOutcome
    data class Failed(val cause: String?) : SearchOutcome
}

/**
 * 高德搜索封装（输入联想 + POI 关键词检索）。
 * - rCode==1000 才算成功（PRD 6.2.1 细节 3），其余按失败处理；
 * - 统一 8s 超时（DEV §4.8）；
 * - 坐标直接返回 GCJ-02（search 与地图同坐标系，不做转换，DEV 决策 2）。
 */
class AmapSearchClient(context: Context) {

    // N-27：原样保存传入的 Context 会把 Activity 泄漏进常驻 ViewModel——本类实例由
    // MainActivity 以 remember 持有并注入 PlanViewModel，后者挂在 Activity 的
    // ViewModelStore 上、跨 recreate 存活。
    private val appContext = context.applicationContext

    // N-16：高德**搜索** SDK 的合规接口此前全仓库零调用（上轮 C-03 只覆盖了地图与定位两侧）。
    // 未声明时高德会拦截请求（返回非 1000 的 rCode），表现为候选列表始终空、逆地理恒「—」，
    // 而这些失败被 catch 吞成 Failed/null，真机极难定位。这里在每次请求前幂等声明。
    private fun ensurePrivacyCompliance() {
        ServiceSettings.updatePrivacyShow(appContext, true, true)
        ServiceSettings.updatePrivacyAgree(appContext, true)
    }

    /**
     * N-09：原实现三处 `catch (e: Exception)` 把协程取消也吞成了「搜索不可用」——
     * 用户敲下一个字符时旧请求被取消，取消转成 Failed 后 `search()` 仍会继续执行并写入
     * `searching=false, suggestions=空, hint=OFFLINE`，界面弹出与事实相反的错误提示。
     * 这里：取消必须原样上抛；超时按「失败」降级（它也是 CancellationException，但语义上
     * 应给 UI 一个失败结果）；其余异常降级并落日志（N-46：本模块此前零日志）。
     */
    private suspend fun <T> guard(tag: String, fallback: T, block: suspend () -> T): T =
        try {
            block()
        } catch (e: TimeoutCancellationException) {
            Log.w(TAG, "高德搜索 $tag 超时", e)
            fallback
        } catch (c: CancellationException) {
            throw c
        } catch (t: Throwable) {
            Log.w(TAG, "高德搜索 $tag 失败", t)
            fallback
        }

    /** 输入联想（F-PLAN-05）。[city] 为空 = 全国搜索 */
    suspend fun inputTips(keyword: String, city: String = ""): SearchOutcome =
        guard<SearchOutcome>("inputTips", SearchOutcome.Failed(null)) {
            ensurePrivacyCompliance()
            withTimeout(SEARCH_TIMEOUT_MS) {
                suspendCancellableCoroutine { cont ->
                    try {
                        val inputtips = Inputtips(appContext, InputtipsQuery(keyword, city))
                        inputtips.setInputtipsListener { tips, rCode ->
                            if (cont.isActive) {
                                cont.resume(
                                    // N-45：原实现完全忽略 rCode，把服务端失败（Key 无效 1001、
                                    // 配额超限、参数错误）误判为「无结果」，UI 反而引导用户
                                    // 「换个关键词」。与下方 poiSearch 的口径对齐。
                                    if (rCode == RCODE_OK) {
                                        outcome(tips) { it.toSuggestion() }
                                    } else {
                                        Log.w(TAG, "inputTips rCode=$rCode")
                                        SearchOutcome.Failed("rCode=$rCode")
                                    },
                                )
                            }
                        }
                        inputtips.requestInputtipsAsyn()
                    } catch (e: Exception) {
                        if (cont.isActive) cont.resume(SearchOutcome.Failed(e.message))
                    }
                }
            }
        }

    /**
     * 关键词 POI 检索。登山场景默认周边检索（PRD 6.2.1：以视野中心为圆心比全国准得多）。
     * [center] 传 null 时退化为 [city] 城市检索（空串 = 全国）。
     */
    suspend fun poiSearch(
        keyword: String,
        city: String = "",
        center: LatLng? = null,
        radiusM: Int = DEFAULT_RADIUS_M,
    ): SearchOutcome = guard<SearchOutcome>("poiSearch", SearchOutcome.Failed(null)) {
        ensurePrivacyCompliance()
        withTimeout(SEARCH_TIMEOUT_MS) {
            suspendCancellableCoroutine { cont ->
                try {
                    val query = PoiSearch.Query(keyword, "", city)
                    query.pageSize = POI_PAGE_SIZE
                    val search = PoiSearch(appContext, query)
                    if (center != null) {
                        search.bound = PoiSearch.SearchBound(
                            LatLonPoint(center.latitude, center.longitude),
                            radiusM,
                        )
                    }
                    search.setOnPoiSearchListener(object : PoiSearch.OnPoiSearchListener {
                        override fun onPoiSearched(result: PoiResult?, rCode: Int) {
                            if (rCode != RCODE_OK) Log.w(TAG, "poiSearch rCode=$rCode")
                            if (cont.isActive) {
                                cont.resume(
                                    if (rCode == RCODE_OK && result != null) outcome(result.pois) { it.toSuggestion() }
                                    else SearchOutcome.Failed("rCode=$rCode"),
                                )
                            }
                        }

                        override fun onPoiItemSearched(item: PoiItem?, rCode: Int) = Unit
                    })
                    search.searchPOIAsyn()
                    cont.invokeOnCancellation { search.setOnPoiSearchListener(null) }
                } catch (e: Exception) {
                    if (cont.isActive) cont.resume(SearchOutcome.Failed(e.message))
                }
            }
        }
    }

    /**
     * 逆地理编码（F-MEDIA-23 照片位置描述）。坐标必须已是 GCJ-02（与高德同系，不做转换）。
     * 返回「省市区 + 名称/道路」拼接；无网或失败返回 null（调用方显示「—」，不阻塞 UI）。
     */
    suspend fun regeocode(latitude: Double, longitude: Double): String? =
        guard<String?>("regeocode", null) {
            ensurePrivacyCompliance()
            withTimeout(SEARCH_TIMEOUT_MS) {
                suspendCancellableCoroutine { cont ->
                    try {
                        val geocodeSearch = GeocodeSearch(appContext)
                        geocodeSearch.setOnGeocodeSearchListener(object : GeocodeSearch.OnGeocodeSearchListener {
                            override fun onRegeocodeSearched(result: RegeocodeResult?, rCode: Int) {
                                if (!cont.isActive) return
                                if (rCode != RCODE_OK) Log.w(TAG, "regeocode rCode=$rCode")
                                val addr = result?.regeocodeAddress
                                val desc = listOfNotNull(addr?.province, addr?.city, addr?.district)
                                    .distinct()
                                    .joinToString("")
                                    .ifEmpty { addr?.formatAddress }
                                cont.resume(desc?.takeIf { it.isNotBlank() })
                            }

                            override fun onGeocodeSearched(result: com.amap.api.services.geocoder.GeocodeResult?, rCode: Int) = Unit
                        })
                        val query = RegeocodeQuery(
                            LatLonPoint(latitude, longitude),
                            200f,
                            GeocodeSearch.AMAP,
                        )
                        geocodeSearch.getFromLocationAsyn(query)
                        cont.invokeOnCancellation { geocodeSearch.setOnGeocodeSearchListener(null) }
                    } catch (e: Exception) {
                        if (cont.isActive) cont.resume(null)
                    }
                }
            }
        }

    private inline fun <T> outcome(
        items: List<T>?,
        map: (T) -> Suggestion?,
    ): SearchOutcome {
        if (items == null) return SearchOutcome.Failed(null)
        val suggestions = items.mapNotNull(map)
        return if (suggestions.isEmpty()) SearchOutcome.Empty else SearchOutcome.Hits(suggestions)
    }

    /**
     * Tip 三态处理（PRD 6.2.1 细节 1）：
     * ① poiID+point 均非空 → 真实 POI，可直接定位；
     * ② poiID 非空、point 为空 → 公交线路名，本产品不处理，过滤；
     * ③ poiID 为空、point 为空 → 只是词，保留 name 供上层再发关键词搜索。
     */
    private fun Tip.toSuggestion(): Suggestion? {
        val point = point
        return when {
            point != null && poiID != null ->
                Suggestion(name.orEmpty(), district.orEmpty(), LatLng(point.latitude, point.longitude))
            poiID != null && point == null -> null
            else -> Suggestion(name.orEmpty(), district.orEmpty(), null)
        }
    }

    private fun PoiItem.toSuggestion(): Suggestion {
        val p = latLonPoint
        return Suggestion(
            name = title.orEmpty(),
            district = snippet.orEmpty(),
            latLng = p?.let { LatLng(it.latitude, it.longitude) },
        )
    }

    private companion object {
        /** rCode==1000 才算成功（PRD 6.2.1 细节 3） */
        const val RCODE_OK = 1000
        const val SEARCH_TIMEOUT_MS = 8_000L
        const val POI_PAGE_SIZE = 10
        const val DEFAULT_RADIUS_M = 10_000
        const val TAG = "AmapSearchClient"
    }
}
