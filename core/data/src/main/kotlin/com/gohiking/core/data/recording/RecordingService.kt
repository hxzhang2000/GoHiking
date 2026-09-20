package com.gohiking.core.data.recording

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import com.gohiking.core.common.coroutine.ApplicationScope
import com.gohiking.core.resources.R as CoreR
import dagger.hilt.android.AndroidEntryPoint
import javax.inject.Inject
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

/**
 * 记录前台服务（F-REC-50/51/52）。只管生命周期与通知；状态全部来自 [RecordingSession]。
 * - ServiceCompat.startForeground 必须在 startForegroundService 后 5 秒内调用（DEV §6.1 红线）
 * - 通知「正在记录 · 距离 x.xx km」每 5 秒更新一次（省电，不是每秒）
 * - 通知动作：暂停 / 继续 / 停止（F-REC-52）；停止 = 停止记录并自动保存（M1 切片；
 *   UI 内停止会弹「保存 / 丢弃」对话框，见 F-REC-07）
 * - stopWithTask=false（manifest）：任务划掉后仍记录（F-REC-51）
 */
@AndroidEntryPoint
class RecordingService : Service() {

    @Inject lateinit var session: RecordingSession

    @Inject
    @ApplicationScope
    lateinit var scope: CoroutineScope

    private var collectJob: Job? = null
    private var lastNotifUpdateAtMs = 0L

    override fun onCreate() {
        super.onCreate()
        createChannel()
        // 5 秒红线：先立即进入前台，内容随后由 collector 刷新
        startInForeground(sessionNotification(0.0, paused = false))
        collectJob = scope.launch {
            session.state
                .collect { state ->
                    val active = state as? SessionState.Active
                    if (active == null) {
                        stopForegroundCompat()
                        stopSelf()
                        return@collect
                    }
                    val now = System.currentTimeMillis()
                    if (lastNotifUpdateAtMs == 0L || now - lastNotifUpdateAtMs >= NOTIF_UPDATE_INTERVAL_MS) {
                        lastNotifUpdateAtMs = now
                        startInForeground(
                            sessionNotification(active.distanceM / 1000.0, paused = !active.isRecording),
                        )
                    }
                }
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_PAUSE -> session.pause()
            ACTION_RESUME -> session.resume()
            ACTION_STOP -> scope.launch {
                session.stop()?.let { draft -> session.save(draft) } // 通知栏停止 = 自动保存（M1 切片）
            }
        }
        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        collectJob?.cancel()
        super.onDestroy()
    }

    private fun startInForeground(notification: Notification) {
        ServiceCompat.startForeground(
            this,
            NOTIF_ID,
            notification,
            if (Build.VERSION.SDK_INT >= 29) ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION else 0,
        )
    }

    private fun stopForegroundCompat() {
        ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
    }

    private fun sessionNotification(distanceKm: Double, paused: Boolean): Notification {
        val title = if (paused) getString(CoreR.string.rec_notif_paused) else getString(CoreR.string.rec_notif_recording)
        val contentIntent = PendingIntent.getActivity(
            this,
            0,
            packageManager.getLaunchIntentForPackage(packageName),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val builder = NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_menu_mylocation)
            .setContentTitle(title)
            .setContentText(getString(CoreR.string.rec_notif_distance, distanceKm))
            .setContentIntent(contentIntent)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
        if (paused) {
            builder.addAction(0, getString(CoreR.string.rec_action_resume), servicePendingIntent(ACTION_RESUME))
        } else {
            builder.addAction(0, getString(CoreR.string.rec_action_pause), servicePendingIntent(ACTION_PAUSE))
        }
        builder.addAction(0, getString(CoreR.string.rec_action_stop), servicePendingIntent(ACTION_STOP))
        return builder.build()
    }

    private fun servicePendingIntent(action: String): PendingIntent = PendingIntent.getService(
        this,
        action.hashCode(),
        Intent(this, RecordingService::class.java).setAction(action),
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
    )

    private fun createChannel() {
        if (Build.VERSION.SDK_INT >= 26) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                getString(CoreR.string.rec_notif_channel),
                NotificationManager.IMPORTANCE_LOW,
            )
            getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
        }
    }

    companion object {
        const val CHANNEL_ID = "recording"
        const val NOTIF_ID = 1001
        const val ACTION_PAUSE = "com.gohiking.action.PAUSE"
        const val ACTION_RESUME = "com.gohiking.action.RESUME"
        const val ACTION_STOP = "com.gohiking.action.STOP"
        private const val NOTIF_UPDATE_INTERVAL_MS = 5_000L

        fun start(context: Context) {
            ContextCompat.startForegroundService(context, Intent(context, RecordingService::class.java))
        }

        fun stop(context: Context) {
            context.stopService(Intent(context, RecordingService::class.java))
        }
    }
}
