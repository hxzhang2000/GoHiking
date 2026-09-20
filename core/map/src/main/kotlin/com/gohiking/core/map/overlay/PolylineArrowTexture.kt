package com.gohiking.core.map.overlay

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import com.amap.api.maps.model.BitmapDescriptor
import com.amap.api.maps.model.BitmapDescriptorFactory

/**
 * F-PLAN-37 方向箭头纹理（2026-09-20）。
 *
 * API 面结论（jlib 实测 `3dmap-location-search-10.0.700_loc6.4.5_sea9.7.2.jar`，
 * 脚本 `tools/m2-verify/verify_amap_arrow.py`）：
 * - `PolylineOptions.setCustomTexture(BitmapDescriptor)` 存在，纹理沿线路按绘制方向平铺；
 * - `setCustomTextureList` / `setCustomTextureIndex`（分段纹理）存在；
 * - **不存在** `setArrowShapeOnly` / `arrowLine`；
 * - 纹理与 `setDottedLine` 互斥 → 返程虚线不能直接换纹理，用「底层虚线 + 上层透明底箭头纹理线」叠加。
 *
 * 纹理 = 指向右侧的 chevron（横向，透明背景），平铺后箭头即行进方向；
 * 线色保持由底层折线负责，本层只贡献箭头。按 ARGB 色值缓存（工程内颜色固定 ≤6 种）。
 */
object PolylineArrowTexture {

    private val cache = LinkedHashMap<Int, BitmapDescriptor>()

    @Synchronized
    fun forColor(color: Int): BitmapDescriptor {
        cache[color]?.let { return it }
        val w = 48
        val h = 32
        val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bmp)
        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            this.color = color
            style = Paint.Style.STROKE
            strokeWidth = h / 4f
            strokeCap = Paint.Cap.ROUND
            strokeJoin = Paint.Join.ROUND
        }
        val path = Path().apply {
            moveTo(w * 0.22f, h * 0.18f)
            lineTo(w * 0.78f, h * 0.5f)
            lineTo(w * 0.22f, h * 0.82f)
        }
        canvas.drawPath(path, paint)
        return BitmapDescriptorFactory.fromBitmap(bmp).also { cache[color] = it }
    }
}
