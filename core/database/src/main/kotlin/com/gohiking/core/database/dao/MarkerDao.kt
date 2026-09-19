package com.gohiking.core.database.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import androidx.room.Update
import com.gohiking.core.database.entity.MarkerEntity

@Dao
interface MarkerDao {
    @Insert
    suspend fun insert(marker: MarkerEntity)

    @Insert
    suspend fun insertAll(markers: List<MarkerEntity>)

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
