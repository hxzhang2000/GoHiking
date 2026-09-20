package com.gohiking.core.elevation

import com.gohiking.core.common.result.GhResult
import com.gohiking.core.model.Difficulty
import com.gohiking.core.model.LatLngValue
import com.gohiking.core.location.altitude.ThresholdAccumulator
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

/** 线路评估指标（PRD 6.2.3）。爬升/海拔均为 DEM 估算值，界面必须带「估算」标注（F-PLAN-46）。 */
data class RouteMetrics(
    val distanceM: Double,
    /** 距离 ÷ 3.5 km/h（登山平均速度，PRD 6.2.3） */
    val estimatedDurationSec: Long,
    /** DEM 估算累计爬升；高程不可用为 null（绝不编造，DEV §4.7） */
    val ascentM: Double?,
    val descentM: Double?,
    val maxAltitudeM: Double?,
    val minAltitudeM: Double?,
    val elevationAvailable: Boolean,
)

/**
 * 推荐线路评估（DEV §4.7）：
 * - 沿折线每 50m 等距重采样；
 * - 批量查高程（ElevationRepository 内部分批 ≤100 点 + 三级降级）；
 * - 爬升用 ThresholdAccumulator(10m)——DEM 噪声比传感器大（PRD 6.2.3）；
 * - 高程不可用 → ascent/descent/max/min 为 null，难度仍可按「距离」给出保守评级？否——
 *   难度由「距离+爬升」综合（PRD 6.2.3），爬升缺失时 difficulty 也为 null（不编造）。
 */
object RouteEvaluator {

    private const val AVG_HIKING_SPEED_KMH = 3.5
    private const val ELEVATION_SAMPLE_INTERVAL_M = 50.0
    private const val DEM_ASCENT_THRESHOLD_M = 10.0

    private const val EARTH_RADIUS_M = 6371000.0

    /**
     * @param queryElevation 高程查询函数（生产传 ElevationRepository::query；测试传 fake，§3.3 可替身要求）
     */
    suspend fun evaluate(
        polyline: List<LatLngValue>,
        queryElevation: suspend (List<LatLngValue>) -> GhResult<List<Double?>>,
    ): RouteMetrics {
        val distance = polylineDistance(polyline)
        // 3.5 km/h = 3.5/3.6 m/s；duration = distance / speed（秒）
        val durationSec = (distance / (AVG_HIKING_SPEED_KMH / 3.6)).toLong()
        if (polyline.size < 2) {
            return RouteMetrics(distance, durationSec, null, null, null, null, false)
        }
        val samples = resample(polyline, ELEVATION_SAMPLE_INTERVAL_M)
        val elevations = when (val r = queryElevation(samples)) {
            is GhResult.Ok -> r.value
            is GhResult.Err -> null
        }
        val usable = elevations != null && samples.size == elevations.size && elevations.none { it == null }
        if (!usable) {
            return RouteMetrics(distance, durationSec, null, null, null, null, false)
        }
        val acc = ThresholdAccumulator(DEM_ASCENT_THRESHOLD_M)
        var maxAlt = Double.NEGATIVE_INFINITY
        var minAlt = Double.POSITIVE_INFINITY
        for (alt in elevations!!) {
            val a = alt!!
            acc.accept(a)
            if (a > maxAlt) maxAlt = a
            if (a < minAlt) minAlt = a
        }
        return RouteMetrics(
            distanceM = distance,
            estimatedDurationSec = durationSec,
            ascentM = acc.ascent,
            descentM = acc.descent,
            maxAltitudeM = maxAlt,
            minAltitudeM = minAlt,
            elevationAvailable = true,
        )
    }

    /**
     * 难度评级（PRD 6.2.3，**严格不等号**：恰好 5km/300m 等边界值不满足本档，归入下一档）。
     */
    fun difficultyOf(distanceM: Double, ascentM: Double): Difficulty = when {
        distanceM < 5_000 && ascentM < 300 -> Difficulty.EASY
        distanceM < 10_000 && ascentM < 800 -> Difficulty.MODERATE
        distanceM < 15_000 && ascentM < 1500 -> Difficulty.HARD
        else -> Difficulty.CHALLENGING
    }

    /**
     * 等距重采样：沿折线每 [intervalM] 取一点（含首点；尾点不足一间距时并入最后一段采样）。
     * 线性插值；同点相邻段零距离不会产生除零（dist 累加，步进 while）。
     */
    fun resample(
        polyline: List<LatLngValue>,
        intervalM: Double,
    ): List<LatLngValue> {
        if (polyline.size < 2) return polyline.toList()
        val out = ArrayList<LatLngValue>()
        out.add(polyline.first())
        var carry = 0.0 // 上一采样点到当前段起点的剩余距离
        for (i in 0 until polyline.size - 1) {
            val a = polyline[i]
            val b = polyline[i + 1]
            val segLen = haversineM(a, b)
            if (segLen <= 0.0) continue
            var d = intervalM - carry
            while (d <= segLen) {
                val t = d / segLen
                out.add(
                    LatLngValue(
                        latitude = a.latitude + (b.latitude - a.latitude) * t,
                        longitude = a.longitude + (b.longitude - a.longitude) * t,
                    ),
                )
                d += intervalM
            }
            carry = segLen - (d - intervalM)
        }
        // 尾点必须保留（峰值海拔常在端点；不足一间距时并入最后一段）
        val last = polyline.last()
        if (out.last() != last) out.add(last)
        return out
    }

    fun polylineDistance(polyline: List<LatLngValue>): Double {
        var total = 0.0
        for (i in 0 until polyline.size - 1) {
            total += haversineM(polyline[i], polyline[i + 1])
        }
        return total
    }

    fun haversineM(a: LatLngValue, b: LatLngValue): Double {
        val dLat = Math.toRadians(b.latitude - a.latitude)
        val dLng = Math.toRadians(b.longitude - a.longitude)
        val la1 = Math.toRadians(a.latitude)
        val la2 = Math.toRadians(b.latitude)
        val h = sin(dLat / 2) * sin(dLat / 2) + cos(la1) * cos(la2) * sin(dLng / 2) * sin(dLng / 2)
        val c = 2 * atan2(sqrt(h), sqrt(1 - h))
        return EARTH_RADIUS_M * c
    }
}
