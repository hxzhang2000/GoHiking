package com.gohiking.core.common.geo

import com.gohiking.core.model.LatLngValue
import kotlin.math.cos

/**
 * 轨迹抽稀（DEV §4.5，PRD 6.3.7「绘制时按缩放级别抽稀」）。
 *
 * 硬规则：
 * - 抽稀只发生在【渲染层】，数据库永远保留原始点（F-HIS-32 原始点数据表必须读原始数据）；
 * - 按 segmentIndex 分段抽稀，跨 segment 不合并（否则断开的两段会被连线）；
 * - 性能：10000 点 < 30ms（Default 线程），迭代式实现避免深递归栈溢出。
 */
object PolylineSimplifier {

    /** 容差按缩放级别取值（DEV §4.5 表） */
    fun epsilonForZoom(zoom: Float): Double = when {
        zoom < 10f -> 40.0
        zoom < 13f -> 25.0
        zoom < 15f -> 12.0
        zoom < 17f -> 5.0
        else -> 0.0 // ≥17 不抽稀，用户要看清每个拐弯
    }

    /**
     * Douglas-Peucker 抽稀。@param epsilonM 容差（米）；≤0 原样返回。
     * 距离用局部等距投影近似：以首点为原点，x = Δlng·cos(lat0)，y = Δlat。
     */
    fun simplify(points: List<LatLngValue>, epsilonM: Double): List<LatLngValue> {
        if (epsilonM <= 0 || points.size <= 2) return points
        val lat0 = Math.toRadians(points.first().latitude)
        val kx = 111_320.0 * cos(lat0) // 每度经度的米数（局部近似）
        val ky = 110_540.0 // 每度纬度的米数
        val px = DoubleArray(points.size) { points[it].longitude * kx }
        val py = DoubleArray(points.size) { points[it].latitude * ky }
        val eps2 = epsilonM * epsilonM

        val keep = BooleanArray(points.size)
        keep[0] = true
        keep[points.size - 1] = true
        // 迭代栈：存 [start, end] 区间
        val stack = ArrayDeque<IntArray>()
        stack.addLast(intArrayOf(0, points.size - 1))
        while (stack.isNotEmpty()) {
            val range = stack.removeLast()
            val s = range[0]
            val e = range[1]
            if (e - s < 2) continue
            var maxDist2 = -1.0
            var maxIdx = -1
            val ax = px[s]; val ay = py[s]
            val bx = px[e]; val by = py[e]
            val dx = bx - ax; val dy = by - ay
            val len2 = dx * dx + dy * dy
            for (i in s + 1 until e) {
                val d2 = if (len2 == 0.0) {
                    val ex = px[i] - ax; val ey = py[i] - ay
                    ex * ex + ey * ey
                } else {
                    // 点到线段距离（垂足参数截断）
                    var t = ((px[i] - ax) * dx + (py[i] - ay) * dy) / len2
                    t = t.coerceIn(0.0, 1.0)
                    val ex = px[i] - (ax + t * dx); val ey = py[i] - (ay + t * dy)
                    ex * ex + ey * ey
                }
                if (d2 > maxDist2) {
                    maxDist2 = d2
                    maxIdx = i
                }
            }
            if (maxDist2 > eps2 && maxIdx > 0) {
                keep[maxIdx] = true
                stack.addLast(intArrayOf(s, maxIdx))
                stack.addLast(intArrayOf(maxIdx, e))
            }
        }
        val out = ArrayList<LatLngValue>(keep.count { it })
        for (i in points.indices) if (keep[i]) out.add(points[i])
        return out
    }

    /** 分段抽稀：segmentIndex 相同的点为一段，跨段不合并（DEV §4.5 规则表） */
    fun simplifyBySegment(
        points: List<Pair<Int, LatLngValue>>, // (segmentIndex, point)
        epsilonM: Double,
    ): List<LatLngValue> {
        if (epsilonM <= 0) return points.map { it.second }
        val out = ArrayList<LatLngValue>(points.size)
        var i = 0
        while (i < points.size) {
            var j = i
            while (j < points.size && points[j].first == points[i].first) j++
            out.addAll(simplify(points.subList(i, j).map { it.second }, epsilonM))
            i = j
        }
        return out
    }
}
