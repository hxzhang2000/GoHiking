package com.gohiking.core.common.format

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

    @Volatile
    var units: DisplayUnits = DisplayUnits()
        private set

    fun update(units: DisplayUnits) {
        this.units = units
    }

    /** 仅供测试 */
    fun resetToMetric() {
        units = DisplayUnits()
    }

    val mPerMile: Double get() = M_PER_MILE
    val mPerFoot: Double get() = M_PER_FOOT
    val mpsToMph: Double get() = MPS_TO_MPH
    val mpsToKmh: Double get() = MPS_TO_KMH
}
