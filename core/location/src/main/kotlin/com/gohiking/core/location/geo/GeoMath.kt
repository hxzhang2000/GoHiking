package com.gohiking.core.location.geo

import kotlin.math.asin
import kotlin.math.cos
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.sqrt

/** 距离与速度计算（DEV §4.2）。输入必须同坐标系；GCJ-02 差分距离误差实测 ≤ 1% */
object GeoMath {
    private const val EARTH_R = 6371008.8   // WGS-84 平均半径（米）

    /** Haversine。输入必须同坐标系 */
    fun distanceMeters(lat1: Double, lng1: Double, lat2: Double, lng2: Double): Double {
        val p1 = Math.toRadians(lat1); val p2 = Math.toRadians(lat2)
        val dp = Math.toRadians(lat2 - lat1); val dl = Math.toRadians(lng2 - lng1)
        val h = sin(dp / 2).pow(2) + cos(p1) * cos(p2) * sin(dl / 2).pow(2)
        return 2 * EARTH_R * asin(minOf(1.0, sqrt(h)))
    }

    /** 差分速度；优先用定位 SDK 返回的 speed，为 0 或缺失时才用本函数（DEV §4.2） */
    fun speedMps(distanceM: Double, durationMs: Long): Double =
        if (durationMs <= 0) 0.0 else distanceM / (durationMs / 1000.0)
}
