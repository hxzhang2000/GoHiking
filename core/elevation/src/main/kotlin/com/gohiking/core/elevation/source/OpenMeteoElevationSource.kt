package com.gohiking.core.elevation.source

import com.gohiking.core.model.LatLngValue
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray

/**
 * Open-Meteo Elevation（DEV §4.10 T-07 实测首选：稳定、Copernicus DEM GLO-90、非商用条款明确、批量逗号分隔）。
 * GET https://api.open-meteo.com/v1/elevation?latitude=lat1,lat2&longitude=lng1,lng2
 * 响应：[{"latitude":..,"longitude":..,"elevation":[..]}]（单个对象，elevation 与请求点一一对应）。
 *
 * 网络层用 HttpURLConnection（不引额外 HTTP 依赖）；连接/读取各 6s 超时（§4.10 表）。
 */
@Singleton
class OpenMeteoElevationSource @Inject constructor() : ElevationRemoteSource {

    override suspend fun queryBatchWgs84(points: List<LatLngValue>): List<Double?>? =
        withContext(Dispatchers.IO) {
            if (points.isEmpty()) return@withContext emptyList()
            val lat = points.joinToString(",") { num(it.latitude) }
            val lng = points.joinToString(",") { num(it.longitude) }
            val url = "$ENDPOINT?latitude=${enc(lat)}&longitude=${enc(lng)}"
            var conn: HttpURLConnection? = null
            try {
                conn = (URL(url).openConnection() as HttpURLConnection).apply {
                    connectTimeout = TIMEOUT_MS
                    readTimeout = TIMEOUT_MS
                    requestMethod = "GET"
                }
                val code = conn.responseCode
                if (code != 200) return@withContext null
                val body = conn.inputStream.bufferedReader().use { it.readText() }
                parse(body, points.size)
            } catch (e: Exception) {
                null // 网络/解析失败：整批不可用（区分于部分点无值）
            } finally {
                conn?.disconnect()
            }
        }

    private fun parse(body: String, expected: Int): List<Double?>? = try {
        val arr = JSONArray(body)
        val elevations = arr.getJSONObject(0).getJSONArray("elevation")
        if (elevations.length() != expected) {
            null // 长度不符视为不可信，整批失败
        } else {
            List(expected) { i ->
                if (elevations.isNull(i)) null else elevations.getDouble(i)
            }
        }
    } catch (e: Exception) {
        null
    }

    /** Open-Meteo 接受最多 5 位小数；固定格式避免逗号分隔被小数位膨胀干扰 */
    private fun num(v: Double): String = String.format(java.util.Locale.ROOT, "%.5f", v)

    private fun enc(v: String): String = URLEncoder.encode(v, "UTF-8")

    private companion object {
        const val ENDPOINT = "https://api.open-meteo.com/v1/elevation"
        const val TIMEOUT_MS = 6_000
    }
}
