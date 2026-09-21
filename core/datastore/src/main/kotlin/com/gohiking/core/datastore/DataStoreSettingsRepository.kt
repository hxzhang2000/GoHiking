package com.gohiking.core.datastore

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.doublePreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.core.stringSetPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.runBlocking

/** 进程内单例 DataStore（preferencesDataStore delegate 保证） */
private val Context.settingsStore: DataStore<Preferences> by preferencesDataStore(name = "gohiking_settings")

/** 设置仓储（DEV §3.1.1 ②）。所有设置项 DataStore 持久化（F-SET-01），修改即时生效（F-SET-02）。 */
interface SettingsRepository {
    val settings: Flow<AppSettings>

    suspend fun setLanguage(v: String)
    suspend fun setBodyWeightKg(v: Int)
    suspend fun setAlertMasterEnabled(v: Boolean)
    suspend fun setAlertVoiceEnabled(v: Boolean)
    suspend fun setAlertDistanceEnabled(v: Boolean)
    suspend fun setAlertDistanceIntervalM(v: Int)
    suspend fun setAlertAltitudeEnabled(v: Boolean)
    suspend fun setAlertAltitudeIntervalM(v: Int)
    suspend fun setAlertVibrateEnabled(v: Boolean)
    suspend fun setRecordLocationMode(v: String)
    suspend fun setRecordAutoPause(v: Boolean)
    suspend fun setRecordKeepScreenOn(v: Boolean)
    suspend fun setRecordBackgroundEnabled(v: Boolean)
    suspend fun setUnitDistance(v: String)
    suspend fun setUnitAltitude(v: String)
    suspend fun setUnitPaceDisplay(v: String)
    suspend fun setMapType(v: String)
    suspend fun setMapTrackWidth(v: String)
    suspend fun setMapShowMediaCluster(v: Boolean)
    suspend fun setIoExportCrs(v: String)
    suspend fun setIoExtraFormats(v: Set<String>)
    suspend fun setIoBackupIncludeMedia(v: Boolean)
    suspend fun setIoBackupIncludeChecksum(v: Boolean)
    suspend fun setIoImportConflictPolicy(v: String)
    suspend fun setPrivacyAgreed(v: Boolean)
    suspend fun setBarometerHintShown(v: Boolean)
    suspend fun setStepAlgorithmWarnAccepted(v: Boolean)
    suspend fun setManualAltitudeCalibrationM(v: Double?)
    suspend fun resetToDefault() // F-SET-03
}

/**
 * 设置仓储实现。不用 @Inject：裸 Context 需要 @ApplicationContext 限定符（hilt 注解依赖不在本模块），
 * 由 core:data 的 AppModule @Provides + @ApplicationContext 提供（保持 DI 图单一来源）。
 */
class DataStoreSettingsRepository(private val context: Context) : SettingsRepository {

    private val store get() = context.settingsStore

    private val flow: Flow<AppSettings> = store.data
        .catch { emit(androidx.datastore.preferences.core.emptyPreferences()) }
        .map { p -> p.toSettings() }

    override val settings: Flow<AppSettings> = flow

    private fun Preferences.toSettings(): AppSettings {
        val k = SettingsKeys
        return AppSettings(
            language = this[stringPreferencesKey(k.APP_LANGUAGE)] ?: AppSettings().language,
            bodyWeightKg = this[intPreferencesKey(k.BODY_WEIGHT_KG)] ?: AppSettings().bodyWeightKg,
            alertMasterEnabled = this[booleanPreferencesKey(k.ALERT_MASTER_ENABLED)] ?: true,
            alertVoiceEnabled = this[booleanPreferencesKey(k.ALERT_VOICE_ENABLED)] ?: true,
            alertDistanceEnabled = this[booleanPreferencesKey(k.ALERT_DISTANCE_ENABLED)] ?: true,
            alertDistanceIntervalM = this[intPreferencesKey(k.ALERT_DISTANCE_INTERVAL_M)] ?: 100,
            alertAltitudeEnabled = this[booleanPreferencesKey(k.ALERT_ALTITUDE_ENABLED)] ?: true,
            alertAltitudeIntervalM = this[intPreferencesKey(k.ALERT_ALTITUDE_INTERVAL_M)] ?: 100,
            alertVibrateEnabled = this[booleanPreferencesKey(k.ALERT_VIBRATE_ENABLED)] ?: true,
            recordLocationMode = this[stringPreferencesKey(k.RECORD_LOCATION_MODE)] ?: "high",
            recordAutoPause = this[booleanPreferencesKey(k.RECORD_AUTO_PAUSE)] ?: false,
            recordKeepScreenOn = this[booleanPreferencesKey(k.RECORD_KEEP_SCREEN_ON)] ?: true,
            recordBackgroundEnabled = this[booleanPreferencesKey(k.RECORD_BACKGROUND_ENABLED)] ?: true,
            unitDistance = this[stringPreferencesKey(k.UNIT_DISTANCE)] ?: "km",
            unitAltitude = this[stringPreferencesKey(k.UNIT_ALTITUDE)] ?: "m",
            unitPaceDisplay = this[stringPreferencesKey(k.UNIT_PACE_DISPLAY)] ?: "pace",
            mapType = this[stringPreferencesKey(k.MAP_TYPE)] ?: "normal",
            mapTrackWidth = this[stringPreferencesKey(k.MAP_TRACK_WIDTH)] ?: "medium",
            mapShowMediaCluster = this[booleanPreferencesKey(k.MAP_SHOW_MEDIA_CLUSTER)] ?: true,
            ioExportCrs = this[stringPreferencesKey(k.IO_EXPORT_CRS)] ?: "GCJ-02",
            ioExtraFormats = this[stringSetPreferencesKey(k.IO_EXTRA_FORMATS)] ?: emptySet(),
            ioBackupIncludeMedia = this[booleanPreferencesKey(k.IO_BACKUP_INCLUDE_MEDIA)] ?: false,
            ioBackupIncludeChecksum = this[booleanPreferencesKey(k.IO_BACKUP_INCLUDE_CHECKSUM)] ?: true,
            ioImportConflictPolicy = this[stringPreferencesKey(k.IO_IMPORT_CONFLICT_POLICY)] ?: "skip",
            privacyAgreed = this[booleanPreferencesKey(k.PRIVACY_AGREED)] ?: false,
            barometerHintShown = this[booleanPreferencesKey(k.BAROMETER_HINT_SHOWN)] ?: false,
            stepAlgorithmWarnAccepted = this[booleanPreferencesKey(k.STEP_ALGORITHM_WARN_ACCEPTED)] ?: false,
            manualAltitudeCalibrationM = this[doublePreferencesKey(k.MANUAL_ALTITUDE_CALIBRATION_M)],
        )
    }

    private suspend fun edit(transform: (androidx.datastore.preferences.core.MutablePreferences) -> Unit) {
        store.edit(transform)
    }

    override suspend fun setLanguage(v: String) = edit { it[stringPreferencesKey(SettingsKeys.APP_LANGUAGE)] = v }
    override suspend fun setBodyWeightKg(v: Int) = edit { it[intPreferencesKey(SettingsKeys.BODY_WEIGHT_KG)] = v }
    override suspend fun setAlertMasterEnabled(v: Boolean) =
        edit { it[booleanPreferencesKey(SettingsKeys.ALERT_MASTER_ENABLED)] = v }

    override suspend fun setAlertVoiceEnabled(v: Boolean) =
        edit { it[booleanPreferencesKey(SettingsKeys.ALERT_VOICE_ENABLED)] = v }

    override suspend fun setAlertDistanceEnabled(v: Boolean) =
        edit { it[booleanPreferencesKey(SettingsKeys.ALERT_DISTANCE_ENABLED)] = v }

    override suspend fun setAlertDistanceIntervalM(v: Int) =
        edit { it[intPreferencesKey(SettingsKeys.ALERT_DISTANCE_INTERVAL_M)] = v }

    override suspend fun setAlertAltitudeEnabled(v: Boolean) =
        edit { it[booleanPreferencesKey(SettingsKeys.ALERT_ALTITUDE_ENABLED)] = v }

    override suspend fun setAlertAltitudeIntervalM(v: Int) =
        edit { it[intPreferencesKey(SettingsKeys.ALERT_ALTITUDE_INTERVAL_M)] = v }

    override suspend fun setAlertVibrateEnabled(v: Boolean) =
        edit { it[booleanPreferencesKey(SettingsKeys.ALERT_VIBRATE_ENABLED)] = v }

    override suspend fun setRecordLocationMode(v: String) =
        edit { it[stringPreferencesKey(SettingsKeys.RECORD_LOCATION_MODE)] = v }

    override suspend fun setRecordAutoPause(v: Boolean) =
        edit { it[booleanPreferencesKey(SettingsKeys.RECORD_AUTO_PAUSE)] = v }

    override suspend fun setRecordKeepScreenOn(v: Boolean) =
        edit { it[booleanPreferencesKey(SettingsKeys.RECORD_KEEP_SCREEN_ON)] = v }

    override suspend fun setRecordBackgroundEnabled(v: Boolean) =
        edit { it[booleanPreferencesKey(SettingsKeys.RECORD_BACKGROUND_ENABLED)] = v }

    override suspend fun setUnitDistance(v: String) =
        edit { it[stringPreferencesKey(SettingsKeys.UNIT_DISTANCE)] = v }

    override suspend fun setUnitAltitude(v: String) =
        edit { it[stringPreferencesKey(SettingsKeys.UNIT_ALTITUDE)] = v }

    override suspend fun setUnitPaceDisplay(v: String) =
        edit { it[stringPreferencesKey(SettingsKeys.UNIT_PACE_DISPLAY)] = v }

    override suspend fun setMapType(v: String) = edit { it[stringPreferencesKey(SettingsKeys.MAP_TYPE)] = v }
    override suspend fun setMapTrackWidth(v: String) =
        edit { it[stringPreferencesKey(SettingsKeys.MAP_TRACK_WIDTH)] = v }

    override suspend fun setMapShowMediaCluster(v: Boolean) =
        edit { it[booleanPreferencesKey(SettingsKeys.MAP_SHOW_MEDIA_CLUSTER)] = v }

    override suspend fun setIoExportCrs(v: String) =
        edit { it[stringPreferencesKey(SettingsKeys.IO_EXPORT_CRS)] = v }

    override suspend fun setIoExtraFormats(v: Set<String>) =
        edit { it[stringSetPreferencesKey(SettingsKeys.IO_EXTRA_FORMATS)] = v }

    override suspend fun setIoBackupIncludeMedia(v: Boolean) =
        edit { it[booleanPreferencesKey(SettingsKeys.IO_BACKUP_INCLUDE_MEDIA)] = v }

    override suspend fun setIoBackupIncludeChecksum(v: Boolean) =
        edit { it[booleanPreferencesKey(SettingsKeys.IO_BACKUP_INCLUDE_CHECKSUM)] = v }

    override suspend fun setIoImportConflictPolicy(v: String) =
        edit { it[stringPreferencesKey(SettingsKeys.IO_IMPORT_CONFLICT_POLICY)] = v }

    override suspend fun setPrivacyAgreed(v: Boolean) =
        edit { it[booleanPreferencesKey(SettingsKeys.PRIVACY_AGREED)] = v }

    override suspend fun setBarometerHintShown(v: Boolean) =
        edit { it[booleanPreferencesKey(SettingsKeys.BAROMETER_HINT_SHOWN)] = v }

    override suspend fun setStepAlgorithmWarnAccepted(v: Boolean) =
        edit { it[booleanPreferencesKey(SettingsKeys.STEP_ALGORITHM_WARN_ACCEPTED)] = v }

    override suspend fun setManualAltitudeCalibrationM(v: Double?) = edit { prefs ->
        val key = doublePreferencesKey(SettingsKeys.MANUAL_ALTITUDE_CALIBRATION_M)
        if (v == null) prefs.remove(key) else prefs[key] = v
    }

    /**
     * F-SET-03 恢复默认：清空全部键（首次读取语义即默认值）。
     *
     * L-12：但 `it.clear()` 会连 `privacy_agreed` 一起抹掉 —— 那是「本设备已同意隐私政策」
     * 的记录，属于设备态/合规证据，不是「偏好」。恢复默认后用户会重新撞上隐私门，
     * 而合规上更糟的是：同意记录凭空消失，无法自证此前取得过同意。
     * 与 SettingsBackupMapper.EXCLUDED_KEYS（备份导入不覆盖语言与同意态）保持同一口径。
     */
    override suspend fun resetToDefault() {
        // 先取出要保留的项（typed 读取，避免 Preferences.Key<*> 的泛型擦除问题）
        val snapshot = store.data.first()
        val language = snapshot[stringPreferencesKey(SettingsKeys.APP_LANGUAGE)]
        val privacyAgreed = snapshot[booleanPreferencesKey(SettingsKeys.PRIVACY_AGREED)]
        store.edit { prefs ->
            prefs.clear()
            if (language != null) prefs[stringPreferencesKey(SettingsKeys.APP_LANGUAGE)] = language
            if (privacyAgreed != null) {
                prefs[booleanPreferencesKey(SettingsKeys.PRIVACY_AGREED)] = privacyAgreed
            }
        }
    }
}

/**
 * 同步读语言偏好（F-I18N-11）：仅 MainActivity.attachBaseContext 用——
 * Activity 重建时基 Context 必须在进入 Compose 前带上语言配置，而 DataStore 只有挂起 API，
 * attachBaseContext 无协程作用域，故 runBlocking 一次（单键读取，首次启动 <10ms，可接受）。
 */
fun readLanguageBlocking(context: Context): String = runBlocking {
    try {
        context.settingsStore.data.first()[stringPreferencesKey(SettingsKeys.APP_LANGUAGE)]
            ?: AppSettings.LANGUAGE_SYSTEM
    } catch (t: Throwable) {
        AppSettings.LANGUAGE_SYSTEM
    }
}
