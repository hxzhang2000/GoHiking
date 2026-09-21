package com.gohiking.core.database.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import androidx.room.Upsert
import com.gohiking.core.database.entity.MediaFingerprintRow
import com.gohiking.core.database.entity.MediaIndexEntity
import com.gohiking.core.database.entity.MediaIndexKeyRow
import com.gohiking.core.database.entity.MediaRefEntity
import kotlinx.coroutines.flow.Flow

/** MediaDao 同管 media_ref 与 media_index 两表（DEV §1.3） */
@Dao
interface MediaDao {
    // ---- media_ref ----
    @Insert
    suspend fun insertRef(ref: MediaRefEntity)

    @Query("SELECT * FROM media_ref WHERE tripId = :tripId ORDER BY timestamp")
    suspend fun refsOf(tripId: String): List<MediaRefEntity>

    @Query("DELETE FROM media_ref WHERE tripId = :tripId")
    suspend fun deleteRefsOf(tripId: String)

    // ---- media_index ----
    /** 增量写入：由复合主键 (mediaStoreId, mediaType) 判定插入还是更新（DEV §3.1.1） */
    @Upsert
    suspend fun upsertIndex(items: List<MediaIndexEntity>)

    @Query("SELECT * FROM media_index WHERE latGcj02 IS NOT NULL")
    suspend fun allLocated(): List<MediaIndexEntity>

    /**
     * N-42：原 SQL 是 `WHERE dateTakenMs BETWEEN :from AND :to`。SQL 三值逻辑里
     * `NULL BETWEEN a AND b` 求值为 NULL（不是 false 也不是 true），该行**直接被过滤掉**。
     * 而 MediaStore 的 DATE_TAKEN 对截图、下载图、社交 App 保存的图、部分不写 EXIF 的
     * 相机一律为 NULL —— 这些照片永远进不了候选集，于是 [MediaTripMatcher] 里那句
     * `m.dateTakenMs ?: m.dateModifiedMs` 的兜底成了**永远走不到的死代码**。
     * 这里用 COALESCE 让 SQL 的时间口径与 Matcher 的兜底口径一致（dateModifiedMs 非空）。
     */
    @Query("SELECT * FROM media_index WHERE COALESCE(dateTakenMs, dateModifiedMs) BETWEEN :from AND :to")
    suspend fun inTimeRange(from: Long, to: Long): List<MediaIndexEntity>

    @Query("SELECT mediaStoreId, dateModifiedMs FROM media_index")
    suspend fun indexFingerprint(): List<MediaFingerprintRow>

    /** 全键表（F-MEDIA-09 增量比对 + 删除同步）：复合主键含 type，必须带 type 读全键 */
    @Query("SELECT mediaStoreId, mediaType, dateModifiedMs FROM media_index")
    suspend fun indexKeys(): List<MediaIndexKeyRow>

    /** 删除单条索引（F-MEDIA-09 增量比对发现媒体已删除时同步缓存） */
    @Query("DELETE FROM media_index WHERE mediaStoreId = :mediaStoreId AND mediaType = :mediaType")
    suspend fun deleteIndex(mediaStoreId: Long, mediaType: String)

    @Query("SELECT COUNT(*) FROM media_index")
    suspend fun countIndex(): Int

    @Query("DELETE FROM media_index")
    suspend fun clearIndex()

    @Query("SELECT COUNT(*) FROM media_index WHERE dateTakenMs BETWEEN :from AND :to")
    fun observeCountInRange(from: Long, to: Long): Flow<Int>
}
