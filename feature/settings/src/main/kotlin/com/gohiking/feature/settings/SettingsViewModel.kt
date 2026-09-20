package com.gohiking.feature.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.gohiking.core.datastore.AppSettings
import com.gohiking.core.datastore.SettingsRepository
import com.gohiking.core.resources.R
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/** 单条设置项 UI 模型（DEV §5.2 P-14） */
sealed interface SettingItemUi {
    abstract val key: String
    abstract val titleRes: Int

    data class Switch(
        override val key: String,
        override val titleRes: Int,
        val checked: Boolean,
    ) : SettingItemUi

    data class Select(
        override val key: String,
        override val titleRes: Int,
        val options: List<OptionUi>,
        val selected: String,
    ) : SettingItemUi

    /** F-SET-04：数字输入（滑块 + 输入框双方式的 v1 简化：±步进按钮 + 输入框） */
    data class Number(
        override val key: String,
        override val titleRes: Int,
        val value: Int,
        val min: Int,
        val max: Int,
        val step: Int,
        val suffixRes: Int,
    ) : SettingItemUi
}

data class OptionUi(val value: String, val labelRes: Int)

data class SettingGroupUi(
    val titleRes: Int,
    val items: List<SettingItemUi>,
)

data class SettingsUiState(
    val groups: List<SettingGroupUi> = emptyList(),
    val barometerHint: Boolean = false, // F-REC-63：无气压计 → 海拔阈值下限提示
) {
    companion object {
        val Empty = SettingsUiState()
    }
}

/**
 * P-14 设置页（DEV §5.2）：groups 按 PRD 6.7 分组；Toggle/Select/NumberChanged 即时写 DataStore
 * （F-SET-01/02）；ResetToDefault（F-SET-03）。导出/导入按钮由 M3-D 的 feature:io 接入后启用。
 * 无 Hilt（模块无 hilt 插件）：viewModelFactory + 参数传入（同 PlanListViewModel 先例）。
 */
class SettingsViewModel(
    private val repository: SettingsRepository,
    private val hasBarometer: Boolean,
    private val onLanguageChanged: (String) -> Unit, // 语言切换 → 重建 Activity（F-I18N-11）
) : ViewModel() {

    private val _state = MutableStateFlow(SettingsUiState(barometerHint = !hasBarometer))
    val state: StateFlow<SettingsUiState> = _state.asStateFlow()

    init {
        viewModelScope.launch {
            repository.settings.collect { s ->
                _state.update { it.copy(groups = buildGroups(s)) }
            }
        }
    }

    private fun buildGroups(s: AppSettings): List<SettingGroupUi> = listOf(
        SettingGroupUi(
            titleRes = R.string.set_group_general,
            items = listOf(
                SettingItemUi.Select(
                    key = "app_language",
                    titleRes = R.string.set_language,
                    options = listOf(
                        OptionUi(AppSettings.LANGUAGE_SYSTEM, R.string.set_lang_system),
                        OptionUi(AppSettings.LANGUAGE_ZH, R.string.set_lang_zh),
                        OptionUi(AppSettings.LANGUAGE_EN, R.string.set_lang_en),
                    ),
                    selected = s.language,
                ),
                SettingItemUi.Number(
                    key = "body_weight_kg",
                    titleRes = R.string.set_body_weight,
                    value = s.bodyWeightKg,
                    min = AppSettings.BODY_WEIGHT_RANGE.first,
                    max = AppSettings.BODY_WEIGHT_RANGE.last,
                    step = 1,
                    suffixRes = R.string.set_unit_kg,
                ),
            ),
        ),
        SettingGroupUi(
            titleRes = R.string.set_group_alert,
            items = listOf(
                SettingItemUi.Switch("alert_master", R.string.set_alert_master, s.alertMasterEnabled),
                SettingItemUi.Switch("alert_voice", R.string.set_alert_voice, s.alertVoiceEnabled),
                SettingItemUi.Switch("alert_distance", R.string.set_alert_distance, s.alertDistanceEnabled),
                SettingItemUi.Number(
                    key = "alert_distance_interval",
                    titleRes = R.string.set_alert_distance_interval,
                    value = s.alertDistanceIntervalM,
                    min = AppSettings.DISTANCE_INTERVAL_RANGE.first,
                    max = AppSettings.DISTANCE_INTERVAL_RANGE.last,
                    step = AppSettings.DISTANCE_INTERVAL_STEP,
                    suffixRes = R.string.set_unit_m,
                ),
                SettingItemUi.Switch("alert_altitude", R.string.set_alert_altitude, s.alertAltitudeEnabled),
                SettingItemUi.Number(
                    key = "alert_altitude_interval",
                    titleRes = R.string.set_alert_altitude_interval,
                    value = s.alertAltitudeIntervalM,
                    min = if (hasBarometer) AppSettings.ALTITUDE_INTERVAL_RANGE.first else AppSettings.ALTITUDE_INTERVAL_MIN_NO_BAROMETER,
                    max = AppSettings.ALTITUDE_INTERVAL_RANGE.last,
                    step = AppSettings.ALTITUDE_INTERVAL_STEP,
                    suffixRes = R.string.set_unit_m,
                ),
                SettingItemUi.Switch("alert_vibrate", R.string.set_alert_vibrate, s.alertVibrateEnabled),
            ),
        ),
        SettingGroupUi(
            titleRes = R.string.set_group_record,
            items = listOf(
                SettingItemUi.Select(
                    key = "record_location_mode",
                    titleRes = R.string.set_record_location_mode,
                    options = listOf(
                        OptionUi("high", R.string.set_loc_high),
                        OptionUi("power_saving", R.string.set_loc_power),
                    ),
                    selected = s.recordLocationMode,
                ),
                SettingItemUi.Switch("record_auto_pause", R.string.set_record_auto_pause, s.recordAutoPause),
                SettingItemUi.Switch("record_keep_screen_on", R.string.set_record_keep_screen_on, s.recordKeepScreenOn),
                SettingItemUi.Switch("record_background", R.string.set_record_background, s.recordBackgroundEnabled),
            ),
        ),
        SettingGroupUi(
            titleRes = R.string.set_group_unit,
            items = listOf(
                SettingItemUi.Select(
                    key = "unit_distance",
                    titleRes = R.string.set_unit_distance,
                    options = listOf(OptionUi("km", R.string.set_unit_km), OptionUi("mi", R.string.set_unit_mi)),
                    selected = s.unitDistance,
                ),
                SettingItemUi.Select(
                    key = "unit_altitude",
                    titleRes = R.string.set_unit_altitude,
                    options = listOf(OptionUi("m", R.string.set_unit_m), OptionUi("ft", R.string.set_unit_ft)),
                    selected = s.unitAltitude,
                ),
                SettingItemUi.Select(
                    key = "unit_pace_display",
                    titleRes = R.string.set_unit_pace_display,
                    options = listOf(OptionUi("pace", R.string.set_unit_pace), OptionUi("speed", R.string.set_unit_speed)),
                    selected = s.unitPaceDisplay,
                ),
            ),
        ),
        SettingGroupUi(
            titleRes = R.string.set_group_map,
            items = listOf(
                SettingItemUi.Select(
                    key = "map_type",
                    titleRes = R.string.set_map_type,
                    options = listOf(OptionUi("normal", R.string.set_map_normal), OptionUi("satellite", R.string.set_map_satellite)),
                    selected = s.mapType,
                ),
                SettingItemUi.Select(
                    key = "map_track_width",
                    titleRes = R.string.set_map_track_width,
                    options = listOf(
                        OptionUi("thin", R.string.set_width_thin),
                        OptionUi("medium", R.string.set_width_medium),
                        OptionUi("thick", R.string.set_width_thick),
                    ),
                    selected = s.mapTrackWidth,
                ),
                SettingItemUi.Switch("map_show_media_cluster", R.string.set_map_media_cluster, s.mapShowMediaCluster),
            ),
        ),
        SettingGroupUi(
            titleRes = R.string.set_group_data,
            items = listOf(
                SettingItemUi.Select(
                    key = "io_export_crs",
                    titleRes = R.string.set_io_export_crs,
                    options = listOf(OptionUi("GCJ-02", R.string.set_crs_gcj), OptionUi("WGS-84", R.string.set_crs_wgs)),
                    selected = s.ioExportCrs,
                ),
                SettingItemUi.Switch("io_gpx", R.string.set_io_gpx, "GPX" in s.ioExtraFormats), // F-IO-04
                SettingItemUi.Switch("io_backup_media", R.string.set_io_backup_media, s.ioBackupIncludeMedia),
                SettingItemUi.Switch("io_backup_checksum", R.string.set_io_backup_checksum, s.ioBackupIncludeChecksum),
                SettingItemUi.Select(
                    key = "io_conflict_policy",
                    titleRes = R.string.set_io_conflict_policy,
                    options = listOf(
                        OptionUi("skip", R.string.set_conflict_skip),
                        OptionUi("overwrite", R.string.set_conflict_overwrite),
                        OptionUi("duplicate", R.string.set_conflict_duplicate),
                        OptionUi("ask", R.string.set_conflict_ask),
                    ),
                    selected = s.ioImportConflictPolicy,
                ),
            ),
        ),
    )

    fun toggle(key: String, checked: Boolean) {
        viewModelScope.launch {
            when (key) {
                "alert_master" -> repository.setAlertMasterEnabled(checked)
                "alert_voice" -> repository.setAlertVoiceEnabled(checked)
                "alert_distance" -> repository.setAlertDistanceEnabled(checked)
                "alert_altitude" -> repository.setAlertAltitudeEnabled(checked)
                "alert_vibrate" -> repository.setAlertVibrateEnabled(checked)
                "record_auto_pause" -> repository.setRecordAutoPause(checked)
                "record_keep_screen_on" -> repository.setRecordKeepScreenOn(checked)
                "record_background" -> repository.setRecordBackgroundEnabled(checked)
                "map_show_media_cluster" -> repository.setMapShowMediaCluster(checked)
                "io_gpx" -> // F-IO-04：附加格式多选（v1 仅 GPX 开关）
                    repository.setIoExtraFormats(if (checked) setOf("GPX") else emptySet())
                "io_backup_media" -> repository.setIoBackupIncludeMedia(checked)
                "io_backup_checksum" -> repository.setIoBackupIncludeChecksum(checked)
            }
        }
    }

    fun select(key: String, value: String) {
        viewModelScope.launch {
            when (key) {
                "app_language" -> {
                    repository.setLanguage(value)
                    onLanguageChanged(value) // F-I18N-11：重建 Activity 生效
                }
                "record_location_mode" -> repository.setRecordLocationMode(value)
                "unit_distance" -> repository.setUnitDistance(value)
                "unit_altitude" -> repository.setUnitAltitude(value)
                "unit_pace_display" -> repository.setUnitPaceDisplay(value)
                "map_type" -> repository.setMapType(value)
                "map_track_width" -> repository.setMapTrackWidth(value)
                "io_export_crs" -> repository.setIoExportCrs(value)
                "io_conflict_policy" -> repository.setIoImportConflictPolicy(value)
            }
        }
    }

    fun numberChanged(key: String, value: Int) {
        viewModelScope.launch {
            when (key) {
                "body_weight_kg" -> repository.setBodyWeightKg(value)
                "alert_distance_interval" -> repository.setAlertDistanceIntervalM(value)
                "alert_altitude_interval" -> repository.setAlertAltitudeIntervalM(value)
            }
        }
    }

    /** F-SET-03 恢复默认设置 */
    fun resetToDefault() {
        viewModelScope.launch { repository.resetToDefault() }
    }
}
