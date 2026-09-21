package com.gohiking.core.location.source

import com.gohiking.core.location.LocationFix
import com.gohiking.core.location.LocationProvider
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update
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
        // N-06：主源报不可用错误（KEY 鉴权失败 7 / 缺权限 12 / 定位失败 14）时切备源。
        // 此前该回调全仓库零赋值点——DEV §6.2 的「主源报不可用错误 → 切备源」完全没接线，
        // 三条通路里只剩 watchdog 一条，而那一条也被错误回调刷新时钟给废掉了（见下）。
        // 回调来自 AMap 的 binder 线程，切源涉及 GMS 调用，这里切回协程执行。
        primary.onPrimaryUnavailable = {
            Timber.w("主源报不可用错误，准备切换备源")
            scope.launch { switchToBackup(intervalMs) }
        }
        backupCollectJob?.cancel()
        // S-01：原实现没有取消旧的 primaryCollectJob。一旦出现「未 stop 就再 start」，
        // 旧 collector 会永久存活并继续向 _fixes 发射，每个 fix 被转发两次（距离翻倍）。
        primaryCollectJob?.cancel()
        primaryCollectJob = scope.launch {
            primary.fixes.collect { fix ->
                if (!usingBackup) {
                    if (!_fixes.tryEmit(fix)) _dropped.update { it + 1 }
                } else {
                    // 主源恢复出点 → 切回（DEV §6.2「恢复后切回」）
                    Timber.i("主源恢复，切回高德定位")
                    usingBackup = false
                    backup?.stop()
                    if (!_fixes.tryEmit(fix)) _dropped.update { it + 1 }
                }
            }
        }
        watchdogJob?.cancel()
        watchdogJob = scope.launch {
            // N-41：watchdog 在新起的协程里跑，任何异常都会冒泡到无 CoroutineExceptionHandler
            // 的 ApplicationScope → 线程默认未捕获处理器 → 进程崩溃、记录中断。加兜底。
            try {
                while (true) {
                    delay(5_000)
                    if (usingBackup) continue
                    // N-06：判定的是「有没有**有效定位**」，不是「有没有回调」。
                    // 原实现读 lastCallbackAtMs，而它在判定错误码之前就被刷新——只要错误回调
                    // 的间隔 < 30s，watchdog 就永远判不出静默：主源持续失败却不切源、也不报错，
                    // 用户走完全程得到 0 点轨迹，且 droppedCount 仍是 0，无任何可诊断痕迹。
                    val last = primary.lastValidFixAtMs
                    val silent = last == 0L || System.currentTimeMillis() - last > WATCHDOG_TIMEOUT_MS
                    if (silent) switchToBackup(intervalMs)
                }
            } catch (c: CancellationException) {
                throw c
            } catch (t: Throwable) {
                Timber.e(t, "定位 watchdog 异常，watchdog 退出")
            }
        }
    }

    /** 切备源；主源保留待恢复（H-03：此前 stop 主源会让「恢复后切回」永远不可达） */
    private fun switchToBackup(intervalMs: Long) {
        if (usingBackup) return
        val b = backup
        if (b == null) {
            Timber.w("高德定位不可用，但 GMS 不可用，继续等待主源")
            return
        }
        try {
            b.start(intervalMs)
        } catch (t: Throwable) {
            // N-41：用户撤销定位权限、「仅此次」授权过期时会抛 SecurityException，
            // 不能让切源动作把整个 App 带崩。
            Timber.w(t, "切换 Fused 备源失败，继续使用主源")
            return
        }
        Timber.w("高德定位 30 秒无有效定位，切换 Fused 备源（主源保留待恢复）")
        usingBackup = true
        collectBackup()
    }

    private fun collectBackup() {
        if (backupCollectJob?.isActive == true) return
        val b = backup ?: return
        backupCollectJob = scope.launch {
            b.fixes.collect { fix ->
                if (usingBackup && !_fixes.tryEmit(fix)) _dropped.update { it + 1 }
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
        primary.onPrimaryUnavailable = null
        primary.stop()
        backup?.stop()
        usingBackup = false
    }

    companion object {
        const val WATCHDOG_TIMEOUT_MS = 30_000L
    }
}
