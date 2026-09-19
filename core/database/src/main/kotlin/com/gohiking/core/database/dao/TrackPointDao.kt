package com.gohiking.core.database.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import androidx.room.Transaction
import com.gohiking.core.database.entity.ChartPointRow
import com.gohiking.core.database.entity.TrackPointEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface TrackPointDao {
    /** 批量插入：每批 10 点 / 每 5 秒一次事务（DEV §3.4），调用方必须包在事务里 */
    @Insert
    suspend fun insertAll(points: List<TrackPointEntity>)

    @Transaction
    suspend fun insertBatch(points: List<TrackPointEntity>) = insertAll(points)

    @Query("SELECT * FROM track_point WHERE tripId = :tripId ORDER BY segmentIndex, seq")
    suspend fun allOf(tripId: String): List<TrackPointEntity>

    @Query("SELECT * FROM track_point WHERE tripId = :tripId ORDER BY segmentIndex, seq")
    fun observeOf(tripId: String): Flow<List<TrackPointEntity>>

    /** 曲线图投影：抽稀交给渲染层（DEV 决策 11），数据库永远存原始点 */
    @Query(
        "SELECT timestamp, altitude, distanceM FROM track_point " +
            "WHERE tripId = :tripId AND quality = 0 ORDER BY segmentIndex, seq",
    )
    suspend fun chartPoints(tripId: String): List<ChartPointRow>

    @Query("SELECT COUNT(*) FROM track_point WHERE tripId = :tripId")
    suspend fun countOf(tripId: String): Int

    @Query("DELETE FROM track_point WHERE tripId = :tripId")
    suspend fun deleteOf(tripId: String)
}
