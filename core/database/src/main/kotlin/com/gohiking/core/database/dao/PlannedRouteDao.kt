package com.gohiking.core.database.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import androidx.room.Transaction
import androidx.room.Update
import com.gohiking.core.database.entity.PlannedRouteEntity
import com.gohiking.core.database.entity.PlannedRouteWithLegs
import kotlinx.coroutines.flow.Flow

@Dao
interface PlannedRouteDao {
    @Insert
    suspend fun insert(route: PlannedRouteEntity)

    @Transaction
    @Query("SELECT * FROM planned_route ORDER BY createdAt DESC")
    fun observeAllWithLegs(): Flow<List<PlannedRouteWithLegs>>

    @Transaction
    @Query("SELECT * FROM planned_route WHERE id = :id")
    suspend fun withLegs(id: String): PlannedRouteWithLegs?

    @Update
    suspend fun update(route: PlannedRouteEntity)

    @Query("DELETE FROM planned_route WHERE id = :id")
    suspend fun deleteById(id: String)

    /** 「原路返回」用：把去程折线反向 */
    @Query("SELECT polylineJson FROM planned_leg WHERE id = :legId")
    suspend fun polylineOf(legId: String): String?
}
