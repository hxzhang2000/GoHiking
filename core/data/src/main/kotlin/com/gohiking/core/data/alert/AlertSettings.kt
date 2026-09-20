package com.gohiking.core.data.alert

import com.gohiking.core.common.coroutine.ApplicationScope
import com.gohiking.core.datastore.SettingsRepository
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * 提醒设置（F-ALERT-01/02/07/10/20，PRD 6.6）。
 * 默认值来自 PRD 6.6（距离 100m / 海拔 100m）；持久化在 DataStore（SettingsRepository，M3-C）。
 */
data class AlertSettings(
    val masterEnabled: Boolean = true, // F-ALERT-01：总开关，关闭 = 不打点不播报（F-ALERT-05）
    val voiceEnabled: Boolean = true, // F-ALERT-02：只影响播报，不影响打点（F-ALERT-06）
    val distanceEnabled: Boolean = true, // F-ALERT-10
    val distanceIntervalM: Int = 100, // F-ALERT-11：50–5000 步进 50
    val elevationEnabled: Boolean = true, // F-ALERT-20
    val elevationIntervalM: Int = 100, // F-ALERT-21：10–1000 步进 10；无气压计下限 30 归 F-REC-63
) {
    companion object {
        val DISTANCE_PRESETS = listOf(100, 200, 500, 1000) // F-ALERT-13
        val ELEVATION_PRESETS = listOf(50, 100, 200) // F-ALERT-23
    }
}

/** 设置来源抽象：RecordingSession 只依赖本接口，不感知存储实现 */
interface AlertSettingsProvider {
    val settings: StateFlow<AlertSettings>
}

/**
 * DataStore 驱动实现（M3-C）：SettingsRepository.flow → AlertSettings 投影；
 * [update] 写回 DataStore（F-SET-02 即时生效：flow 回流后 _settings 更新）。
 */
@Singleton
class DataStoreAlertSettingsProvider @Inject constructor(
    private val repository: SettingsRepository,
    @ApplicationScope private val scope: CoroutineScope,
) : AlertSettingsProvider {

    private val _settings = MutableStateFlow(AlertSettings())
    override val settings: StateFlow<AlertSettings> = _settings.asStateFlow()

    init {
        scope.launch {
            repository.settings.collect { s ->
                _settings.value = AlertSettings(
                    masterEnabled = s.alertMasterEnabled,
                    voiceEnabled = s.alertVoiceEnabled,
                    distanceEnabled = s.alertDistanceEnabled,
                    distanceIntervalM = s.alertDistanceIntervalM,
                    elevationEnabled = s.alertAltitudeEnabled,
                    elevationIntervalM = s.alertAltitudeIntervalM,
                )
            }
        }
    }

    fun update(value: AlertSettings) {
        scope.launch {
            repository.setAlertMasterEnabled(value.masterEnabled)
            repository.setAlertVoiceEnabled(value.voiceEnabled)
            repository.setAlertDistanceEnabled(value.distanceEnabled)
            repository.setAlertDistanceIntervalM(value.distanceIntervalM)
            repository.setAlertAltitudeEnabled(value.elevationEnabled)
            repository.setAlertAltitudeIntervalM(value.elevationIntervalM)
        }
    }
}
