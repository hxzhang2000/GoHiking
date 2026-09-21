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

    /**
     * N-47：DP 总扫描量的上界系数（相对点数 n）。
     *
     * 定这个数的依据（实测，10000 点，epsilon=12m）：
     * - **真实 GPS 轨迹**的扫描量 ≈ 15n（噪声型，切分平衡，O(n log k)）
     *   到 202n（盘山型，切分不平衡），且 scans/n 不随 n 增长；
     * - **理论最坏**是 n²/2，即 5000n。
     *
     * 取 1024n：给真实轨迹 ~5 倍余量，**任何真实轨迹都碰不到**（正常输入输出与
     * 改动前逐点一致）；同时把最坏情形削掉约 5 倍。
     *
     * ⚠ 不要把它调小：实测用 64n 会把一条正常轨迹的输出从 3000 点砍到 68 点，
     * 且保留点全挤在前 10% —— 形状严重失真。它是**防 ANR 的安全网**，不是提速手段。
     */
    private const val SCAN_BUDGET_FACTOR = 1024L

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
        // N-47：Douglas-Peucker 的**最坏情形是 O(n²)** —— 当绝大多数点都要被保留时
        // （epsilon 很小、或轨迹本身抖动剧烈），分割次数趋近 n、每次平均扫描 n/2，
        // 总扫描量达 n²/2。实测 10000 点：随机游走 80 万次扫描（O(n log n)），
        // 而「每个点都在容差外」的折线是 5000 万次 —— JVM 上 100~250ms，详情页直接掉帧。
        // 这不需要病态输入：epsilon 略大于 0 且轨迹抖，就会命中。
        // 这里给总扫描量设上界，超出即停止细分（阈值见 [SCAN_BUDGET_FACTOR]）。
        //
        // 两个关键性质，决定了这个改动的兼容性：
        // ① **处理顺序不影响结果**：每个区间的 keep 判定是独立的、累积取并集，
        //    因此预算未耗尽时（正常输入）输出与改动前**逐点完全一致**，现有测试无需放宽。
        // ② **按区间长度从大到小处理**（而不是原来的 DFS 后进先出）：预算耗尽时，
        //    若用 DFS 会一路往一侧深挖，导致另一半只剩首尾两点、形状严重失真；
        //    按长度优先则已处理完全部大区间，剩下的都是细节级小区间 ——
        //    降级是「全局均匀地少一点细节」，而不是「某一半被抹平」。
        val scanBudget = points.size.toLong() * SCAN_BUDGET_FACTOR
        var scanned = 0L
        // 迭代栈：存 [start, end] 区间，**按区间长度从大到小**取（见下）。
        val stack = java.util.PriorityQueue<IntArray>(compareByDescending<IntArray> { it[1] - it[0] })
        stack.add(intArrayOf(0, points.size - 1))
        while (stack.isNotEmpty()) {
            if (scanned >= scanBudget) break
            val range = stack.poll() ?: continue // Java PriorityQueue.poll() 是平台类型，可空
            val s = range[0]
            val e = range[1]
            if (e - s < 2) continue
            scanned += e - s - 1
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
                stack.add(intArrayOf(s, maxIdx))
                stack.add(intArrayOf(maxIdx, e))
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
