package com.gohiking.core.common.format

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * 展示单位配置（H-02：AppSettings.unitDistance / unitAltitude / unitPaceDisplay
 * 此前只是「能存、能显示、不生效」的死设置，Formatters 恒定输出公制）。
 *
 * 放在 core:common 是为了让纯 Kotlin 的 [Formatters] 无需依赖 core:datastore；
 * App 壳层在设置变化时调用 [DisplayUnitProvider.update] 同步。
 */
enum class DistanceUnit { METRIC, IMPERIAL }

enum class AltitudeUnit { METERS, FEET }

/** 配速优先还是速度优先（PRD 6.5） */
enum class PaceDisplay { PACE, SPEED }

data class DisplayUnits(
    val distance: DistanceUnit = DistanceUnit.METRIC,
    val altitude: AltitudeUnit = AltitudeUnit.METERS,
    val pace: PaceDisplay = PaceDisplay.PACE,
) {
    companion object {
        /** 从 AppSettings 的字符串协议值构造；未知值回落公制（枚举值不翻译，DEV 决策 8） */
        fun fromStrings(distance: String?, altitude: String?, pace: String?): DisplayUnits = DisplayUnits(
            distance = if (distance == "mi") DistanceUnit.IMPERIAL else DistanceUnit.METRIC,
            altitude = if (altitude == "ft") AltitudeUnit.FEET else AltitudeUnit.METERS,
            pace = if (pace == "speed") PaceDisplay.SPEED else PaceDisplay.PACE,
        )
    }
}

/** 进程内单例；默认公制，保证纯 Kotlin 单测无需配置 */
object DisplayUnitProvider {
    private const val M_PER_MILE = 1609.344
    private const val M_PER_FOOT = 0.3048
    private const val MPS_TO_MPH = 2.2369362920544
    private const val MPS_TO_KMH = 3.6

    private val _units = MutableStateFlow(DisplayUnits())

    /**
     * 同步读取（供 core 层 [Formatters] 等非组合环境使用）。
     *
     * ⚠ N-07：此前这里是 `@Volatile var`，**不是**可观察状态——读它不会订阅重组，
     * 于是把格式化文案缓存在 ViewModel init 里的页面（历史列表）在单位变更后永远不刷新：
     * 切到英里再回到列表仍显示 km，直到 Room 重新发射或重启进程。
     * UI/ViewModel 侧必须改用 [unitsFlow]。
     */
    val units: DisplayUnits get() = _units.value

    /** N-07：可观察的单位状态；单位变更时订阅方需重算所有已格式化的文案 */
    val unitsFlow: StateFlow<DisplayUnits> = _units.asStateFlow()

    fun update(units: DisplayUnits) {
        _units.value = units
    }

    /** 仅供测试 */
    fun resetToMetric() {
        _units.value = DisplayUnits()
    }

    val mPerMile: Double get() = M_PER_MILE
    val mPerFoot: Double get() = M_PER_FOOT
    val mpsToMph: Double get() = MPS_TO_MPH
    val mpsToKmh: Double get() = MPS_TO_KMH
}
