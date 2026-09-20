package com.gohiking.core.data.media

import com.gohiking.core.database.entity.MediaIndexEntity
import kotlin.math.floor
import kotlin.math.pow

/**
 * 照片网格聚类（F-MEDIA-10~12，DEV §4.6）。
 *
 * - 单元格边长（度）= 单元格像素 / 该 zoom 层级全球像素 × 360；⚠ [cellPx] 必须由调用方先做
 *   密度换算（60.dp.toPx()），直接写死 60f 在高密度屏会把格子画小、聚类过散（审阅 D8）；
 * - 桶 key = floor(lat / cell) to floor(lng / cell)，O(n)；
 * - 气泡直径分级（F-MEDIA-13，UI 侧使用）：1–9 → 32dp；10–49 → 40dp；50–99 → 48dp；100+ → 56dp；
 * - 重建时机（UI 侧）：zoom 变化结束后防抖 150ms。
 */
object MediaClusterer {

    /** 聚合结果：代表点（簇内均值）+ 簇内媒体 */
    data class Cluster(val latGcj02: Double, val lngGcj02: Double, val items: List<MediaIndexEntity>)

    /** F-MEDIA-13 气泡直径分级（dp） */
    fun bubbleSizeDp(count: Int): Int = when {
        count >= 100 -> 56
        count >= 50 -> 48
        count >= 10 -> 40
        else -> 32
    }

    /** 单元格边长（度）。zoom 每 +1，格子边长减半。 */
    fun cellSizeDeg(zoom: Float, cellPx: Float): Double =
        cellPx / (256.0 * 2.0.pow(zoom.toDouble())) * 360.0

    /** 只对有 GCJ-02 坐标的媒体聚类（无坐标的不上地图） */
    fun cluster(items: List<MediaIndexEntity>, zoom: Float, cellPx: Float): List<Cluster> {
        val located = items.mapNotNull { m ->
            val lat = m.latGcj02 ?: return@mapNotNull null
            val lng = m.lngGcj02 ?: return@mapNotNull null
            Triple(lat, lng, m)
        }
        if (located.isEmpty()) return emptyList()
        val cell = cellSizeDeg(zoom, cellPx)
        val buckets = HashMap<Pair<Long, Long>, MutableList<Triple<Double, Double, MediaIndexEntity>>>()
        for (p in located) {
            val key = floor(p.first / cell).toLong() to floor(p.second / cell).toLong()
            buckets.getOrPut(key) { ArrayList() }.add(p)
        }
        return buckets.values.map { members ->
            Cluster(
                latGcj02 = members.fold(0.0) { acc, p -> acc + p.first } / members.size,
                lngGcj02 = members.fold(0.0) { acc, p -> acc + p.second } / members.size,
                items = members.map { it.third },
            )
        }
    }
}
