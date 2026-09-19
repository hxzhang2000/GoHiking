package com.gohiking.core.database.dao

import androidx.room.Dao
import androidx.room.Query
import androidx.room.Upsert
import com.gohiking.core.database.entity.ElevationCacheEntity
import com.gohiking.core.database.entity.ElevationCacheRow
import com.gohiking.core.database.entity.RecordingStateEntity

@Dao
interface RecordingStateDao {
    @Query("SELECT * FROM recording_state WHERE id = 1")
    suspend fun get(): RecordingStateEntity?

    @Upsert
    suspend fun save(state: RecordingStateEntity)

    @Query("DELETE FROM recording_state")
    suspend fun clear()
}

@Dao
interface ElevationCacheDao {
    // ⚠ IN(latKeys) AND IN(lngKeys) 会返回跨点错配的行——Repository 层必须按 (latKey, lngKey)
    //   精确回配到请求点，并配单测「三点同批查询不串值」（DEV §3.2 硬规则）
    @Query(
        "SELECT latKey, lngKey, altitudeM FROM elevation_cache " +
            "WHERE latKey IN (:latKeys) AND lngKey IN (:lngKeys)",
    )
    suspend fun query(latKeys: List<Double>, lngKeys: List<Double>): List<ElevationCacheRow>

    @Upsert
    suspend fun upsert(items: List<ElevationCacheEntity>)

    @Query("DELETE FROM elevation_cache")
    suspend fun clear()

    @Query("SELECT COUNT(*) FROM elevation_cache")
    suspend fun count(): Int
}
