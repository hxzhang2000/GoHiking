package com.gohiking.core.data.io

import com.gohiking.core.datastore.AppSettings
import com.gohiking.core.datastore.SettingsKeys
import com.gohiking.core.datastore.SettingsRepository
import kotlinx.coroutines.flow.first
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put

/**
 * settings.json 的序列化 / diff / 应用（F-IO-36/37，PRD 7.4）。
 * - 备份照写全量键；导入 diff 只列与当前值不同的项；
 * - 语言 app_language 始终不导入（F-IO-37）；privacy_agreed 同属设备态一并排除；
 * - 阈值类写回前过 AppSettings.validate(hasBarometer)（F-ALERT-12/22）。
 */
object SettingsBackupMapper {

    /** F-IO-37：永不导入的键 */
    val EXCLUDED_KEYS = setOf(SettingsKeys.APP_LANGUAGE, SettingsKeys.PRIVACY_AGREED)

    fun toJson(s: AppSettings): JsonObject = buildJsonObject {
        val k = SettingsKeys
        put(k.APP_LANGUAGE, s.language)
        put(k.BODY_WEIGHT_KG, s.bodyWeightKg)
        put(k.ALERT_MASTER_ENABLED, s.alertMasterEnabled)
        put(k.ALERT_VOICE_ENABLED, s.alertVoiceEnabled)
        put(k.ALERT_DISTANCE_ENABLED, s.alertDistanceEnabled)
        put(k.ALERT_DISTANCE_INTERVAL_M, s.alertDistanceIntervalM)
        put(k.ALERT_ALTITUDE_ENABLED, s.alertAltitudeEnabled)
        put(k.ALERT_ALTITUDE_INTERVAL_M, s.alertAltitudeIntervalM)
        put(k.ALERT_VIBRATE_ENABLED, s.alertVibrateEnabled)
        put(k.RECORD_LOCATION_MODE, s.recordLocationMode)
        put(k.RECORD_AUTO_PAUSE, s.recordAutoPause)
        put(k.RECORD_KEEP_SCREEN_ON, s.recordKeepScreenOn)
        put(k.RECORD_BACKGROUND_ENABLED, s.recordBackgroundEnabled)
        put(k.UNIT_DISTANCE, s.unitDistance)
        put(k.UNIT_ALTITUDE, s.unitAltitude)
        put(k.UNIT_PACE_DISPLAY, s.unitPaceDisplay)
        put(k.MAP_TYPE, s.mapType)
        put(k.MAP_TRACK_WIDTH, s.mapTrackWidth)
        put(k.MAP_SHOW_MEDIA_CLUSTER, s.mapShowMediaCluster)
        put(k.IO_EXPORT_CRS, s.ioExportCrs)
        put(k.IO_EXTRA_FORMATS, JsonArray(s.ioExtraFormats.map { JsonPrimitive(it) }))
        put(k.IO_BACKUP_INCLUDE_MEDIA, s.ioBackupIncludeMedia)
        put(k.IO_BACKUP_INCLUDE_CHECKSUM, s.ioBackupIncludeChecksum)
        put(k.IO_IMPORT_CONFLICT_POLICY, s.ioImportConflictPolicy)
        put(k.BAROMETER_HINT_SHOWN, s.barometerHintShown)
        put(k.STEP_ALGORITHM_WARN_ACCEPTED, s.stepAlgorithmWarnAccepted)
        when (val calibration = s.manualAltitudeCalibrationM) {
            null -> put(k.MANUAL_ALTITUDE_CALIBRATION_M, JsonNull)
            else -> put(k.MANUAL_ALTITUDE_CALIBRATION_M, calibration)
        }
    }

    data class Change(val key: String, val currentValue: String, val backupValue: String)

    /** 与当前设置的差异（只列将改变的项，F-IO-37；排除 EXCLUDED_KEYS） */
    fun diff(current: AppSettings, backup: JsonObject): List<Change> = backup
        .filterKeys { it !in EXCLUDED_KEYS }
        .mapNotNull { (key, value) ->
            val cur = displayOf(current, key) ?: return@mapNotNull null // 未知键忽略（F-IO-50）
            val bak = displayOf(value)
            if (cur != bak) Change(key, cur, bak) else null
        }

    /** 应用差异（先经用户二次确认）；阈值写回后过 validate 夹回合法域（F-ALERT-12/22） */
    suspend fun apply(repository: SettingsRepository, changes: List<Change>, hasBarometer: Boolean) {
        changes.forEach { change ->
            when (change.key) {
                SettingsKeys.BODY_WEIGHT_KG -> repository.setBodyWeightKg(change.backupValue.toIntOrNull() ?: return@forEach)
                SettingsKeys.ALERT_MASTER_ENABLED -> repository.setAlertMasterEnabled(change.backupValue.toBooleanStrictOrNull() ?: return@forEach)
                SettingsKeys.ALERT_VOICE_ENABLED -> repository.setAlertVoiceEnabled(change.backupValue.toBooleanStrictOrNull() ?: return@forEach)
                SettingsKeys.ALERT_DISTANCE_ENABLED -> repository.setAlertDistanceEnabled(change.backupValue.toBooleanStrictOrNull() ?: return@forEach)
                SettingsKeys.ALERT_DISTANCE_INTERVAL_M -> change.backupValue.toIntOrNull()?.let { repository.setAlertDistanceIntervalM(it) }
                SettingsKeys.ALERT_ALTITUDE_ENABLED -> repository.setAlertAltitudeEnabled(change.backupValue.toBooleanStrictOrNull() ?: return@forEach)
                SettingsKeys.ALERT_ALTITUDE_INTERVAL_M -> change.backupValue.toIntOrNull()?.let { repository.setAlertAltitudeIntervalM(it) }
                SettingsKeys.ALERT_VIBRATE_ENABLED -> repository.setAlertVibrateEnabled(change.backupValue.toBooleanStrictOrNull() ?: return@forEach)
                SettingsKeys.RECORD_LOCATION_MODE -> repository.setRecordLocationMode(change.backupValue)
                SettingsKeys.RECORD_AUTO_PAUSE -> repository.setRecordAutoPause(change.backupValue.toBooleanStrictOrNull() ?: return@forEach)
                SettingsKeys.RECORD_KEEP_SCREEN_ON -> repository.setRecordKeepScreenOn(change.backupValue.toBooleanStrictOrNull() ?: return@forEach)
                SettingsKeys.RECORD_BACKGROUND_ENABLED -> repository.setRecordBackgroundEnabled(change.backupValue.toBooleanStrictOrNull() ?: return@forEach)
                SettingsKeys.UNIT_DISTANCE -> repository.setUnitDistance(change.backupValue)
                SettingsKeys.UNIT_ALTITUDE -> repository.setUnitAltitude(change.backupValue)
                SettingsKeys.UNIT_PACE_DISPLAY -> repository.setUnitPaceDisplay(change.backupValue)
                SettingsKeys.MAP_TYPE -> repository.setMapType(change.backupValue)
                SettingsKeys.MAP_TRACK_WIDTH -> repository.setMapTrackWidth(change.backupValue)
                SettingsKeys.MAP_SHOW_MEDIA_CLUSTER -> repository.setMapShowMediaCluster(change.backupValue.toBooleanStrictOrNull() ?: return@forEach)
                SettingsKeys.IO_EXPORT_CRS -> repository.setIoExportCrs(change.backupValue)
                SettingsKeys.IO_EXTRA_FORMATS -> repository.setIoExtraFormats(change.backupValue.split(',').filter { it.isNotBlank() }.toSet())
                SettingsKeys.IO_BACKUP_INCLUDE_MEDIA -> repository.setIoBackupIncludeMedia(change.backupValue.toBooleanStrictOrNull() ?: return@forEach)
                SettingsKeys.IO_BACKUP_INCLUDE_CHECKSUM -> repository.setIoBackupIncludeChecksum(change.backupValue.toBooleanStrictOrNull() ?: return@forEach)
                SettingsKeys.IO_IMPORT_CONFLICT_POLICY -> repository.setIoImportConflictPolicy(change.backupValue)
                SettingsKeys.BAROMETER_HINT_SHOWN -> repository.setBarometerHintShown(change.backupValue.toBooleanStrictOrNull() ?: return@forEach)
                SettingsKeys.STEP_ALGORITHM_WARN_ACCEPTED -> repository.setStepAlgorithmWarnAccepted(change.backupValue.toBooleanStrictOrNull() ?: return@forEach)
                SettingsKeys.MANUAL_ALTITUDE_CALIBRATION_M -> repository.setManualAltitudeCalibrationM(change.backupValue.toDoubleOrNull())
            }
        }
        // 数值夹回合法域（无气压计下限 30 等，F-REC-63）
        val validated = repository.settings.first().validate(hasBarometer)
        repository.setAlertDistanceIntervalM(validated.alertDistanceIntervalM)
        repository.setAlertAltitudeIntervalM(validated.alertAltitudeIntervalM)
        repository.setBodyWeightKg(validated.bodyWeightKg)
    }

    private fun displayOf(s: AppSettings, key: String): String? = when (key) {
        SettingsKeys.APP_LANGUAGE -> s.language
        SettingsKeys.BODY_WEIGHT_KG -> s.bodyWeightKg.toString()
        SettingsKeys.ALERT_MASTER_ENABLED -> s.alertMasterEnabled.toString()
        SettingsKeys.ALERT_VOICE_ENABLED -> s.alertVoiceEnabled.toString()
        SettingsKeys.ALERT_DISTANCE_ENABLED -> s.alertDistanceEnabled.toString()
        SettingsKeys.ALERT_DISTANCE_INTERVAL_M -> s.alertDistanceIntervalM.toString()
        SettingsKeys.ALERT_ALTITUDE_ENABLED -> s.alertAltitudeEnabled.toString()
        SettingsKeys.ALERT_ALTITUDE_INTERVAL_M -> s.alertAltitudeIntervalM.toString()
        SettingsKeys.ALERT_VIBRATE_ENABLED -> s.alertVibrateEnabled.toString()
        SettingsKeys.RECORD_LOCATION_MODE -> s.recordLocationMode
        SettingsKeys.RECORD_AUTO_PAUSE -> s.recordAutoPause.toString()
        SettingsKeys.RECORD_KEEP_SCREEN_ON -> s.recordKeepScreenOn.toString()
        SettingsKeys.RECORD_BACKGROUND_ENABLED -> s.recordBackgroundEnabled.toString()
        SettingsKeys.UNIT_DISTANCE -> s.unitDistance
        SettingsKeys.UNIT_ALTITUDE -> s.unitAltitude
        SettingsKeys.UNIT_PACE_DISPLAY -> s.unitPaceDisplay
        SettingsKeys.MAP_TYPE -> s.mapType
        SettingsKeys.MAP_TRACK_WIDTH -> s.mapTrackWidth
        SettingsKeys.MAP_SHOW_MEDIA_CLUSTER -> s.mapShowMediaCluster.toString()
        SettingsKeys.IO_EXPORT_CRS -> s.ioExportCrs
        SettingsKeys.IO_EXTRA_FORMATS -> s.ioExtraFormats.sorted().joinToString(",")
        SettingsKeys.IO_BACKUP_INCLUDE_MEDIA -> s.ioBackupIncludeMedia.toString()
        SettingsKeys.IO_BACKUP_INCLUDE_CHECKSUM -> s.ioBackupIncludeChecksum.toString()
        SettingsKeys.IO_IMPORT_CONFLICT_POLICY -> s.ioImportConflictPolicy
        SettingsKeys.BAROMETER_HINT_SHOWN -> s.barometerHintShown.toString()
        SettingsKeys.STEP_ALGORITHM_WARN_ACCEPTED -> s.stepAlgorithmWarnAccepted.toString()
        SettingsKeys.MANUAL_ALTITUDE_CALIBRATION_M -> s.manualAltitudeCalibrationM?.toString() ?: "null"
        else -> null // 未知键 → 忽略（F-IO-50 向后兼容）
    }

    private fun displayOf(value: kotlinx.serialization.json.JsonElement): String = when {
        value is JsonNull -> "null"
        value is JsonArray -> value.jsonArray.joinToString(",") { it.jsonPrimitive.content }
        else -> (value as? JsonPrimitive)?.content ?: value.toString()
    }
}
