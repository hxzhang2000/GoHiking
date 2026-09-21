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

    /**
     * 媒体读取权限（照片条与照片地图共用）——**全仓库唯一判定口径**（N-23）。
     *
     * 三态（不是布尔）：
     * - 完全授权：Android 13+ READ_MEDIA_IMAGES/VIDEO，低版本 READ_EXTERNAL_STORAGE；
     * - **部分授权**：Android 14（API 34）+ 用户在系统弹窗里选了「选择照片」，
     *   系统只授予 READ_MEDIA_VISUAL_USER_SELECTED（N-24）。这**不是拒绝**：
     *   MediaStore 仍可读，只是只能读用户选中的那批。此前把它判成 false，
     *   于是再次弹出「授权」引导卡 → 再点 → 系统对话框已经答过不再出现 → 死循环；
     * - 拒绝：以上都没有。
     */
    fun hasMediaAccess(context: Context): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) {
            return ContextCompat.checkSelfPermission(
                context,
                Manifest.permission.READ_EXTERNAL_STORAGE,
            ) == PackageManager.PERMISSION_GRANTED
        }
        val full = ContextCompat.checkSelfPermission(context, Manifest.permission.READ_MEDIA_IMAGES) ==
            PackageManager.PERMISSION_GRANTED ||
            ContextCompat.checkSelfPermission(context, Manifest.permission.READ_MEDIA_VIDEO) ==
            PackageManager.PERMISSION_GRANTED
        if (full) return true
        // N-24：Android 14+ 的「部分访问」同样可读 MediaStore
        return Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE &&
            ContextCompat.checkSelfPermission(
                context,
                Manifest.permission.READ_MEDIA_VISUAL_USER_SELECTED,
            ) == PackageManager.PERMISSION_GRANTED
    }

    /** N-24：是否只是「部分访问」（Android 14+ 选照片模式）——用于提示用户可扩展选择 */
    fun hasPartialMediaAccessOnly(context: Context): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.UPSIDE_DOWN_CAKE) return false
        if (!hasMediaAccess(context)) return false
        val full = ContextCompat.checkSelfPermission(context, Manifest.permission.READ_MEDIA_IMAGES) ==
            PackageManager.PERMISSION_GRANTED ||
            ContextCompat.checkSelfPermission(context, Manifest.permission.READ_MEDIA_VIDEO) ==
            PackageManager.PERMISSION_GRANTED
        return !full
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
