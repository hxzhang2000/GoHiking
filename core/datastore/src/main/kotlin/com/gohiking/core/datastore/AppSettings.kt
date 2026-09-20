package com.gohiking.core.datastore

/**
 * 应用设置聚合（DEV §3.5 键表 / PRD 6.7）。
 * 键名常量与字段一一对应；默认值即首次读取值（SettingsRepository_Defaults 测试约定）。
 */
data class AppSettings(
    // 通用
    val language: String = LANGUAGE_SYSTEM, // system / zh-CN / en（F-I18N-11）
    val bodyWeightKg: Int = 65, // F-SET-06：30–200，卡路里估算（§4.12）
    // 提醒
    val alertMasterEnabled: Boolean = true, // F-ALERT-01
    val alertVoiceEnabled: Boolean = true, // F-ALERT-02
    val alertDistanceEnabled: Boolean = true, // F-ALERT-10
    val alertDistanceIntervalM: Int = 100, // F-ALERT-11：50–5000 步进 50
    val alertAltitudeEnabled: Boolean = true, // F-ALERT-20
    val alertAltitudeIntervalM: Int = 100, // F-ALERT-21：10–1000 步进 10；无气压计下限 30（F-REC-63）
    val alertVibrateEnabled: Boolean = true, // PRD 6.7 打点震动反馈
    // 记录
    val recordLocationMode: String = "high", // high / power_saving（PRD 6.3.7）
    val recordAutoPause: Boolean = false, // F-REC-10
    val recordKeepScreenOn: Boolean = true, // F-REC-18
    val recordBackgroundEnabled: Boolean = true, // PRD 6.7 后台记录
    // 单位
    val unitDistance: String = "km", // km / mi
    val unitAltitude: String = "m", // m / ft
    val unitPaceDisplay: String = "pace", // pace / speed
    // 地图
    val mapType: String = "normal", // normal / satellite（F-MAP-04）
    val mapTrackWidth: String = "medium", // thin / medium / thick
    val mapShowMediaCluster: Boolean = true, // PRD 6.7
    // 数据
    val ioExportCrs: String = "GCJ-02", // GCJ-02 / WGS-84（GPX 恒 WGS-84，冻结决策 15）
    val ioExtraFormats: Set<String> = emptySet(), // F-IO-04：GPX
    val ioBackupIncludeMedia: Boolean = false, // F-IO-10
    val ioBackupIncludeChecksum: Boolean = true, // PRD 6.7
    val ioImportConflictPolicy: String = "skip", // skip / overwrite / duplicate / ask（F-IO-42）
    // 内部状态
    val privacyAgreed: Boolean = false, // PRD 9.5
    val barometerHintShown: Boolean = false, // F-REC-62（只弹一次）
    val stepAlgorithmWarnAccepted: Boolean = false, // F-REC-71
    val manualAltitudeCalibrationM: Double? = null, // F-REC-64
) {
    companion object {
        const val LANGUAGE_SYSTEM = "system"
        const val LANGUAGE_ZH = "zh-CN"
        const val LANGUAGE_EN = "en"
        val DISTANCE_INTERVAL_RANGE = 50..5000
        const val DISTANCE_INTERVAL_STEP = 50
        val ALTITUDE_INTERVAL_RANGE = 10..1000
        const val ALTITUDE_INTERVAL_STEP = 10
        const val ALTITUDE_INTERVAL_MIN_NO_BAROMETER = 30 // F-REC-63
        val BODY_WEIGHT_RANGE = 30..200
    }

    /**
     * 阈值合法范围校验（DEV §3.5，PRD F-ALERT-12/22、F-SET-05）：
     * UI 与 DataStore 写入前都过一遍；对齐步进并 clamp。
     */
    fun validate(hasBarometer: Boolean): AppSettings {
        val d = DISTANCE_INTERVAL_STEP
        val distance = ((alertDistanceIntervalM / d) * d).coerceIn(DISTANCE_INTERVAL_RANGE.first, DISTANCE_INTERVAL_RANGE.last)
        val a = ALTITUDE_INTERVAL_STEP
        val altMin = if (hasBarometer) ALTITUDE_INTERVAL_RANGE.first else ALTITUDE_INTERVAL_MIN_NO_BAROMETER
        val altitude = ((alertAltitudeIntervalM / a) * a).coerceIn(altMin, ALTITUDE_INTERVAL_RANGE.last)
        val weight = bodyWeightKg.coerceIn(BODY_WEIGHT_RANGE.first, BODY_WEIGHT_RANGE.last)
        return copy(alertDistanceIntervalM = distance, alertAltitudeIntervalM = altitude, bodyWeightKg = weight)
    }
}

/** Preferences DataStore 键名（DEV §3.5，照写不改名） */
object SettingsKeys {
    const val APP_LANGUAGE = "app_language"
    const val BODY_WEIGHT_KG = "body_weight_kg"
    const val ALERT_MASTER_ENABLED = "alert_master_enabled"
    const val ALERT_VOICE_ENABLED = "alert_voice_enabled"
    const val ALERT_DISTANCE_ENABLED = "alert_distance_enabled"
    const val ALERT_DISTANCE_INTERVAL_M = "alert_distance_interval_m"
    const val ALERT_ALTITUDE_ENABLED = "alert_altitude_enabled"
    const val ALERT_ALTITUDE_INTERVAL_M = "alert_altitude_interval_m"
    const val ALERT_VIBRATE_ENABLED = "alert_vibrate_enabled"
    const val RECORD_LOCATION_MODE = "record_location_mode"
    const val RECORD_AUTO_PAUSE = "record_auto_pause"
    const val RECORD_KEEP_SCREEN_ON = "record_keep_screen_on"
    const val RECORD_BACKGROUND_ENABLED = "record_background_enabled"
    const val UNIT_DISTANCE = "unit_distance"
    const val UNIT_ALTITUDE = "unit_altitude"
    const val UNIT_PACE_DISPLAY = "unit_pace_display"
    const val MAP_TYPE = "map_type"
    const val MAP_TRACK_WIDTH = "map_track_width"
    const val MAP_SHOW_MEDIA_CLUSTER = "map_show_media_cluster"
    const val IO_EXPORT_CRS = "io_export_crs"
    const val IO_EXTRA_FORMATS = "io_extra_formats"
    const val IO_BACKUP_INCLUDE_MEDIA = "io_backup_include_media"
    const val IO_BACKUP_INCLUDE_CHECKSUM = "io_backup_include_checksum"
    const val IO_IMPORT_CONFLICT_POLICY = "io_import_conflict_policy"
    const val PRIVACY_AGREED = "privacy_agreed"
    const val BAROMETER_HINT_SHOWN = "barometer_hint_shown"
    const val STEP_ALGORITHM_WARN_ACCEPTED = "step_algorithm_warn_accepted"
    const val MANUAL_ALTITUDE_CALIBRATION_M = "manual_altitude_calibration_m"
}
