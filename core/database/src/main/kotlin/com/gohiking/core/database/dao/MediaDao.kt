package com.gohiking.core.database.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import androidx.room.Upsert
import com.gohiking.core.database.entity.MediaFingerprintRow
import com.gohiking.core.database.entity.MediaIndexEntity
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

    @Query("SELECT * FROM media_index WHERE dateTakenMs BETWEEN :from AND :to")
    suspend fun inTimeRange(from: Long, to: Long): List<MediaIndexEntity>

    @Query("SELECT mediaStoreId, dateModifiedMs FROM media_index")
    suspend fun indexFingerprint(): List<MediaFingerprintRow>

    @Query("DELETE FROM media_index")
    suspend fun clearIndex()

    @Query("SELECT COUNT(*) FROM media_index WHERE dateTakenMs BETWEEN :from AND :to")
    fun observeCountInRange(from: Long, to: Long): Flow<Int>
}
