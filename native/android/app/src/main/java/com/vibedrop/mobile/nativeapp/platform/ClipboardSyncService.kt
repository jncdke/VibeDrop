package com.vibedrop.mobile.nativeapp.platform

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.util.Log
import androidx.room.Room
import com.vibedrop.mobile.nativeapp.MainActivity
import com.vibedrop.mobile.nativeapp.data.local.DeviceEntity
import com.vibedrop.mobile.nativeapp.data.local.VibeDropDatabase
import com.vibedrop.mobile.nativeapp.protocol.AuthPayload
import com.vibedrop.mobile.nativeapp.protocol.VibeDropActions
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import org.json.JSONObject
import java.util.concurrent.TimeUnit
import kotlin.math.min

class ClipboardSyncService : Service() {
    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val mainHandler = Handler(Looper.getMainLooper())
    private val httpClient = OkHttpClient.Builder()
        .retryOnConnectionFailure(true)
        .pingInterval(15, TimeUnit.SECONDS)
        .build()
    private val connections = LinkedHashMap<String, ClipboardConnection>()

    private lateinit var database: VibeDropDatabase
    private lateinit var identity: AndroidDeviceIdentity
    private var lastAppliedClipboardText: String? = null
    private var lastAppliedClipboardAt = 0L

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
        startForeground(NOTIFICATION_ID, buildNotification())
        identity = loadAndroidDeviceIdentity(applicationContext)
        database = Room.databaseBuilder(
            applicationContext,
            VibeDropDatabase::class.java,
            "vibedrop-native.db"
        ).build()
        reloadConnections()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_REFRESH || connections.isEmpty()) {
            reloadConnections()
        }
        return START_STICKY
    }

    override fun onDestroy() {
        connections.values.forEach { it.close() }
        connections.clear()
        serviceScope.cancel()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun reloadConnections() {
        serviceScope.launch {
            val devices = database.deviceDao().getClipboardSyncDevices()
            mainHandler.post {
                val activeIds = devices.map { it.id }.toSet()
                connections.keys
                    .filterNot { it in activeIds }
                    .forEach { id -> connections.remove(id)?.close() }
                devices.forEach { device ->
                    val existing = connections[device.id]
                    if (existing == null || !existing.matches(device)) {
                        existing?.close()
                        connections[device.id] = ClipboardConnection(device).also { it.connect() }
                    }
                }
            }
        }
    }

    private fun applyClipboard(text: String) {
        if (text.isBlank()) return
        val now = System.currentTimeMillis()
        if (lastAppliedClipboardText == text && now - lastAppliedClipboardAt < 1200L) return
        lastAppliedClipboardText = text
        lastAppliedClipboardAt = now
        mainHandler.post {
            runCatching {
                val manager = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                manager.setPrimaryClip(ClipData.newPlainText("VibeDrop", text))
            }.onFailure { error ->
                Log.w(TAG, "后台直接写剪贴板失败，尝试透明 Activity", error)
                runCatching {
                    ClipboardFloatingActivity.launchWrite(applicationContext, text)
                }.onFailure { fallbackError ->
                    Log.e(TAG, "透明 Activity 写剪贴板也失败", fallbackError)
                }
            }
        }
    }

    private inner class ClipboardConnection(
        private val device: DeviceEntity
    ) : WebSocketListener() {
        private var socket: WebSocket? = null
        private var reconnectAttempt = 0
        private var closed = false

        fun matches(other: DeviceEntity): Boolean {
            return device.host == other.host && device.port == other.port && device.pin == other.pin
        }

        fun connect() {
            if (closed) return
            val host = device.host ?: return
            val port = device.port ?: return
            val request = Request.Builder()
                .url("ws://$host:$port/ws")
                .build()
            socket = httpClient.newWebSocket(request, this)
        }

        fun close() {
            closed = true
            socket?.close(1000, "clipboard sync stop")
            socket = null
        }

        override fun onOpen(webSocket: WebSocket, response: Response) {
            reconnectAttempt = 0
            webSocket.send(
                AuthPayload(
                    pin = device.pin.orEmpty(),
                    deviceId = identity.clipboardDeviceId(device.id),
                    baseDeviceId = identity.baseDeviceId,
                    deviceName = identity.clipboardDeviceName(),
                    canReceiveFiles = false,
                    receivesClipboard = true,
                    deviceRole = "clipboard_sync"
                ).toJson().toString()
            )
        }

        override fun onMessage(webSocket: WebSocket, text: String) {
            val json = runCatching { JSONObject(text) }.getOrNull() ?: return
            if (json.optString("action") == VibeDropActions.Clipboard) {
                applyClipboard(json.optString("text"))
            }
        }

        override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
            scheduleReconnect()
        }

        override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
            scheduleReconnect()
        }

        private fun scheduleReconnect() {
            if (closed) return
            reconnectAttempt += 1
            val delayMs = min(60_000L, 3_000L * reconnectAttempt)
            mainHandler.postDelayed({ connect() }, delayMs)
        }
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "剪贴板同步",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "保持 VibeDrop 后台运行以同步剪贴板"
                setShowBadge(false)
            }
            getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
        }
    }

    private fun buildNotification(): Notification {
        val intent = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP
        }
        val pendingIntent = PendingIntent.getActivity(
            this,
            0,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        return Notification.Builder(this, CHANNEL_ID)
            .setContentTitle("VibeDrop 同步中")
            .setContentText("剪贴板同步已开启")
            .setSmallIcon(android.R.drawable.stat_notify_sync)
            .setContentIntent(pendingIntent)
            .setOngoing(true)
            .build()
    }

    companion object {
        private const val CHANNEL_ID = "vibedrop_native_clipboard_sync"
        private const val NOTIFICATION_ID = 1001
        private const val ACTION_REFRESH = "com.vibedrop.mobile.nativeapp.action.REFRESH_CLIPBOARD_SYNC"
        private const val TAG = "VibeDrop.ClipboardSync"

        fun start(context: Context) {
            val intent = Intent(context, ClipboardSyncService::class.java).apply {
                action = ACTION_REFRESH
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
        }
    }
}

fun startClipboardSyncService(context: Context) {
    ClipboardSyncService.start(context.applicationContext)
}
