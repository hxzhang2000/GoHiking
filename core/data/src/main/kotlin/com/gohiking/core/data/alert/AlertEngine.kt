package com.gohiking.core.data.alert

import com.gohiking.core.location.altitude.ThresholdAccumulator

/**
 * 提醒触发引擎（DEV §4.9）。纯逻辑、无 Android 依赖，便于单测。
 *
 * 关键约束：
 * - 距离打点的唯一持久基准是 [lastDistanceMarkM]（写入 recording_state.lastDistanceMarkM），
 *   判定 = 累计距离 ≥ n×interval，n 单调递增（F-ALERT-14/17，审阅 D2：不得另立 ThresholdAccumulator 双轨）。
 * - 海拔打点累加器（阈值 = 提醒间隔，可配置）与统计累加器是两个独立实例（冻结决策 5）；
 *   爬升/下降各自独立累计与判定（F-ALERT-24）。
 * - masterEnabled=false → 不产生任何标记（F-ALERT-05）；voiceEnabled 只影响播报，由调用方裁剪（F-ALERT-06）。
 */
class AlertEngine(settings: AlertSettings) {

    var settings: AlertSettings = settings
        private set

    // —— 持久化基准（recording_state.lastDistanceMarkM / lastAscentMarkM / lastDescentMarkM）——
    private var lastDistanceMarkM = 0.0
    private var lastAscentMarkM = 0.0
    private var lastDescentMarkM = 0.0

    // 海拔打点累加器：阈值 = 海拔提醒间隔（重建时保留累计与锚点，见 onSettingsChanged）
    private var elevationAccumulator = ThresholdAccumulator(settings.elevationIntervalM.toDouble())

    private var distanceSeq = 0
    private var ascentSeq = 0
    private var descentSeq = 0

    /**
     * 每个有效采样点（quality==0）调用一次；返回需要落库的标记 + 对应播报事件。
     * 播报是否发声由调用方按 voiceEnabled 裁剪（F-ALERT-06）。
     */
    fun onSample(sample: AlertSample): List<AlertEvent> {
        if (!settings.masterEnabled) return emptyList() // F-ALERT-05
        val events = mutableListOf<AlertEvent>()

        if (settings.distanceEnabled) {
            val nextMark = lastDistanceMarkM + settings.distanceIntervalM
            if (sample.distanceM >= nextMark) {
                lastDistanceMarkM = nextMark
                distanceSeq += 1
                events += AlertEvent(
                    marker = MarkerDraft(
                        type = "ALERT_DISTANCE",
                        sequence = distanceSeq,
                        latitude = sample.latitude,
                        longitude = sample.longitude,
                        altitude = sample.altitudeM,
                        extraJson = "{\"distanceM\":${sample.distanceM.toInt()}}",
                    ),
                    voice = AlertVoice.Distance(sample.distanceM.toInt()),
                )
            }
        }

        val altitude = sample.altitudeM ?: return events
        if (settings.elevationEnabled) {
            elevationAccumulator.accept(altitude)
            val asc = elevationAccumulator.ascent
            val desc = elevationAccumulator.descent
            val nextAsc = lastAscentMarkM + settings.elevationIntervalM
            if (asc >= nextAsc) {
                lastAscentMarkM = nextAsc
                ascentSeq += 1
                events += AlertEvent(
                    marker = MarkerDraft(
                        type = "ALERT_ASCENT",
                        sequence = ascentSeq,
                        latitude = sample.latitude,
                        longitude = sample.longitude,
                        altitude = altitude,
                        extraJson = "{\"accumM\":${asc.toInt()}}",
                    ),
                    voice = AlertVoice.Ascent(asc.toInt(), altitude.toInt()),
                )
            }
            val nextDesc = lastDescentMarkM + settings.elevationIntervalM
            if (desc >= nextDesc) {
                lastDescentMarkM = nextDesc
                descentSeq += 1
                events += AlertEvent(
                    marker = MarkerDraft(
                        type = "ALERT_DESCENT",
                        sequence = descentSeq,
                        latitude = sample.latitude,
                        longitude = sample.longitude,
                        altitude = altitude,
                        extraJson = "{\"accumM\":${desc.toInt()}}",
                    ),
                    voice = AlertVoice.Descent(desc.toInt(), altitude.toInt()),
                )
            }
        }
        return events
    }

    /** 设置即时生效（F-ALERT-03）：海拔间隔变化时重建累加器但保留累计与锚点 */
    fun onSettingsChanged(new: AlertSettings) {
        if (new.elevationIntervalM != settings.elevationIntervalM) {
            elevationAccumulator = ThresholdAccumulator(new.elevationIntervalM.toDouble()).also {
                it.restore(elevationAccumulator.currentAnchor(), elevationAccumulator.ascent, elevationAccumulator.descent)
            }
        }
        settings = new
    }

    /** recording_state 持久化用（每 10 秒 + 暂停时） */
    fun markBaselines(): Triple<Double, Double, Double> =
        Triple(lastDistanceMarkM, lastAscentMarkM, lastDescentMarkM)

    fun currentElevationAnchor(): Double? = elevationAccumulator.currentAnchor()

    /**
     * 崩溃恢复回灌。recording_state 未持久化打点累加值 → 累计从「上次打点基准」继续
     * （误差 ≤ 1 个间隔，绝不重复触发上一阈值：nextMark = lastMark + interval）。
     */
    fun restore(lastDistanceM: Double, lastAscentM: Double, lastDescentM: Double, elevationAnchor: Double?) {
        lastDistanceMarkM = lastDistanceM
        lastAscentMarkM = lastAscentM
        lastDescentMarkM = lastDescentM
        elevationAccumulator = ThresholdAccumulator(settings.elevationIntervalM.toDouble()).also {
            it.restore(elevationAnchor, lastAscentM, lastDescentM)
        }
    }
}

/** 引擎输入：每个有效采样点的判定所需最小集 */
data class AlertSample(
    val distanceM: Double, // 累计距离（暂停期间不增长，F-ALERT-16）
    val altitudeM: Double?, // 融合海拔（无气压计/低精度可为 null）
    val latitude: Double,
    val longitude: Double,
    val timestampMs: Long,
)

/** 引擎输出：待落库标记 + 待播报事件（落库由 Session 完成，播报按 voiceEnabled 裁剪） */
data class AlertEvent(val marker: MarkerDraft, val voice: AlertVoice)

data class MarkerDraft(
    val type: String, // ALERT_DISTANCE / ALERT_ASCENT / ALERT_DESCENT
    val sequence: Int, // 同类内序号（F-ALERT-27）
    val latitude: Double,
    val longitude: Double,
    val altitude: Double?, // 当时海拔（F-ALERT-27）
    val extraJson: String, // 类型特有字段：距离 {"distanceM":500}；海拔 {"accumM":300}（相对上一同向标记累计量）
)

/** 播报事件（文案模板见 core/resources strings_tts.xml，F-ALERT-28/40） */
sealed interface AlertVoice {
    data class Distance(val meters: Int) : AlertVoice
    data class Ascent(val meters: Int, val altitudeM: Int) : AlertVoice
    data class Descent(val meters: Int, val altitudeM: Int) : AlertVoice
}
