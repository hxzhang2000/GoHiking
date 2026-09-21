package com.gohiking.core.location.altitude

/**
 * `AltitudeFuser` 的测试替身（DEV §2.3 可替身清单 / §8.8；文档审阅 D10）。
 *
 * 两种用法：
 * 1. **回放模式**（给了 [replay]）——每次 `onGpsFix` 消费序列里的下一个值，
 *    可以精确编排「漂移 / 跳变 / 突然不可用（null）」等场景；用尽后保持最后一个值。
 * 2. **透传模式**（[replay] 为空）——`current()` 直接回显最后一次 GPS 海拔
 *    （MSL 优先，与真实实现同口径）。
 *
 * 所有输入都记录在案（`gpsFixes` / `pressureSamples` / `calibrations`），
 * 断言「消费方到底喂了什么」比断言返回值更能定位问题。
 *
 * ⚠ 这是**测试替身**：放在 main 源集是为了让 `core:data` 等模块的单测也能引用
 * （testFixtures 在本工程尚未建立）；生产路径一律走 [RealAltitudeFuserFactory]。
 */
class FakeAltitudeFuser(
    private val replay: List<Double?> = emptyList(),
    override var source: AltitudeFuser.Source? = null,
) : AltitudeFusion {

    private var cursor = 0

    // 标定前为 null（与真实实现一致：未收到任何 fix 时海拔不可用，UI 显示「—」）
    private var currentAlt: Double? = null

    /** 收到的 GPS 修复：`(gpsAltM, verticalAccuracyM, mslAltM)` */
    val gpsFixes = mutableListOf<Triple<Double?, Double?, Double?>>()

    /** 收到的气压采样（hPa） */
    val pressureSamples = mutableListOf<Double>()

    /** 收到的手动校准目标海拔（F-REC-64） */
    val calibrations = mutableListOf<Double>()

    /** `onSessionPause()` 次数（暂停语义断言用） */
    var pauseCount = 0
        private set

    /** 最后一次 `restoreRef` 回灌的基准（崩溃恢复路径断言用） */
    var restoredRef: Pair<Double?, Double?>? = null
        private set

    override fun current(): Double? = currentAlt

    override fun onGpsFix(gpsAltM: Double?, verticalAccuracyM: Double?, mslAltM: Double?) {
        gpsFixes += Triple(gpsAltM, verticalAccuracyM, mslAltM)
        if (replay.isEmpty()) {
            // 透传：MSL 优先（同真实实现的取数顺序）
            val v = mslAltM ?: gpsAltM ?: return
            currentAlt = v
            return
        }
        if (cursor < replay.size) currentAlt = replay[cursor++]
    }

    override fun onPressure(hPa: Double) {
        pressureSamples += hPa
    }

    override fun calibrateTo(knownAltitudeM: Double) {
        calibrations += knownAltitudeM
        currentAlt = knownAltitudeM
        source = AltitudeFuser.Source.MANUAL_CALIBRATED
    }

    override fun onSessionPause() {
        pauseCount++
    }

    override fun refSnapshot(): Pair<Double?, Double?> = currentAlt to pressureSamples.lastOrNull()

    override fun restoreRef(altRefM: Double?, pressureRefHpaM: Double?) {
        restoredRef = altRefM to pressureRefHpaM
        currentAlt = altRefM
    }
}

/**
 * [FakeAltitudeFuser] 的工厂（注入 [com.gohiking.core.location.altitude.AltitudeFuserFactory]
 * 即可让消费方拿到替身）。[replay] 由构造方编排，见 [FakeAltitudeFuser] 的两种模式。
 */
class FakeAltitudeFuserFactory(
    private val replay: List<Double?> = emptyList(),
    private val source: AltitudeFuser.Source? = null,
) : AltitudeFuserFactory {

    /** 每次 `create()` 产出的实例都能在测试里取到（逐个断言用） */
    val created = mutableListOf<FakeAltitudeFuser>()

    override fun create(hasBarometer: Boolean): AltitudeFusion {
        val fake = FakeAltitudeFuser(
            replay = replay,
            source = source ?: if (hasBarometer) {
                AltitudeFuser.Source.BAROMETER_FUSED
            } else {
                AltitudeFuser.Source.GPS_ONLY
            },
        )
        created += fake
        return fake
    }
}
