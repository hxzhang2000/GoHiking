package com.gohiking.core.database.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import com.gohiking.core.database.entity.TripEntity
import com.gohiking.core.database.entity.TripSummaryRow
import kotlinx.coroutines.flow.Flow

@Dao
interface TripDao {
    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insert(trip: TripEntity)

    @Update
    suspend fun update(trip: TripEntity)

    // 只有 FINISHED 进列表与统计（DEV 决策 13）
    @Query("SELECT * FROM trip WHERE status = 'FINISHED' ORDER BY startTime DESC")
    fun observeFinished(): Flow<List<TripEntity>>

    @Query("SELECT * FROM trip ORDER BY startTime DESC LIMIT :limit OFFSET :offset")
    suspend fun page(limit: Int, offset: Int): List<TripEntity>

    @Query("SELECT * FROM trip WHERE id = :id")
    suspend fun byId(id: String): TripEntity?

    @Query("SELECT * FROM trip WHERE id = :id")
    fun observeById(id: String): Flow<TripEntity?>

    @Query(
        "SELECT COUNT(*) AS c, COALESCE(SUM(distanceM),0) AS d, " +
            "COALESCE(SUM(durationSec),0) AS t, COALESCE(SUM(totalAscentM),0) AS a " +
            "FROM trip WHERE status = 'FINISHED'",
    )
    fun observeSummary(): Flow<TripSummaryRow>

    @Query("DELETE FROM trip WHERE id = :id")
    suspend fun deleteById(id: String)

    @Query("DELETE FROM trip WHERE id IN (:ids)")
    suspend fun deleteByIds(ids: List<String>)

    @Query("SELECT id FROM trip WHERE id IN (:ids)")
    suspend fun existingIds(ids: List<String>): List<String>

    @Query("SELECT id FROM trip WHERE name = :name AND startTime = :startTime")
    suspend fun findSuspectedDuplicate(name: String, startTime: Long): List<String>
}
