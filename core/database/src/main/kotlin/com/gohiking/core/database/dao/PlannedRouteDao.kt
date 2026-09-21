package com.gohiking.core.database.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import androidx.room.Transaction
import androidx.room.Update
import com.gohiking.core.database.entity.PlannedLegEntity
import com.gohiking.core.database.entity.PlannedRouteEntity
import com.gohiking.core.database.entity.PlannedRouteWithLegs
import com.gohiking.core.database.entity.PlannedWaypointEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface PlannedRouteDao {
    @Insert
    suspend fun insert(route: PlannedRouteEntity)

    @Insert
    suspend fun insertLegs(legs: List<PlannedLegEntity>)

    /** 线路与段同事务落库（半截数据无意义：route 无 legs 时管理列表会显示空壳） */
    @Transaction
    suspend fun saveWithLegs(route: PlannedRouteEntity, legs: List<PlannedLegEntity>) {
        insert(route)
        insertLegs(legs)
    }

    @Transaction
    @Query("SELECT * FROM planned_route ORDER BY createdAt DESC")
    fun observeAllWithLegs(): Flow<List<PlannedRouteWithLegs>>

    @Transaction
    @Query("SELECT * FROM planned_route WHERE id = :id")
    suspend fun withLegs(id: String): PlannedRouteWithLegs?

    /** 导出全量备份用（F-IO-03） */
    @Transaction
    @Query("SELECT * FROM planned_route ORDER BY createdAt DESC")
    suspend fun allWithLegs(): List<PlannedRouteWithLegs>

    @Update
    suspend fun update(route: PlannedRouteEntity)

    /** 计划详情编辑保存（P-07→详情）：同事务替换段 + 更新主表（半截数据无意义） */
    @Transaction
    suspend fun replaceLegsAndSave(route: PlannedRouteEntity, legs: List<PlannedLegEntity>) {
        deleteLegsOf(route.id)
        update(route)
        insertLegs(legs)
    }

    @Query("DELETE FROM planned_leg WHERE plannedRouteId = :routeId")
    suspend fun deleteLegsOf(routeId: String)

    @Query("DELETE FROM planned_route WHERE id = :id")
    suspend fun deleteById(id: String)

    /** 「原路返回」用：把去程折线反向 */
    @Query("SELECT polylineJson FROM planned_leg WHERE id = :legId")
    suspend fun polylineOf(legId: String): String?

    /** 导入冲突判定（F-IO-40）用：全量 id */
    @Query("SELECT id FROM planned_route")
    suspend fun allIds(): List<String>

    /** 导入重建途经点（F-IO-21；M2 规划链路暂不写该表，导入端先支持） */
    @Insert
    suspend fun insertWaypoints(items: List<PlannedWaypointEntity>)

    /** 导出计划线路途经点（F-PLAN-43） */
    @Query("SELECT * FROM planned_waypoint WHERE legId IN (:legIds) ORDER BY orderIndex")
    suspend fun waypointsOfLegs(legIds: List<String>): List<PlannedWaypointEntity>
}
