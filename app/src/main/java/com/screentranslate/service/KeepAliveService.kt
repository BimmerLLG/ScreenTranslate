package com.screentranslate.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import com.screentranslate.logger.L
import com.screentranslate.ui.MainActivity

/**
 * KeepAliveService - 后台保活前台服务
 * 在 App 启动时就运行，通过前台通知占住进程位置，防止被系统回收。
 * 当翻译服务启动后，通知内容会更新为运行状态。
 */
class KeepAliveService : Service() {

    private val channelId = "keep_alive_channel"
    private val notificationId = 1002

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
        L.d("KeepAlive", "保活服务已创建")
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        L.d("KeepAlive", "onStartCommand action=${intent?.action}, isRunning=${intent?.getBooleanExtra("is_running", false)}")
        when (intent?.action) {
            ACTION_UPDATE_STATUS -> {
                val isRunning = intent.getBooleanExtra("is_running", false)
                startForeground(notificationId, buildNotification(isRunning))
            }
            ACTION_STOP -> {
                stopForeground(STOP_FOREGROUND_REMOVE)
                stopSelf()
            }
            else -> {
                startForeground(notificationId, buildNotification(false))
            }
        }
        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        super.onDestroy()
        L.d("KeepAlive", "保活服务销毁")
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                channelId,
                "屏幕翻译保活",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "维持翻译服务在后台运行"
                setShowBadge(false)
            }
            val manager = getSystemService(NotificationManager::class.java)
            manager.createNotificationChannel(channel)
        }
        L.d("KeepAlive", "通知渠道已建立: ${channelId}")
    }

    private fun buildNotification(isRunning: Boolean): Notification {
        L.d("KeepAlive", "通知构建: isRunning=$isRunning")
        val openIntent = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_REORDER_TO_FRONT or Intent.FLAG_ACTIVITY_SINGLE_TOP
        }
        val pendingIntent = PendingIntent.getActivity(
            this, 0, openIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        return NotificationCompat.Builder(this, channelId)
            .setContentTitle("屏幕翻译")
            .setContentText(if (isRunning) "翻译服务运行中" else "就绪 - 切换到其他应用开始翻译")
            .setSmallIcon(android.R.drawable.ic_menu_edit)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setOngoing(true)
            .setContentIntent(pendingIntent)
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
            .build()
    }

    companion object {
        const val ACTION_UPDATE_STATUS = "com.screentranslate.ACTION_UPDATE_STATUS"
        const val ACTION_STOP = "com.screentranslate.ACTION_KEEP_ALIVE_STOP"

        fun start(service: android.content.Context) {
            val intent = Intent(service, KeepAliveService::class.java)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                service.startForegroundService(intent)
            } else {
                service.startService(intent)
            }
        }

        fun updateStatus(service: android.content.Context, isRunning: Boolean) {
            val intent = Intent(service, KeepAliveService::class.java).apply {
                action = ACTION_UPDATE_STATUS
                putExtra("is_running", isRunning)
            }
            service.startService(intent)
        }

        fun stop(service: android.content.Context) {
            val intent = Intent(service, KeepAliveService::class.java).apply {
                action = ACTION_STOP
            }
            service.startService(intent)
        }
    }
}
