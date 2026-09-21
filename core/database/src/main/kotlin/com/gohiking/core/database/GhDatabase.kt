package com.gohiking.core.database

import androidx.room.Database
import androidx.room.RoomDatabase
import com.gohiking.core.database.dao.ElevationCacheDao
import com.gohiking.core.database.dao.MarkerDao
import com.gohiking.core.database.dao.MediaDao
import com.gohiking.core.database.dao.PlannedRouteDao
import com.gohiking.core.database.dao.RecordingStateDao
import com.gohiking.core.database.dao.TrackPointDao
import com.gohiking.core.database.dao.TripDao
import com.gohiking.core.database.entity.ElevationCacheEntity
import com.gohiking.core.database.entity.MarkerEntity
import com.gohiking.core.database.entity.MediaIndexEntity
import com.gohiking.core.database.entity.MediaRefEntity
import com.gohiking.core.database.entity.PlannedLegEntity
import com.gohiking.core.database.entity.PlannedRouteEntity
import com.gohiking.core.database.entity.PlannedWaypointEntity
import com.gohiking.core.database.entity.RecordingStateEntity
import com.gohiking.core.database.entity.TrackPointEntity
import com.gohiking.core.database.entity.TripEntity

/**
 * GoHiking 本地库（PRD 7.1 + DEV §3.1.1 共 10 表）。
 * 版本 2（v2 见 MIGRATION_1_2：planned_route/planned_leg 的高程与难度列改为可空，H-06）；
 * 禁止 fallbackToDestructiveMigration（PRD 9.5「数据属于用户」），
 * 每加表/字段 +1 版本并写 Migration（Migrations.kt）+ MigrationTestHelper 测试。
 */
@Database(
    entities = [
        TripEntity::class,
        TrackPointEntity::class,
        MarkerEntity::class,
        PlannedRouteEntity::class,
        PlannedLegEntity::class,
        PlannedWaypointEntity::class,
        MediaRefEntity::class,
        MediaIndexEntity::class,
        RecordingStateEntity::class,
        ElevationCacheEntity::class,
    ],
    version = 2,
    exportSchema = true,
)
abstract class GhDatabase : RoomDatabase() {
    abstract fun tripDao(): TripDao
    abstract fun trackPointDao(): TrackPointDao
    abstract fun markerDao(): MarkerDao
    abstract fun plannedRouteDao(): PlannedRouteDao
    abstract fun mediaDao(): MediaDao
    abstract fun recordingStateDao(): RecordingStateDao
    abstract fun elevationCacheDao(): ElevationCacheDao

    companion object {
        const val NAME = "gohiking.db"
    }
}
