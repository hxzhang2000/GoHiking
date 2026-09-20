package com.gohiking.core.data.media

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.content.ContextCompat
import com.gohiking.core.database.GhDatabase
import com.gohiking.core.database.entity.MediaIndexEntity
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * 媒体仓库（DEV §2.3 / §4.6 / §798 坐标转换落点）。
 * - 扫描缓存：F-MEDIA-01~09（扫描器在 [MediaScanner]）；
 * - 记录关联照片：F-MEDIA-40/41/43（时间窗 ±30min + GCJ-02 判距 500m，判定在 [MediaTripMatcher]）；
 * - 权限判断：UI 侧据此决定是否展示照片区 / 引导授权（Android 13+ 用细粒度媒体权限）。
 */
@Singleton
class MediaRepository @Inject constructor(
    private val db: GhDatabase,
    private val scanner: MediaScanner,
) {

    private val mediaDao get() = db.mediaDao()

    /** 媒体读取权限（照片条与照片地图共用）：Android 13+ 细粒度，低版本 READ_EXTERNAL_STORAGE */
    fun hasMediaAccess(context: Context): Boolean {
        val permission = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            Manifest.permission.READ_MEDIA_IMAGES
        } else {
            Manifest.permission.READ_EXTERNAL_STORAGE
        }
        return ContextCompat.checkSelfPermission(context, permission) == PackageManager.PERMISSION_GRANTED
    }

    /** 精确照片位置权限（F-MEDIA-05）：Android 10+ 才有；未授权时位置模糊 ~1km */
    fun hasMediaLocationAccess(context: Context): Boolean =
        Build.VERSION.SDK_INT < Build.VERSION_CODES.Q ||
            ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_MEDIA_LOCATION) ==
            PackageManager.PERMISSION_GRANTED

    /** 增量扫描（F-MEDIA-08/09）。调用方需已确认媒体权限。 */
    suspend fun rescan(context: Context, onProgress: (suspend (MediaScanner.ScanProgress) -> Unit)? = null) =
        scanner.rescan(hasMediaLocationAccess(context), onProgress)

    /**
     * 某条记录关联的照片/视频（F-MEDIA-40/41/43）。
     * 照片坐标用 media_index.latGcj02（扫描时已转 GCJ-02），与 GCJ-02 轨迹同系判距——
     * **绝不用 latWgs84**（F-MEDIA-43 红线）。时间窗 ±30min，判距 500m（F-MEDIA-41）。
     */
    suspend fun photosForTrip(
        tripId: String,
        tripStartMs: Long,
        tripEndMs: Long,
        trackPointsGcj: List<MediaTripMatcher.TrackPointGcj>,
    ): List<MediaIndexEntity> = withContext(Dispatchers.IO) {
        val from = tripStartMs - MediaTripMatcher.WINDOW_MS
        val to = tripEndMs + MediaTripMatcher.WINDOW_MS
        val candidates = mediaDao.inTimeRange(from, to)
        val hits = MediaTripMatcher.match(
            points = trackPointsGcj,
            photos = candidates,
            tripStartMs = tripStartMs,
            tripEndMs = tripEndMs,
        )
        hits.map { candidates[it] }.sortedBy { it.dateTakenMs ?: it.dateModifiedMs }
    }

    /** 全部有 GCJ-02 坐标的媒体（照片地图 P-12 数据源；mediaDao.allLocated） */
    suspend fun located(): List<MediaIndexEntity> = withContext(Dispatchers.IO) { mediaDao.allLocated() }

    /** 已建立引用的媒体（media_ref，导入/手动关联用） */
    suspend fun refsOf(tripId: String) = withContext(Dispatchers.IO) { mediaDao.refsOf(tripId) }
}
