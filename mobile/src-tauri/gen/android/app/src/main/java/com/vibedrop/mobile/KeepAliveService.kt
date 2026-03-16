package com.vibedrop.mobile

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.os.Build
import android.os.IBinder

class KeepAliveService : Service() {

    companion object {
        const val CHANNEL_ID = "vibedrop_sync"
        const val NOTIFICATION_ID = 1
        const val ACTION_REFRESH_BACKGROUND_CLIPBOARD = "com.vibedrop.mobile.action.REFRESH_BACKGROUND_CLIPBOARD"
    }

    private var backgroundClipboardSyncManager: BackgroundClipboardSyncManager? = null

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
        startForeground(NOTIFICATION_ID, buildNotification())
        backgroundClipboardSyncManager = BackgroundClipboardSyncManager(this).also {
            it.reloadConfig()
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_REFRESH_BACKGROUND_CLIPBOARD) {
            backgroundClipboardSyncManager?.reloadConfig()
        }
        return START_STICKY // 被杀掉后自动重启
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        backgroundClipboardSyncManager?.shutdown()
        backgroundClipboardSyncManager = null
        super.onDestroy()
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "剪贴板同步",
                NotificationManager.IMPORTANCE_LOW // 静默通知，不弹出不响铃
            ).apply {
                description = "保持 VibeDrop 后台运行以同步剪贴板"
                setShowBadge(false)
            }
            val manager = getSystemService(NotificationManager::class.java)
            manager.createNotificationChannel(channel)
        }
    }

    private fun buildNotification(): Notification {
        // 点击通知打开 APP
        val intent = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP
        }
        val pendingIntent = PendingIntent.getActivity(
            this, 0, intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        return Notification.Builder(this, CHANNEL_ID)
            .setContentTitle("VibeDrop 同步中")
            .setContentText("剪贴板同步已开启")
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setContentIntent(pendingIntent)
            .setOngoing(true) // 不能被滑掉
            .build()
    }
}
