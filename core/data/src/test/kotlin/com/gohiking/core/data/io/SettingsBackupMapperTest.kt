package com.gohiking.core.data.io

import com.gohiking.core.datastore.AppSettings
import com.gohiking.core.datastore.SettingsKeys
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** settings.json 备份映射（F-IO-36/37） */
class SettingsBackupMapperTest {

    @Test
    fun `language and privacy keys are never exported for import diff`() {
        val backup = SettingsBackupMapper.toJson(AppSettings(language = "en"))
        // 备份文件可以带语言键（照写全量），但 diff 必须排除
        assertTrue(backup.containsKey(SettingsKeys.APP_LANGUAGE))
        val changes = SettingsBackupMapper.diff(
            AppSettings(language = "zh-CN"),
            backup,
        )
        assertTrue(changes.none { it.key == SettingsKeys.APP_LANGUAGE })
        assertTrue(changes.none { it.key == SettingsKeys.PRIVACY_AGREED })
    }

    @Test
    fun `diff lists only differing items`() {
        val backup = SettingsBackupMapper.toJson(
            AppSettings(alertDistanceIntervalM = 200, alertMasterEnabled = false),
        )
        val changes = SettingsBackupMapper.diff(AppSettings(), backup)
        val keys = changes.map { it.key }
        assertTrue(SettingsKeys.ALERT_DISTANCE_INTERVAL_M in keys)
        assertTrue(SettingsKeys.ALERT_MASTER_ENABLED in keys)
        assertTrue(SettingsKeys.BODY_WEIGHT_KG !in keys) // 默认值相同
        assertEquals("200", changes.first { it.key == SettingsKeys.ALERT_DISTANCE_INTERVAL_M }.backupValue)
    }

    @Test
    fun `unknown keys are ignored for forward compatibility`() {
        val backup = SettingsBackupMapper.toJson(AppSettings()).toMutableMap()
        backup["brand_new_key_2049"] = JsonPrimitive("x")
        val changes = SettingsBackupMapper.diff(AppSettings(), kotlinx.serialization.json.JsonObject(backup))
        assertTrue(changes.none { it.key == "brand_new_key_2049" })
    }

    @Test
    fun `roundtrip json preserves representative values`() {
        val s = AppSettings(
            alertDistanceIntervalM = 300,
            ioExtraFormats = setOf("GPX"),
            unitDistance = "mi",
        )
        val json = SettingsBackupMapper.toJson(s)
        val changes = SettingsBackupMapper.diff(AppSettings(), json)
        assertEquals(
            setOf("alert_distance_interval_m", "io_extra_formats", "unit_distance"),
            changes.map { it.key }.toSet(),
        )
        assertEquals("GPX", changes.first { it.key == SettingsKeys.IO_EXTRA_FORMATS }.backupValue)
    }

    @Test
    fun `toJson is valid json object`() {
        val obj = SettingsBackupMapper.toJson(AppSettings())
        assertEquals(obj, obj.jsonObject) // parseable shape sanity
        assertTrue(obj.containsKey(SettingsKeys.ALERT_ALTITUDE_INTERVAL_M))
    }
}
