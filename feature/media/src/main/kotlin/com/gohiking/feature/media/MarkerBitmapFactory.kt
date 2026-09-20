package com.gohiking.feature.media

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.PorterDuff
import android.graphics.PorterDuffXfermode
import coil3.BitmapImage
import coil3.ImageLoader
import coil3.request.ErrorResult
import coil3.request.ImageRequest
import coil3.request.SuccessResult
import coil3.size.Size

/**
 * 聚合 marker 位图（P-12，F-MEDIA-13/14）。
 * - 气泡：圆形 + 数量文字，直径按 MediaClusterer.bubbleSizeDp 四级（dp→px 由 density 换算）；
 * - 单点缩略图：Coil 同步加载（必须在 IO 线程调用），失败回退气泡；
 * - bitmap 尺寸用 px（AMap marker icon 直接吃像素）。
 */
object MarkerBitmapFactory {

    private val bubbleFill = Color.parseColor("#E65100") // 与登顶标记同色系（橙红）
    private val bubbleStroke = Color.WHITE
    private val textColor = Color.WHITE

    private const val THUMB_PX = 96

    /** 聚合气泡（F-MEDIA-13）。[density] = 屏幕密度（dp→px 系数） */
    fun bubble(context: Context, count: Int, density: Float): Bitmap {
        val sizeDp = com.gohiking.core.data.media.MediaClusterer.bubbleSizeDp(count)
        val sizePx = (sizeDp * density).toInt().coerceAtLeast(16)
        val bitmap = Bitmap.createBitmap(sizePx, sizePx, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = bubbleFill
            style = Paint.Style.FILL
        }
        val cx = sizePx / 2f
        val r = sizePx / 2f - 2f
        canvas.drawCircle(cx, cx, r, paint)
        // 白描边
        paint.style = Paint.Style.STROKE
        paint.color = bubbleStroke
        paint.strokeWidth = 2f * density
        canvas.drawCircle(cx, cx, r - paint.strokeWidth / 2f, paint)
        // 数量文字
        paint.style = Paint.Style.FILL
        paint.color = textColor
        paint.textSize = sizePx * 0.42f
        paint.textAlign = Paint.Align.CENTER
        val text = if (count > 999) "999+" else count.toString()
        val textY = cx - (paint.descent() + paint.ascent()) / 2f
        canvas.drawText(text, cx, textY, paint)
        return bitmap
    }

    /**
     * 单点缩略图（F-MEDIA-14/22）：Coil 同步解码为方形小图，失败返回 null（调用方回退气泡）。
     * 必须在 IO 线程调用；Coil 内存缓存兜底，重复进入不重复解码。
     */
    suspend fun thumbnail(context: Context, imageLoader: ImageLoader, uri: String): Bitmap? =
        when (val result = imageLoader.execute(
            ImageRequest.Builder(context)
                .data(uri)
                .size(Size(THUMB_PX, THUMB_PX))
                .build(),
        )) {
            is SuccessResult -> (result.image as? BitmapImage)?.bitmap
            is ErrorResult -> null
            else -> null
        }

    /** 圆形裁剪（缩略图 marker 用，F-MEDIA-14 圆形缩略图点） */
    fun circleCrop(src: Bitmap): Bitmap {
        val size = minOf(src.width, src.height)
        val out = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(out)
        val paint = Paint(Paint.ANTI_ALIAS_FLAG)
        canvas.drawCircle(size / 2f, size / 2f, size / 2f, paint)
        paint.xfermode = PorterDuffXfermode(PorterDuff.Mode.SRC_IN)
        val left = (src.width - size) / 2f
        val top = (src.height - size) / 2f
        canvas.drawBitmap(src, -left, -top, paint)
        return out
    }
}
