package com.gohiking.core.elevation

import com.gohiking.core.database.entity.ElevationCacheRow
import com.gohiking.core.model.LatLngValue

/**
 * 高程缓存的 `(latKey, lngKey)` **精确回配**（DEV §3.2 硬规则；文档审阅 D3）。
 *
 * `ElevationCacheDao.query` 用的是 `latKey IN (...) AND lngKey IN (...)`——这是
 * **笛卡尔积**语义：请求 A(30.1,120.1)、B(30.2,120.2) 时，库里若存了 C(30.1,120.2)，
 * 也会被查出来。所以查询结果**必须**按组合键回配到请求点，否则高程会配到别的坐标上
 * （爬升评估出脏数据，且因为它只会让数值「略有偏差」，极难在真机上察觉）。
 *
 * 抽成纯函数是为了能单测：DAO 依赖 Room / Android，而这段配对逻辑是纯 Kotlin。
 */
object ElevationCacheMatcher {

    /** 组合键，与 [ElevationRepository] 的格网 key 同口径（WGS-84 5 位小数） */
    fun key(lat5: Double, lng5: Double): String = "$lat5,$lng5"

    /** @see key */
    fun key(point: LatLngValue): String = key(point.latitude, point.longitude)

    /**
     * @param rows DAO 返回的候选行（可能含跨点错配行、可能缺行）
     * @param wantedKeys 请求点的组合键集合
     * @return 组合键 → 海拔。**未命中的请求点不在结果里**，调用方按 null（界面「—」）处理。
     */
    fun pair(rows: List<ElevationCacheRow>, wantedKeys: Set<String>): Map<String, Double> =
        rows.mapNotNull { row ->
            val k = key(row.latKey, row.lngKey)
            if (k in wantedKeys) k to row.altitudeM else null
        }.toMap()
}
