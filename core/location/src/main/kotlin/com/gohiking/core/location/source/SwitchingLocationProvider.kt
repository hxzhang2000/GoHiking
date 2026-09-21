package com.gohiking.core.location.source

import com.gohiking.core.location.LocationFix
import com.gohiking.core.location.LocationProvider
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import timber.log.Timber

/**
 * 主备切换定位提供者（DEV D-08）：高德为主，Fused 为备。
 *
 * 切换规则（DEV §6.2）：
 * - 主源连续 30 秒无回调或主源报不可用错误 → 切备源，记录日志；
 * - 备源期间持续收集主源 fix，一旦主源恢复出点 → 切回主源、停备源；
 * - GMS 不可用 → 备源为 null，只记日志，继续用主源（国产 ROM 常态，不算错误）。
 *
 * 对上层完全透明：RecordingSession / 地图只看到统一的 [fixes] 流。
 */
class SwitchingLocationProvider(
    private val primary: AmapLocationSource,
    private val backup: FusedLocationSource?,
    private val scope: CoroutineScope,
) : LocationProvider {

    private val _fixes = MutableSharedFlow<LocationFix>(
        extraBufferCapacity = 64,
        onBufferOverflow = BufferOverflow.DROP_OLDEST,
    )
    override val fixes: Flow<LocationFix> = _fixes

    private val _dropped = MutableStateFlow(0)
    override val droppedCount: Int get() = _dropped.value

    @Volatile
    private var usingBackup = false

    private var watchdogJob: Job? = null
    private var primaryCollectJob: Job? = null
    private var backupCollectJob: Job? = null

    override fun start(intervalMs: Long) {
        primary.start(intervalMs)
        backupCollectJob?.cancel()
        primaryCollectJob = scope.launch {
            primary.fixes.collect { fix ->
                if (!usingBackup) {
                    if (!_fixes.tryEmit(fix)) _dropped.value += 1
                } else {
                    // 主源恢复出点 → 切回（DEV §6.2「恢复后切回」）
                    Timber.i("主源恢复，切回高德定位")
                    usingBackup = false
                    backup?.stop()
                    if (!_fixes.tryEmit(fix)) _dropped.value += 1
                }
            }
        }
        watchdogJob?.cancel()
        watchdogJob = scope.launch {
            while (true) {
                delay(5_000)
                if (usingBackup) continue
                val last = primary.lastCallbackAtMs
                val silent = last == 0L || System.currentTimeMillis() - last > WATCHDOG_TIMEOUT_MS
                if (silent) {
                    if (backup != null && !usingBackup) {
                        // H-03：此前这里会 primary.stop()，主源停止后不再回调，
                        // 第 55-59 行「主源恢复，切回」分支永远不可达 —— 降级变成不可逆。
                        // 改为保留主源、只屏蔽其转发，恢复时自然能切回（DEV §6.2）。
                        Timber.w("高德定位 30 秒无回调，切换 Fused 备源（主源保留待恢复）")
                        backup.start(intervalMs)
                        usingBackup = true
                        collectBackup()
                    } else {
                        Timber.w("高德定位 30 秒无回调，但 GMS 不可用，继续等待主源")
                    }
                }
            }
        }
    }

    private fun collectBackup() {
        if (backupCollectJob?.isActive == true) return
        val b = backup ?: return
        backupCollectJob = scope.launch {
            b.fixes.collect { fix ->
                if (usingBackup && !_fixes.tryEmit(fix)) _dropped.value += 1
            }
        }
    }

    override fun stop() {
        watchdogJob?.cancel()
        watchdogJob = null
        primaryCollectJob?.cancel()
        primaryCollectJob = null
        backupCollectJob?.cancel()
        backupCollectJob = null
        primary.stop()
        backup?.stop()
        usingBackup = false
    }

    companion object {
        const val WATCHDOG_TIMEOUT_MS = 30_000L
    }
}
