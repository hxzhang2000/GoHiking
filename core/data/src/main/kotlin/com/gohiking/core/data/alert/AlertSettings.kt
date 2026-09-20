package com.gohiking.core.data.alert

import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * 提醒设置（F-ALERT-01/02/07/10/20，PRD 6.6）。
 * 默认值来自 PRD 6.6（距离 100m / 海拔 100m）；持久化由 M3-C 设置页接 DataStore 后写入。
 */
data class AlertSettings(
    val masterEnabled: Boolean = true, // F-ALERT-01：总开关，关闭 = 不打点不播报（F-ALERT-05）
    val voiceEnabled: Boolean = true, // F-ALERT-02：只影响播报，不影响打点（F-ALERT-06）
    val distanceEnabled: Boolean = true, // F-ALERT-10
    val distanceIntervalM: Int = 100, // F-ALERT-11/12：50–5000，步进 50
    val elevationEnabled: Boolean = true, // F-ALERT-20
    val elevationIntervalM: Int = 100, // F-ALERT-21/22：10–1000，步进 10；无气压计下限 30 归 F-REC-63
) {
    companion object {
        val DISTANCE_PRESETS = listOf(100, 200, 500, 1000) // F-ALERT-13
        val ELEVATION_PRESETS = listOf(50, 100, 200) // F-ALERT-23
    }
}

/** 设置来源抽象：M3-A 用内存默认实现，M3-C 换 DataStore 仓储 */
interface AlertSettingsProvider {
    val settings: StateFlow<AlertSettings>
}

/** 内存实现（即时生效 F-ALERT-03 的更新入口；设置页接入后由 DataStore 仓储调用 update） */
@Singleton
class DefaultAlertSettingsProvider @Inject constructor() : AlertSettingsProvider {

    private val _settings = MutableStateFlow(AlertSettings())
    override val settings: StateFlow<AlertSettings> = _settings.asStateFlow()

    fun update(value: AlertSettings) {
        _settings.value = value
    }
}
