package com.gohiking.core.location.source

import android.content.Context
import com.amap.api.location.AMapLocation
import com.amap.api.location.AMapLocationClient
import com.amap.api.location.AMapLocationClientOption
import com.amap.api.location.AMapLocationListener
import com.gohiking.core.location.LocationFix
import com.gohiking.core.location.LocationProvider
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import timber.log.Timber

/**
 * 高德定位源（DEV §6.2 主源）。已返回 GCJ-02，【不转】坐标系。
 *
 * - 回调线程不做算法：立即 tryEmit 进 SharedFlow（extraBufferCapacity=64 + DROP_OLDEST，
 *   等价 DEV §6.2 的 Channel(64, DROP_OLDEST) 语义），算法在消费方协程里做；
 * - 丢点必须计数：记录场景丢一个点 = 数据永久缺失，会话结束 diagnostics 可见；
 * - 暂停时 stop()（省电），恢复时重新 start()。
 *
 * ⚠ 主/备切换（DEV D-08：30 秒无回调或 KEY/权限错误 → 切 Fused）：FusedLocationSource
 * 在 M1 下一批接入；本类先暴露 [onPrimaryUnavailable] 回调与 [lastCallbackAtMs]，
 * 切源由上层 SwitchingLocationProvider 组合。
 */
@Singleton
class AmapLocationSource @Inject constructor(
    @ApplicationContext private val context: Context,
) : LocationProvider {

    private val _fixes = MutableSharedFlow<LocationFix>(
        extraBufferCapacity = 64,
        onBufferOverflow = BufferOverflow.DROP_OLDEST,
    )
    override val fixes: Flow<LocationFix> = _fixes

    private val _dropped = MutableStateFlow(0)
    override val droppedCount: Int get() = _dropped.value

    /** 最近一次收到回调的时间戳；watchdog 据此判定 30 秒无回调 */
    @Volatile
    var lastCallbackAtMs: Long = 0
        private set

    /** 主源不可用回调（KEY 鉴权失败 / 缺权限 / 定位失败 / 长时间无回调时由 watchdog 触发） */
    var onPrimaryUnavailable: (() -> Unit)? = null

    private var client: AMapLocationClient? = null
    private var option: AMapLocationClientOption? = null

    private val listener = AMapLocationListener { location ->
        lastCallbackAtMs = System.currentTimeMillis()
        val code = location.errorCode
        if (code != 0) { // 高德定位：errorCode == 0 即成功
            Timber.w("高德定位错误 code=%d info=%s", code, location.errorInfo)
            if (code in UNAVAILABLE_ERROR_CODES) {
                onPrimaryUnavailable?.invoke()
            }
            // 定位失败时 location 仍可能带出上次坐标，跳过本次
            return@AMapLocationListener
        }
        val sent = _fixes.tryEmit(toFix(location))
        if (!sent) {
            _dropped.value += 1
            if (_dropped.value % 50 == 1) Timber.w("定位流丢点累计=%d", _dropped.value)
        }
    }

    override fun start(intervalMs: Long) {
        if (option == null || option?.interval != intervalMs) {
            option = AMapLocationClientOption().apply {
                locationMode = AMapLocationClientOption.AMapLocationMode.Hight_Accuracy
                this.interval = intervalMs
                isOnceLocation = false
                isNeedAddress = false
            }
        }
        val c = client ?: AMapLocationClient(context.applicationContext).also { client = it }
        c.setLocationOption(option)
        c.setLocationListener(listener)
        c.startLocation()
        lastCallbackAtMs = System.currentTimeMillis()
    }

    override fun stop() {
        client?.stopLocation()
    }

    fun release() {
        client?.onDestroy()
        client = null
    }

    private fun toFix(location: AMapLocation): LocationFix = LocationFix(
        lat = location.latitude,
        lng = location.longitude,
        altitudeM = if (location.hasAltitude()) location.altitude else null,
        verticalAccuracyM = location.verticalAccuracyMeters, // minSdk 26，恒可用
        mslAltitudeM = null, // API 34+ getMslAltitudeMeters 由 AltitudeFuser 批次接入
        accuracyM = location.accuracy,
        speedMps = location.speed,
        bearing = location.bearing,
        timestampMs = location.time,
    )

    private companion object {
        /** 7=KEY 鉴权失败 12=缺少定位权限 14=定位失败——均属主源不可用，触发切备源 */
        val UNAVAILABLE_ERROR_CODES = setOf(7, 12, 14)
    }
}
