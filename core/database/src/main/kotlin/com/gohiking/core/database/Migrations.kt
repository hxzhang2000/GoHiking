package com.gohiking.core.database

import androidx.room.migration.Migration

/**
 * 迁移注册表（DEV §3.1）。v1.1 起在此追加，例如：
 * ```
 * object : Migration(1, 2) {
 *     override fun migrate(db: SupportSQLiteDatabase) {
 *         db.execSQL("ALTER TABLE trip ADD COLUMN weather TEXT")
 *     }
 * }
 * ```
 * 每个迁移必须同时配一个 MigrationTestHelper 测试（旧 schema JSON 建库 → 迁移 → 断言数据不丢）。
 *
 * 注：v1.0 所有枚举列都存 String（wire 协议），暂无需要 TypeConverter 的类型；
 * 将来引入转换器时再挂 @TypeConverters（空类挂注解会报「无转换方法」错误）。
 */
val MIGRATIONS: Array<Migration> = arrayOf(
    // v1.1 时在此追加
)
