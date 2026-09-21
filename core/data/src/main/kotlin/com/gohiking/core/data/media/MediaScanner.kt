package com.gohiking.core.data.media

import android.content.ContentUris
import android.content.Context
import android.net.Uri
import android.provider.MediaStore
import androidx.exifinterface.media.ExifInterface
import com.gohiking.core.database.dao.MediaDao
import com.gohiking.core.database.entity.MediaIndexEntity
import com.gohiking.core.location.crs.CoordinateConverter
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import timber.log.Timber

/**
 * 全库媒体扫描（F-MEDIA-01/02/03/05/09，DEV §3.1.1 ②）。
 *
 * 位置来源优先级（F-MEDIA-02）：MediaStore LATITUDE/LONGITUDE（旧设备）→ EXIF GPS → 无位置忽略。
 * 坐标系（F-MEDIA-03）：EXIF/MediaStore 的 GPS 是 WGS-84，写入 media_index 时**一次转换**成
 * GCJ-02（latGcj02/lngGcj02），WGS-84 原值同时保留（latWgs84/lngWgs84）。
 * 精度（F-MEDIA-05）：未授 ACCESS_MEDIA_LOCATION 时位置被系统模糊到 ~1km，isApproximate = true。
 * 增量（F-MEDIA-09）：按复合主键 (mediaStoreId, mediaType) + dateModifiedMs 比对，只重读新增/
 * 修改条目的 EXIF；MediaStore 已删除的条目同步从缓存移除。
 */
@Singleton
class MediaScanner @Inject constructor(
    @ApplicationContext private val context: Context,
    private val mediaDao: MediaDao,
) {

    data class ScanProgress(val scanned: Int, val total: Int)

    data class Result(
        val scanned: Int,
        val added: Int,
        val updated: Int,
        val removed: Int,
        val located: Int,
        val approximate: Int,
    )

    /** 一条 MediaStore 命中（扫描中间产物，未落库） */
    private data class StoreRow(
        val mediaStoreId: Long,
        val mediaType: String, // IMAGE / VIDEO
        val uri: Uri,
        val dateModifiedMs: Long,
        val dateTakenMs: Long?,
        val durationMs: Long?,
        val sizeBytes: Long?,
    )

    /**
     * 增量扫描并写 media_index。[hasMediaLocationPermission] 决定 EXIF 是否为原始 GPS 与
     * isApproximate 标记（F-MEDIA-05）。[onProgress] 在 IO 线程回调（F-MEDIA-08 异步不阻塞 UI）。
     */
    suspend fun rescan(
        hasMediaLocationPermission: Boolean,
        onProgress: (suspend (ScanProgress) -> Unit)? = null,
    ): Result = withContext(Dispatchers.IO) {
        val (rows, anyTableFailed) = queryStore()

        // DB 侧现有指纹：key=(id, type) -> dateModifiedMs（F-MEDIA-09 比对基准）
        val oldKeys = mediaDao.indexKeys().associate { (it.mediaStoreId to it.mediaType) to it.dateModifiedMs }

        var added = 0
        var updated = 0
        var located = 0
        var approximate = 0
        val seenKeys = HashSet<Pair<Long, String>>(rows.size)
        val toWrite = ArrayList<MediaIndexEntity>()

        rows.forEachIndexed { index, row ->
            val key = row.mediaStoreId to row.mediaType
            seenKeys.add(key)
            val oldModified = oldKeys[key]
            if (oldModified != null && oldModified == row.dateModifiedMs) {
                return@forEachIndexed // 未变化，跳过 EXIF 重读（F-MEDIA-09）
            }
            val entity = buildEntity(row, hasMediaLocationPermission)
            if (entity.latGcj02 != null) located++
            if (entity.isApproximate) approximate++
            toWrite.add(entity)
            if (oldModified == null) added++ else updated++
            if (onProgress != null && (index % 50 == 0 || index == rows.size - 1)) {
                onProgress(ScanProgress(index + 1, rows.size))
            }
        }
        if (toWrite.isNotEmpty()) mediaDao.upsertIndex(toWrite)

        // 已删除的媒体从缓存移除（F-MEDIA-09）
        //
        // N-23：**只要有任意一张表查询失败，就必须跳过删除同步**。
        // 删除同步的判据是「库里有、但这次没扫到 = 已被用户删掉」，而这个推论的前提是
        // 「扫描结果是完整的」。查询失败时 seenKeys 天然不完整，照常删除会把
        // 「还在、只是这次没查到」的媒体索引全部清掉；两张表都失败时更是直接清空
        // 整张 media_index —— 用户会看到照片地图一夜之间全空，且无法自愈。
        // 查询不可信时宁可留着旧数据（下次成功扫描会自愈），也绝不误删。
        var removed = 0
        if (!anyTableFailed) {
            oldKeys.keys.forEach { key ->
                if (key !in seenKeys) {
                    mediaDao.deleteIndex(key.first, key.second)
                    removed++
                }
            }
        } else {
            Timber.w("MediaStore 查询有失败，本次跳过删除同步（避免误删 %d 条索引）", oldKeys.size)
        }

        Result(
            scanned = rows.size,
            added = added,
            updated = updated,
            removed = removed,
            located = located,
            approximate = approximate,
        )
    }

    /** queryStore 的结果：[rows] 已扫到的行，[anyTableFailed] 是否有表查询失败（N-23） */
    private data class QueryResult(val rows: List<StoreRow>, val anyTableFailed: Boolean)

    /**
     * 查询两张 MediaStore 表（image + video），F-MEDIA-01。
     *
     * N-23：原实现两张表在同一个裸循环里查，任何一张抛异常（`getColumnIndexOrThrow`
     * 在缺失列时必抛、部分 ROM 的 MediaStore 会返回损坏游标）都会让**整个**扫描失败，
     * 连另一张表扫到的数据一起丢掉。改为逐表独立容错：一张失败不影响另一张，
     * 并通过 [QueryResult.anyTableFailed] 告知调用方「本次扫描结果不完整」。
     */
    private fun queryStore(): QueryResult {
        val rows = ArrayList<StoreRow>()
        var anyTableFailed = false
        class Spec(val collection: Uri, val type: String, val projection: Array<String>)
        val specs = listOf(
            Spec(
                MediaStore.Images.Media.EXTERNAL_CONTENT_URI, "IMAGE",
                arrayOf(
                    MediaStore.MediaColumns._ID,
                    MediaStore.MediaColumns.DATE_MODIFIED,
                    MediaStore.MediaColumns.DATE_TAKEN,
                    MediaStore.MediaColumns.SIZE,
                ),
            ),
            Spec(
                MediaStore.Video.Media.EXTERNAL_CONTENT_URI, "VIDEO",
                arrayOf(
                    MediaStore.MediaColumns._ID,
                    MediaStore.MediaColumns.DATE_MODIFIED,
                    MediaStore.MediaColumns.DATE_TAKEN,
                    MediaStore.MediaColumns.SIZE,
                    MediaStore.Video.Media.DURATION,
                ),
            ),
        )
        for (spec in specs) {
            try {
                context.contentResolver.query(
                    spec.collection,
                    spec.projection,
                    null,
                    null,
                    null,
                )?.use { c ->
                    val idCol = c.getColumnIndexOrThrow(MediaStore.MediaColumns._ID)
                    val modifiedCol = c.getColumnIndexOrThrow(MediaStore.MediaColumns.DATE_MODIFIED)
                    val takenCol = c.getColumnIndex(MediaStore.MediaColumns.DATE_TAKEN)
                    val sizeCol = c.getColumnIndex(MediaStore.MediaColumns.SIZE)
                    val durationCol = c.getColumnIndex(MediaStore.Video.Media.DURATION)
                    while (c.moveToNext()) {
                        rows.add(
                            StoreRow(
                                mediaStoreId = c.getLong(idCol),
                                mediaType = spec.type,
                                uri = ContentUris.withAppendedId(spec.collection, c.getLong(idCol)),
                                // MediaStore DATE_MODIFIED 单位是秒；DATE_TAKEN 是毫秒
                                dateModifiedMs = c.getLong(modifiedCol) * 1000,
                                dateTakenMs = if (takenCol >= 0 && !c.isNull(takenCol)) c.getLong(takenCol) else null,
                                durationMs = if (durationCol >= 0 && !c.isNull(durationCol)) c.getLong(durationCol) else null,
                                sizeBytes = if (sizeCol >= 0 && !c.isNull(sizeCol)) c.getLong(sizeCol) else null,
                            ),
                        )
                    }
                } ?: run {
                    // 查询返回 null 也算失败（部分 ROM 在媒体库未就绪时返回 null）
                    anyTableFailed = true
                    Timber.w("MediaStore 查询返回 null type=%s", spec.type)
                }
            } catch (t: Throwable) {
                anyTableFailed = true
                Timber.e(t, "MediaStore 查询失败 type=%s", spec.type)
            }
        }
        return QueryResult(rows, anyTableFailed)
    }

    /** 组装落库实体：GPS 来源优先级（F-MEDIA-02）+ WGS→GCJ 一次转换（F-MEDIA-03） */
    private fun buildEntity(row: StoreRow, hasMediaLocation: Boolean): MediaIndexEntity {
        var latWgs: Double? = null
        var lngWgs: Double? = null

        // 1) MediaStore LATITUDE/LONGITUDE：Android 9 及以下可用（Q+ 常为 null，改走 EXIF）。
        // 常量在 compileSdk 29+ 的 SDK 中已被移除，只能用列名字符串（旧设备的库里列仍在）。
        runCatching {
            context.contentResolver.query(
                row.uri,
                arrayOf("latitude", "longitude"),
                null, null, null,
            )?.use { c ->
                if (c.moveToFirst()) {
                    val la = c.getColumnIndex("latitude")
                    val lo = c.getColumnIndex("longitude")
                    if (la >= 0 && lo >= 0 && !c.isNull(la) && !c.isNull(lo)) {
                        val a = c.getDouble(la)
                        val b = c.getDouble(lo)
                        if (a != 0.0 || b != 0.0) {
                            latWgs = a
                            lngWgs = b
                        }
                    }
                }
            }
        }

        // 2) EXIF GPS（未拿到时）。授权 ACCESS_MEDIA_LOCATION 后 openInputStream 返回原始 EXIF。
        if (latWgs == null) {
            runCatching {
                context.contentResolver.openInputStream(row.uri)?.use { input ->
                    val exif = ExifInterface(input)
                    exif.latLong?.let { (la, lo) ->
                        latWgs = la
                        lngWgs = lo
                    }
                }
            }
        }

        val located = latWgs != null && lngWgs != null
        val isApproximate = located && !hasMediaLocation // 无位置谈不上模糊（F-MEDIA-05）
        val (latGcj, lngGcj) = if (located) {
            val (a, b) = CoordinateConverter.wgs84ToGcj02(latWgs!!, lngWgs!!)
            a to b
        } else {
            null to null
        }

        return MediaIndexEntity(
            mediaStoreId = row.mediaStoreId,
            mediaType = row.mediaType,
            dateModifiedMs = row.dateModifiedMs,
            uri = row.uri.toString(),
            dateTakenMs = row.dateTakenMs,
            latWgs84 = latWgs,
            lngWgs84 = lngWgs,
            latGcj02 = latGcj,
            lngGcj02 = lngGcj,
            isApproximate = isApproximate,
            durationMs = row.durationMs,
            sizeBytes = row.sizeBytes,
            indexedAt = System.currentTimeMillis(),
        )
    }
}
