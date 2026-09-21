package com.gohiking.core.database.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import com.gohiking.core.database.entity.MarkerEntity

@Dao
interface MarkerDao {
    @Insert
    suspend fun insert(marker: MarkerEntity)

    @Insert
    suspend fun insertAll(markers: List<MarkerEntity>)

    /**
     * N-30：记录过程中即时落库标记，崩溃/进程被杀后可恢复。
     * 用 REPLACE 而非默认 ABORT：save() 会再写一次同一批标记（主键相同），
     * 默认策略会直接抛 SQLiteConstraintException 让保存失败。
     */
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(marker: MarkerEntity)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertAll(markers: List<MarkerEntity>)

    @Query("SELECT * FROM marker WHERE tripId = :tripId ORDER BY timestamp")
    suspend fun allOf(tripId: String): List<MarkerEntity>

    @Query("SELECT * FROM marker WHERE tripId = :tripId AND type = :type ORDER BY sequence DESC LIMIT 1")
    suspend fun lastOfType(tripId: String, type: String): MarkerEntity?

    @Update
    suspend fun update(marker: MarkerEntity)

    @Query("DELETE FROM marker WHERE id = :id")
    suspend fun deleteById(id: String)

    @Query("DELETE FROM marker WHERE tripId = :tripId")
    suspend fun deleteOf(tripId: String)
}
