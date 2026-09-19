package com.gohiking.core.location.crs

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * GCJ-02 ⇄ WGS-84 坐标转换（DEV §4.1）。
 * 内部存储统一 GCJ-02；照片 EXIF / GPX / Fused 定位是 WGS-84，显示前必须转换（DEV 决策 2）。
 */
object CoordinateConverter {
    private const val A = 6378245.0                  // 克拉索夫斯基椭球长半轴（米）
    private const val EE = 0.00669342162296594323    // 第一偏心率平方
    private const val PI = kotlin.math.PI
    internal const val MAX_ITER = 10
    internal const val EPS_DEG = 1e-9                // 约 0.1 毫米

    /** 中国大陆粗略范围外不做偏移（PRD 4.3 第 5 条） */
    fun outOfChina(lat: Double, lng: Double): Boolean =
        lng < 72.004 || lng > 137.8347 || lat < 0.8293 || lat > 55.8271

    fun wgs84ToGcj02(lat: Double, lng: Double): Pair<Double, Double> {
        if (outOfChina(lat, lng)) return lat to lng
        val dLatRaw = transformLat(lng - 105.0, lat - 35.0)
        val dLngRaw = transformLng(lng - 105.0, lat - 35.0)
        val radLat = lat / 180.0 * PI
        var magic = sin(radLat)
        magic = 1 - EE * magic * magic
        val sqrtMagic = sqrt(magic)
        val dLat = (dLatRaw * 180.0) / ((A * (1 - EE)) / (magic * sqrtMagic) * PI)
        val dLng = (dLngRaw * 180.0) / (A / sqrtMagic * cos(radLat) * PI)
        return (lat + dLat) to (lng + dLng)
    }

    /** GCJ-02 → WGS-84：解析不可逆，用迭代逼近。预期 3~4 次迭代收敛（单测断言 < 5 次，§4.11） */
    fun gcj02ToWgs84(lat: Double, lng: Double): Pair<Double, Double> {
        if (outOfChina(lat, lng)) return lat to lng
        var wLat = lat; var wLng = lng
        repeat(MAX_ITER) {
            val (gLat, gLng) = wgs84ToGcj02(wLat, wLng)
            val dLat = gLat - lat; val dLng = gLng - lng
            if (abs(dLat) < EPS_DEG && abs(dLng) < EPS_DEG) return wLat to wLng
            wLat -= dLat; wLng -= dLng
        }
        return wLat to wLng
    }

    private fun transformLat(x: Double, y: Double): Double {
        var ret = -100.0 + 2.0 * x + 3.0 * y + 0.2 * y * y + 0.1 * x * y + 0.2 * sqrt(abs(x))
        ret += (20.0 * sin(6.0 * x * PI) + 20.0 * sin(2.0 * x * PI)) * 2.0 / 3.0
        ret += (20.0 * sin(y * PI) + 20.0 * sin(y / 3.0 * PI)) * 2.0 / 3.0
        ret += (160.0 * sin(y / 12.0 * PI) + 320.0 * sin(y * PI / 30.0)) * 2.0 / 3.0
        return ret
    }

    private fun transformLng(x: Double, y: Double): Double {
        var ret = 300.0 + x + 2.0 * y + 0.1 * x * x + 0.1 * x * y + 0.1 * sqrt(abs(x))
        ret += (20.0 * sin(6.0 * x * PI) + 20.0 * sin(2.0 * x * PI)) * 2.0 / 3.0
        ret += (20.0 * sin(x * PI) + 40.0 * sin(x / 3.0 * PI)) * 2.0 / 3.0
        ret += (150.0 * sin(x / 12.0 * PI) + 300.0 * sin(x / 30.0 * PI)) * 2.0 / 3.0
        return ret
    }
}
