package com.gohiking.core.data.media

import com.gohiking.core.data.io.IoCodecs.haversineM
import com.gohiking.core.database.entity.MediaIndexEntity

/**
 * F-MEDIA-41/43 照片关联判定（纯函数，可单测）。
 *
 * 规则（PRD 7.6 / DEV §4.2）：
 * - 时间：拍摄时间落在 [tripStart - WINDOW, tripEnd + WINDOW]（WINDOW = 30 min，F-MEDIA-41）；
 *   无拍摄时间（DATE_TAKEN 为 NULL）的照片用 DATE_MODIFIED 兜底。
 *
 *   ⚠ N-42：此处的 `?: m.dateModifiedMs` 兜底**曾经是死代码** —— 候选集来自
 *   `MediaDao.inTimeRange`，而它的 SQL 是 `dateTakenMs BETWEEN ? AND ?`，SQL 三值逻辑下
 *   NULL 参与比较的结果是 NULL，那些照片在进本函数之前就被过滤掉了。
 *   DAO 已改为 `COALESCE(dateTakenMs, dateModifiedMs) BETWEEN ? AND ?`，两边口径才真正一致。
 *   （原注释写的「扫描器已保证非空时才进 time 字段」是错的：扫描器会如实写入 null。）
 * - 距离：照片 GCJ-02 坐标与任一轨迹点（GCJ-02）距离 ≤ 500 m（F-MEDIA-41）；
 * - **F-MEDIA-43 红线**：两边都必须是 GCJ-02。调用方必须传入 media.latGcj02/lngGcj02 与轨迹的
 *   GCJ-02 点；本类绝不触碰 latWgs84/lngWgs84，杜绝跨坐标系混算。
 */
object MediaTripMatcher {

    /** F-MEDIA-41 时间窗（±30 min） */
    const val WINDOW_MS: Long = 30L * 60 * 1000

    /** F-MEDIA-41 判距（米） */
    const val RADIUS_M: Double = 500.0

    /** 轨迹点投影：只取 GCJ-02 坐标，避免调用方顺手把 WGS-84 传进来 */
    data class TrackPointGcj(val latGcj02: Double, val lngGcj02: Double)

    /**
     * 返回命中的照片下标（相对 [photos]）。
     * 性能：先做轨迹包围盒粗过滤（O(n)），bbox 外的照片直接淘汰，再对剩余照片线性扫描轨迹点、
     * 命中即退出。个人行程照片通常 < 数百张，轨迹点 < 1 万，最坏也可接受。
     */
    fun match(
        points: List<TrackPointGcj>,
        photos: List<MediaIndexEntity>,
        tripStartMs: Long,
        tripEndMs: Long,
        windowMs: Long = WINDOW_MS,
        radiusM: Double = RADIUS_M,
    ): List<Int> {
        if (points.isEmpty() || photos.isEmpty()) return emptyList()

        var minLat = Double.MAX_VALUE
        var maxLat = -Double.MAX_VALUE
        var minLng = Double.MAX_VALUE
        var maxLng = -Double.MAX_VALUE
        for (p in points) {
            if (p.latGcj02 < minLat) minLat = p.latGcj02
            if (p.latGcj02 > maxLat) maxLat = p.latGcj02
            if (p.lngGcj02 < minLng) minLng = p.lngGcj02
            if (p.lngGcj02 > maxLng) maxLng = p.lngGcj02
        }
        // 500m ≈ 0.0045° 纬度；经度按保守 0.01° 余量放大，粗过滤只求不漏
        val latPad = radiusM / 111_000.0 + 1e-6
        val lngPad = 0.01 + radiusM / 111_000.0
        val tFrom = tripStartMs - windowMs
        val tTo = tripEndMs + windowMs

        val hits = ArrayList<Int>()
        photos.forEachIndexed { idx, m ->
            val lat = m.latGcj02 ?: return@forEachIndexed
            val lng = m.lngGcj02 ?: return@forEachIndexed
            val t = m.dateTakenMs ?: m.dateModifiedMs
            if (t < tFrom || t > tTo) return@forEachIndexed
            if (lat < minLat - latPad || lat > maxLat + latPad) return@forEachIndexed
            if (lng < minLng - lngPad || lng > maxLng + lngPad) return@forEachIndexed
            for (p in points) {
                if (haversineM(lat, lng, p.latGcj02, p.lngGcj02) <= radiusM) {
                    hits.add(idx)
                    break
                }
            }
        }
        return hits
    }
}
