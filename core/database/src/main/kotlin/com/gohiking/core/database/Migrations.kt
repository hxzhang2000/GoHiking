package com.gohiking.core.database

import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

/**
 * 迁移注册表（DEV §3.1）。每个迁移必须同时配一个 MigrationTestHelper 测试
 *（旧 schema JSON 建库 → 迁移 → 断言数据不丢）。
 *
 * 注：minSdk 26 自带 SQLite 3.19，**不支持** ALTER TABLE ... RENAME COLUMN，
 * 因此改列的可空性必须走「建新表 → 抄数据 → 删旧表 → 改名」四步。
 */

/**
 * H-06：planned_route.totalAscent/totalDescent 与 planned_leg.ascent/descent/difficulty
 * 改为可空 —— 高程不可用或难度未知时必须存 null 而不是 0 / "MODERATE"（PRD「绝不编造」）。
 */
val MIGRATION_1_2 = object : Migration(1, 2) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL(
            "CREATE TABLE IF NOT EXISTS `planned_route_new` (" +
                "`id` TEXT NOT NULL, `name` TEXT NOT NULL, `note` TEXT, `source` TEXT NOT NULL, " +
                "`createdAt` INTEGER NOT NULL, `totalDistanceM` REAL NOT NULL, " +
                "`totalAscentM` REAL, `totalDescentM` REAL, PRIMARY KEY(`id`))",
        )
        db.execSQL(
            "INSERT INTO `planned_route_new` " +
                "(`id`,`name`,`note`,`source`,`createdAt`,`totalDistanceM`,`totalAscentM`,`totalDescentM`) " +
                "SELECT `id`,`name`,`note`,`source`,`createdAt`,`totalDistanceM`,`totalAscentM`,`totalDescentM` " +
                "FROM `planned_route`",
        )
        db.execSQL("DROP TABLE `planned_route`")
        db.execSQL("ALTER TABLE `planned_route_new` RENAME TO `planned_route`")

        db.execSQL(
            "CREATE TABLE IF NOT EXISTS `planned_leg_new` (" +
                "`id` TEXT NOT NULL, `plannedRouteId` TEXT NOT NULL, `legType` TEXT NOT NULL, " +
                "`distanceM` REAL NOT NULL, `ascentM` REAL, `descentM` REAL, `estimatedMin` INTEGER, " +
                "`difficulty` TEXT, `polylineJson` TEXT NOT NULL, PRIMARY KEY(`id`), " +
                "FOREIGN KEY(`plannedRouteId`) REFERENCES `planned_route`(`id`) " +
                "ON UPDATE NO ACTION ON DELETE CASCADE)",
        )
        db.execSQL(
            "INSERT INTO `planned_leg_new` " +
                "(`id`,`plannedRouteId`,`legType`,`distanceM`,`ascentM`,`descentM`,`estimatedMin`,`difficulty`,`polylineJson`) " +
                "SELECT `id`,`plannedRouteId`,`legType`,`distanceM`,`ascentM`,`descentM`,`estimatedMin`,`difficulty`,`polylineJson` " +
                "FROM `planned_leg`",
        )
        db.execSQL("DROP TABLE `planned_leg`")
        db.execSQL("ALTER TABLE `planned_leg_new` RENAME TO `planned_leg`")
        db.execSQL("CREATE INDEX IF NOT EXISTS `index_planned_leg_plannedRouteId` ON `planned_leg` (`plannedRouteId`)")
    }
}

val MIGRATIONS: Array<Migration> = arrayOf(
    MIGRATION_1_2,
)
