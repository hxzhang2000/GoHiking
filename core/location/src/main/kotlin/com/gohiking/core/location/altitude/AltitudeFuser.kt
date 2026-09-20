package com.gohiking.core.location.altitude

import kotlin.math.abs
import kotlin.math.pow

/**
 * 海拔融合器（DEV §4.3，依据 PRD 4.4.1/4.4.2）。
 *
 * 有气压计（BAROMETER_FUSED）：GPS 前 10 秒标定 altRef → 气压高度做主数据源 →
 * 每 60 秒用 GPS 海拔中位数漂移修正（k=0.1，一阶低通）。
 * 无气压计（GPS_ONLY）：GPS 海拔为主，垂直精度 >20m 的点不参与海拔处理（防线②），
 * 海拔变化需连续稳定 10 秒才确认（防线③）。
 * 两者共用：9 点中值滤波、MSL 优先（API 34+ getMslAltitudeMeters，防线⑤）、
 * 手动校准（F-REC-64 → MANUAL_CALIBRATED）。
 *
 * 输出 null 表示海拔不可用，UI 显示「—」（PRD 6.1 降级规则），绝不编造。
 *
 * 注：气压→海拔用国际标准大气公式（与 SensorManager.getAltitude(PRESSURE_STANDARD_ATMOSPHERE, hPa)
 * 同族结果），纯 Kotlin 实现保证 JVM 单测确定性；可注入 [pressureAltitude] 替换。
 */
class AltitudeFuser(
    private val hasBarometer: Boolean,
    private val nowMs: () -> Long,
    private val pressureAltitude: (hPa: Double) -> Double = DEFAULT_PRESSURE_ALTITUDE,
) {

    enum class Source { BAROMETER_FUSED, GPS_ONLY, MANUAL_CALIBRATED }

    private val medianBuffer = ArrayDeque<Double>(MEDIAN_WINDOW)
    private val calibGpsAlts = mutableListOf<Double>()
    private val driftWindowGpsAlts = mutableListOf<Double>()

    private var startAtMs: Long = nowMs()
    private var altRef: Double? = null
    private var pressureRefHpa: Double? = null
    private var lastDriftFixAtMs: Long = nowMs()
    private var manualOffset = 0.0

    // 气压 1Hz 聚合（DEV §4.3：采样聚合到 1Hz 取均值）
    private var bucketSecond = -1L
    private var bucketSum = 0.0
    private var bucketCount = 0
    private var latestPressureHpa: Double? = null

    // GPS_ONLY 防线③：变化需连续稳定 10 秒
    private var confirmedAlt: Double? = null
    private var pendingAlt: Double? = null
    private var pendingSinceMs = 0L

    var source: Source? = null
        private set

    /** 滤波后的当前海拔（米）；标定完成前返回 null */
    fun current(): Double? {
        val filtered = if (hasBarometer) {
            val est = baroEstimate() ?: return null
            pushAndMedian(est)
        } else {
            // GPS_ONLY：confirmedAlt 已是「9 点中值 + 10 秒稳定确认」后的输出
            confirmedAlt
        }
        return filtered?.plus(manualOffset)
    }

    /** GPS 修复输入；mslAltM 优先（API 34+ getMslAltitudeMeters，防线⑤） */
    fun onGpsFix(gpsAltM: Double?, verticalAccuracyM: Double?, mslAltM: Double? = null) {
        if (gpsAltM == null && mslAltM == null) return
        val t = nowMs()
        val alt = mslAltM ?: gpsAltM ?: return

        // 防线②（仅无气压计路径）：垂直精度 >20m 的点不参与海拔处理
        if (!hasBarometer && verticalAccuracyM != null && verticalAccuracyM > VERTICAL_ACCURACY_GATE_M) return

        driftWindowGpsAlts.add(alt)
        if (driftWindowGpsAlts.size > DRIFT_WINDOW_MAX) driftWindowGpsAlts.removeAt(0)

        // 标定窗口：开始后前 10 秒取 GPS 海拔中位数
        val ref = altRef
        if (ref == null) {
            calibGpsAlts.add(alt)
            if (t - startAtMs >= CALIBRATION_WINDOW_MS) {
                altRef = median(calibGpsAlts)
                pressureRefHpa = latestPressureHpa
                lastDriftFixAtMs = t
                if (hasBarometer) {
                    source = Source.BAROMETER_FUSED
                } else {
                    confirmedAlt = altRef
                    source = Source.GPS_ONLY
                }
            }
            return
        }

        // 漂移修正：每 60 秒，altRef += k * (gpsMedian - currentEstimate)
        if (t - lastDriftFixAtMs >= DRIFT_PERIOD_MS) {
            val k = if (hasBarometer) DRIFT_K_BARO else DRIFT_K_GPS
            val est = if (hasBarometer) baroEstimate() else confirmedAlt
            if (est != null) {
                altRef = ref + k * (median(driftWindowGpsAlts) - est)
                if (hasBarometer) pressureRefHpa = latestPressureHpa
            }
            lastDriftFixAtMs = t
            driftWindowGpsAlts.clear()
            driftWindowGpsAlts.add(alt)
        }

        if (!hasBarometer) {
            // 防线③：中值滤波后的值作为候选，需连续稳定 10 秒才确认
            medianBuffer.addLast(alt)
            while (medianBuffer.size > MEDIAN_WINDOW) medianBuffer.removeFirst()
            val candidate = medianOfBuffer() ?: return
            val confirmed = confirmedAlt ?: return
            if (abs(candidate - confirmed) >= CONFIRM_DELTA_M) {
                if (pendingAlt == null || abs(candidate - (pendingAlt ?: candidate)) > 0.5) {
                    pendingAlt = candidate
                    pendingSinceMs = t
                } else if (t - pendingSinceMs >= STABLE_CONFIRM_MS) {
                    confirmedAlt = candidate
                    pendingAlt = null
                }
            } else {
                pendingAlt = null
            }
            if (source != Source.MANUAL_CALIBRATED) source = Source.GPS_ONLY
        }
    }

    /** 气压采样输入（hPa）；内部按秒桶聚合取均值（≈1Hz） */
    fun onPressure(hPa: Double) {
        if (!hasBarometer) return
        val sec = nowMs() / 1000L
        if (sec != bucketSecond) {
            if (bucketCount > 0) latestPressureHpa = bucketSum / bucketCount
            bucketSecond = sec
            bucketSum = 0.0
            bucketCount = 0
        }
        bucketSum += hPa
        bucketCount++
        if (altRef != null && source != Source.MANUAL_CALIBRATED) source = Source.BAROMETER_FUSED
    }

    /** 手动校准（F-REC-64）：输入已知海拔，偏移作用于全部后续输出 */
    fun calibrateTo(knownAltitudeM: Double) {
        val c = current() ?: return
        manualOffset += knownAltitudeM - c
        source = Source.MANUAL_CALIBRATED
    }

    /** 暂停/继续：保留 medianBuffer 与 altRef（DEV §4.3.1 anchor 保留要求），仅清漂移窗口 */
    fun onSessionPause() {
        driftWindowGpsAlts.clear()
        pendingAlt = null
    }

    /** 崩溃恢复快照：altRef 与 pressureRefHpa（recording_state.altitudeRefM/pressureRefHpa） */
    fun refSnapshot(): Pair<Double?, Double?> = altRef to pressureRefHpa

    /** 回灌快照（baro 路径恢复）；回灌后标定视为已完成 */
    fun restoreRef(altRefM: Double?, pressureRefHpaM: Double?) {
        val ref = altRefM ?: return
        altRef = ref
        pressureRefHpa = pressureRefHpaM
        startAtMs = nowMs() - CALIBRATION_WINDOW_MS - 1
        source = if (hasBarometer) Source.BAROMETER_FUSED else Source.GPS_ONLY
    }

    // —— 内部 ——

    /** 气压路径：推入最新估值并输出 9 点中值 */
    private fun pushAndMedian(est: Double): Double? {
        medianBuffer.addLast(est)
        while (medianBuffer.size > MEDIAN_WINDOW) medianBuffer.removeFirst()
        return medianOfBuffer()
    }

    private fun medianOfBuffer(): Double? = if (medianBuffer.isEmpty()) null else median(medianBuffer)

    private fun baroEstimate(): Double? {
        val ref = altRef ?: return null
        val p = latestPressureHpa ?: return ref
        val pRef = pressureRefHpa ?: return ref
        return ref + (pressureAltitude(p) - pressureAltitude(pRef))
    }

    private fun median(values: List<Double>): Double {
        val sorted = values.sorted()
        val n = sorted.size
        return if (n % 2 == 1) sorted[n / 2] else (sorted[n / 2 - 1] + sorted[n / 2]) / 2.0
    }

    companion object {
        const val CALIBRATION_WINDOW_MS = 10_000L
        const val DRIFT_PERIOD_MS = 60_000L
        const val DRIFT_K_BARO = 0.1
        const val DRIFT_K_GPS = 0.15
        const val MEDIAN_WINDOW = 9
        const val VERTICAL_ACCURACY_GATE_M = 20.0
        const val STABLE_CONFIRM_MS = 10_000L
        const val CONFIRM_DELTA_M = 2.0
        const val DRIFT_WINDOW_MAX = 120

        /** 国际标准大气：h = 44330.77 × (1 − (p/1013.25)^0.1902632) */
        val DEFAULT_PRESSURE_ALTITUDE: (Double) -> Double = { p ->
            44330.77 * (1.0 - (p / 1013.25).pow(0.1902632))
        }
    }
}
