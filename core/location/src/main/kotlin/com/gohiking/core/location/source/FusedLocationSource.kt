package com.gohiking.core.location.source

import android.annotation.SuppressLint
import android.content.Context
import android.location.Location
import android.os.Build
import android.os.Looper
import com.google.android.gms.common.ConnectionResult
import com.google.android.gms.common.GoogleApiAvailability
import com.google.android.gms.location.LocationCallback
import com.google.android.gms.location.LocationRequest
import com.google.android.gms.location.LocationResult
import com.google.android.gms.location.LocationServices
import com.google.android.gms.location.Priority
import com.gohiking.core.location.LocationFix
import com.gohiking.core.location.LocationProvider
import com.gohiking.core.location.crs.CoordinateConverter
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import timber.log.Timber

/**
 * Fused 备源（DEV §6.2 / D-08）。返回 WGS-84，【必须转 GCJ-02】——最容易漏的一处。
 *
 * GMS 不可用（国产 ROM 常态）时 [isAvailable] 为 false，上层 watchdog 只记日志不切换。
 * Flow 语义与 [AmapLocationSource] 一致：tryEmit + DROP_OLDEST + 丢点计数。
 */
class FusedLocationSource(private val context: Context) : LocationProvider {

    private val _fixes = MutableSharedFlow<LocationFix>(
        extraBufferCapacity = 64,
        onBufferOverflow = BufferOverflow.DROP_OLDEST,
    )
    override val fixes: Flow<LocationFix> = _fixes

    private val _dropped = MutableStateFlow(0)
    override val droppedCount: Int get() = _dropped.value

    /** 最近一次回调时间戳（watchdog 切回主源时用） */
    @Volatile
    var lastCallbackAtMs: Long = 0
        private set

    private var client: com.google.android.gms.location.FusedLocationProviderClient? = null

    private val callback = object : LocationCallback() {
        override fun onLocationResult(result: LocationResult) {
            lastCallbackAtMs = System.currentTimeMillis()
            val loc = result.lastLocation ?: return
            val sent = _fixes.tryEmit(toFix(loc))
            if (!sent) {
                _dropped.value += 1
                if (_dropped.value % 50 == 1) Timber.w("Fused 定位流丢点累计=%d", _dropped.value)
            }
        }
    }

    @SuppressLint("MissingPermission") // 权限在会话层已请求并校验
    override fun start(intervalMs: Long) {
        if (!isAvailable(context)) {
            Timber.w("GMS 不可用，Fused 备源无法启动")
            return
        }
        val c = client ?: LocationServices.getFusedLocationProviderClient(context).also { client = it }
        val request = LocationRequest.Builder(Priority.PRIORITY_HIGH_ACCURACY, intervalMs).build()
        c.requestLocationUpdates(request, callback, Looper.getMainLooper())
        lastCallbackAtMs = System.currentTimeMillis()
    }

    override fun stop() {
        client?.removeLocationUpdates(callback)
    }

    private fun toFix(location: Location): LocationFix {
        // ⚠ WGS-84 → GCJ-02（DEV §4.1 调用点清单第二行）
        val (lat, lng) = CoordinateConverter.wgs84ToGcj02(location.latitude, location.longitude)
        return LocationFix(
            lat = lat,
            lng = lng,
            altitudeM = if (location.hasAltitude()) location.altitude else null,
            verticalAccuracyM = location.verticalAccuracyMeters,
            mslAltitudeM = if (Build.VERSION.SDK_INT >= 34 && location.hasMslAltitude()) {
                location.mslAltitudeMeters
            } else {
                null
            },
            accuracyM = location.accuracy,
            speedMps = location.speed,
            bearing = location.bearing,
            timestampMs = location.time,
        )
    }

    companion object {
        fun isAvailable(context: Context): Boolean =
            GoogleApiAvailability.getInstance()
                .isGooglePlayServicesAvailable(context) == ConnectionResult.SUCCESS
    }
}
