package com.gohiking.core.location.step

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.callbackFlow

/**
 * 计步源（DEV §4.4，依据 PRD 4.5.3 四级降级）。
 *
 * wire 协议值（不翻译，i18n 冻结决策 8）：SENSOR_COUNTER / SENSOR_DETECTOR /
 * ACCEL_ALGORITHM / UNAVAILABLE / MANUAL。
 */
interface StepSource {
    /** 实时步数；不可用时为 -1（UI 显示「—」，允许手动补录） */
    val steps: Flow<Int>
    val wire: String

    /** 暂停期间不计步：暂停时冻结计数（级①更新基准），默认空实现 */
    fun pause() {}
    fun resume() {}

    /**
     * 释放底层资源（注销传感器监听）。H-04：此前三处 registerListener 均无配对 unregister，
     * 记录停止后加速度计仍按 SENSOR_DELAY_GAME 采样，持续耗电。
     */
    fun close() {}
}

object StepWire {
    const val SENSOR_COUNTER = "SENSOR_COUNTER"
    const val SENSOR_DETECTOR = "SENSOR_DETECTOR"
    const val ACCEL_ALGORITHM = "ACCEL_ALGORITHM"
    const val UNAVAILABLE = "UNAVAILABLE"
    const val MANUAL = "MANUAL"
}

/** 级④：不可用 */
object UnavailableStepSource : StepSource {
    private val flow = MutableStateFlow(-1)
    override val steps: Flow<Int> = flow
    override val wire: String = StepWire.UNAVAILABLE
}

/** 级①：TYPE_STEP_COUNTER（开机以来累计值 → 记录开始取基准；暂停时更新基准） */
class SensorCounterStepSource(
    private val sensorManager: SensorManager,
) : StepSource {

    private val flow = MutableStateFlow(0)
    override val steps: Flow<Int> = flow
    override val wire: String = StepWire.SENSOR_COUNTER

    private var baseRaw = -1L
    private var accumulated = 0
    private var lastRaw = -1L
    private var regressed = false // F-REC-72：设备重启回退检测
    private var paused = false

    private val listener = object : SensorEventListener {
        override fun onSensorChanged(event: SensorEvent) {
            val raw = event.values.firstOrNull()?.toLong() ?: return
            if (raw < lastRaw) {
                // F-REC-72：current < lastRaw → 设备重启 → 本段不再累加，UI 提示
                regressed = true
                flow.value = -1
                return
            }
            lastRaw = raw
            if (baseRaw < 0) baseRaw = raw
            if (!paused && !regressed) {
                flow.value = accumulated + (raw - baseRaw).toInt()
            }
        }

        override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) = Unit
    }

    init {
        sensorManager.getDefaultSensor(Sensor.TYPE_STEP_COUNTER)?.let {
            sensorManager.registerListener(listener, it, SensorManager.SENSOR_DELAY_NORMAL)
        }
    }

    override fun pause() {
        // 暂停期间不计步：把当前 raw 冻结进累计
        if (lastRaw >= 0 && baseRaw >= 0 && !regressed) {
            accumulated += (lastRaw - baseRaw).toInt()
            baseRaw = lastRaw
        }
        paused = true
    }

    override fun resume() {
        paused = false
    }

    override fun close() {
        paused = true
        sensorManager.unregisterListener(listener)
    }
}

/** 级②：TYPE_STEP_DETECTOR（每事件 +1） */
class SensorDetectorStepSource(private val sensorManager: SensorManager) : StepSource {

    private val flow = MutableStateFlow(0)
    override val steps: Flow<Int> = flow
    override val wire: String = StepWire.SENSOR_DETECTOR
    private var paused = false

    private val listener = object : SensorEventListener {
        override fun onSensorChanged(event: SensorEvent) {
            if (paused) return
            if (event.sensor?.type == Sensor.TYPE_STEP_DETECTOR) {
                flow.value = flow.value + 1
            }
        }

        override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) = Unit
    }

    init {
        sensorManager.getDefaultSensor(Sensor.TYPE_STEP_DETECTOR)?.let {
            sensorManager.registerListener(listener, it, SensorManager.SENSOR_DELAY_NORMAL)
        }
    }

    override fun pause() {
        paused = true
    }

    override fun resume() {
        paused = false
    }

    override fun close() {
        paused = true
        sensorManager.unregisterListener(listener)
    }
}

/** 级③：加速度计自研算法（50Hz → AccelStepDetector） */
class AccelAlgorithmStepSource(
    private val sensorManager: SensorManager,
    clock: () -> Long = System::currentTimeMillis,
) : StepSource {

    private val flow = MutableStateFlow(0)
    override val steps: Flow<Int> = flow
    override val wire: String = StepWire.ACCEL_ALGORITHM
    private val detector = AccelStepDetector(timestampMs = clock)
    private var paused = false

    private val listener = object : SensorEventListener {
        override fun onSensorChanged(event: SensorEvent) {
            if (paused) return
            val v = event.values
            // 50Hz：靠 SENSOR_DELAY_GAME 近似，无需自行节流（重复样本无峰值影响）
            if (detector.accept(v[0], v[1], v[2])) {
                flow.value = detector.steps
            }
        }

        override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) = Unit
    }

    init {
        sensorManager.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)?.let {
            sensorManager.registerListener(listener, it, SensorManager.SENSOR_DELAY_GAME)
        }
    }

    override fun pause() {
        paused = true
    }

    override fun resume() {
        paused = false
    }

    override fun close() {
        paused = true
        sensorManager.unregisterListener(listener)
    }
}

/** 四级降级工厂（DEV §4.4） */
object StepSourceFactory {
    fun create(ctx: Context, hasPermission: Boolean): StepSource {
        if (!hasPermission) return UnavailableStepSource
        val sm = ctx.getSystemService(SensorManager::class.java) ?: return UnavailableStepSource
        return when {
            // TYPE_* 常量在 Sensor 类上（DEV §9.3 D-16）
            sm.getDefaultSensor(Sensor.TYPE_STEP_COUNTER) != null -> SensorCounterStepSource(sm)
            sm.getDefaultSensor(Sensor.TYPE_STEP_DETECTOR) != null -> SensorDetectorStepSource(sm)
            sm.getDefaultSensor(Sensor.TYPE_ACCELEROMETER) != null -> AccelAlgorithmStepSource(sm)
            else -> UnavailableStepSource
        }
    }
}
