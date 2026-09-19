package com.gohiking.core.database.entity

import androidx.room.ColumnInfo
import androidx.room.Embedded
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey
import androidx.room.Relation

/** 计划线路主表（PRD 7.1）。[source]：AUTO / MANUAL / MIXED */
@Entity(tableName = "planned_route")
data class PlannedRouteEntity(
    @PrimaryKey val id: String, // UUID
    val name: String,
    val note: String?,
    val source: String,
    val createdAt: Long,
    val totalDistanceM: Double,
    val totalAscentM: Double,
    val totalDescentM: Double,
)

/** 计划线路的往返两段（PRD 7.1）。[legType]：OUTBOUND / RETURN；[difficulty]：EASY/MODERATE/HARD/CHALLENGING */
@Entity(
    tableName = "planned_leg",
    foreignKeys = [
        ForeignKey(
            entity = PlannedRouteEntity::class,
            parentColumns = ["id"],
            childColumns = ["plannedRouteId"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [Index(value = ["plannedRouteId"])],
)
data class PlannedLegEntity(
    @PrimaryKey val id: String, // UUID
    val plannedRouteId: String,
    val legType: String,
    val distanceM: Double,
    val ascentM: Double,
    val descentM: Double,
    val estimatedMin: Int?,
    val difficulty: String,
    val polylineJson: String, // 抽稀后的坐标点数组 JSON
)

/**
 * 计划线路途经点（PRD 7.1）。
 * PRD 列名 `order` 是 SQLite 关键字，此处落库为 `orderIndex`（DEV §9.3 D-14）。
 * [source]：AUTO / MANUAL
 */
@Entity(
    tableName = "planned_waypoint",
    foreignKeys = [
        ForeignKey(
            entity = PlannedLegEntity::class,
            parentColumns = ["id"],
            childColumns = ["legId"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [Index(value = ["legId"])],
)
data class PlannedWaypointEntity(
    @PrimaryKey val id: String, // UUID
    val legId: String,
    @ColumnInfo(name = "orderIndex") val orderIndex: Int,
    val latitude: Double,
    val longitude: Double,
    val name: String?,
    val source: String,
)

/** 路线 + 段的联查结果（PlannedRouteDao @Transaction 查询用） */
data class PlannedRouteWithLegs(
    @Embedded val route: PlannedRouteEntity,
    @Relation(parentColumn = "id", entityColumn = "plannedRouteId")
    val legs: List<PlannedLegEntity>,
)
