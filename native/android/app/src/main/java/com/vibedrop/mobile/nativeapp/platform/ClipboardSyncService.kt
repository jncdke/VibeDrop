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
import com.vibedrop.mobile.nativeapp.data.local.VibeDropMigrations
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
    private lateinit var diagnosticLogStore: DiagnosticLogStore
    private var lastAppliedClipboardText: String? = null
    private var lastAppliedClipboardAt = 0L

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
        startForeground(NOTIFICATION_ID, buildNotification())
        identity = loadAndroidDeviceIdentity(applicationContext)
        diagnosticLogStore = DiagnosticLogStore(applicationContext)
        diagnosticLogStore.append("clipboard-sync", "service_start")
        database = Room.databaseBuilder(
            applicationContext,
            VibeDropDatabase::class.java,
            "vibedrop-native.db"
        )
            .addMigrations(*VibeDropMigrations.ALL)
            .build()
        reloadConnections()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_REFRESH || connections.isEmpty()) {
            reloadConnections()
        }
        return START_STICKY
    }

    override fun onDestroy() {
        diagnosticLogStore.append("clipboard-sync", "service_stop")
        connections.values.forEach { it.close() }
        connections.clear()
        serviceScope.cancel()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun reloadConnections() {
        serviceScope.launch {
            val devices = database.deviceDao().getClipboardSyncDevices()
            diagnosticLogStore.append("clipboard-sync", "reload_devices", JSONObject().put("count", devices.size))
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
                diagnosticLogStore.append("clipboard-sync", "clipboard_applied", JSONObject().put("length", text.length).put("method", "direct"))
            }.onFailure { error ->
                diagnosticLogStore.append("clipboard-sync", "direct_clipboard_failed", JSONObject().put("error", error.message ?: error.javaClass.simpleName))
                Log.w(TAG, "后台直接写剪贴板失败，尝试透明 Activity", error)
                runCatching {
                    ClipboardFloatingActivity.launchWrite(applicationContext, text)
                }.onFailure { fallbackError ->
                    diagnosticLogStore.append("clipboard-sync", "floating_clipboard_failed", JSONObject().put("error", fallbackError.message ?: fallbackError.javaClass.simpleName))
                    Log.e(TAG, "透明 Activity 写剪贴板也失败", fallbackError)
                }
            }
        }
    }

    private inner class ClipboardConnection(
        private val device: DeviceEntity
    ) : WebSocketListener() {
        @Volatile
        private var socket: WebSocket? = null
        private var reconnectAttempt = 0
        private var reconnectRunnable: Runnable? = null
        private var endpointIndex = 0
        @Volatile
        private var activeHost: String = device.host.orEmpty()
        private var closed = false

        fun matches(other: DeviceEntity): Boolean {
            return device.host == other.host &&
                device.ip == other.ip &&
                device.port == other.port &&
                device.pin == other.pin
        }

        fun connect() {
            if (closed) return
            cancelScheduledReconnect()
            val host = nextEndpointHost() ?: return
            val port = device.port ?: return
            activeHost = host
            log("connect_start")
            val request = Request.Builder()
                .url("ws://$host:$port/ws")
                .build()
            socket = httpClient.newWebSocket(request, this)
        }

        fun close() {
            closed = true
            cancelScheduledReconnect()
            log("connection_close_requested")
            socket?.close(1000, "clipboard sync stop")
            socket = null
        }

        override fun onOpen(webSocket: WebSocket, response: Response) {
            if (!isCurrent(webSocket)) return
            reconnectAttempt = 0
            cancelScheduledReconnect()
            log("socket_open", JSONObject().put("httpCode", response.code))
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
            log("auth_sent")
        }

        override fun onMessage(webSocket: WebSocket, text: String) {
            if (!isCurrent(webSocket)) return
            val json = runCatching { JSONObject(text) }.getOrNull() ?: return
            when {
                json.optString("status") == "ok" -> {
                    log("auth_ok")
                }
                json.optString("status") == "error" -> {
                    log("server_error", JSONObject().put("error", json.optString("error", "认证失败")))
                }
                json.optString("action") == VibeDropActions.Clipboard -> {
                    log("clipboard_received", JSONObject().put("length", json.optString("text").length))
                    applyClipboard(json.optString("text"))
                }
            }
        }

        override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
            if (!isCurrent(webSocket)) return
            log("socket_closed", JSONObject().put("code", code).put("reason", reason))
            socket = null
            scheduleReconnect()
        }

        override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
            if (!isCurrent(webSocket)) return
            log(
                "socket_failure",
                JSONObject()
                    .put("error", t.message ?: t.javaClass.simpleName)
                    .put("httpCode", response?.code ?: JSONObject.NULL)
            )
            socket = null
            scheduleReconnect()
        }

        private fun scheduleReconnect() {
            if (closed) return
            cancelScheduledReconnect()
            rotateEndpoint()
            reconnectAttempt += 1
            val cappedAttempt = min(reconnectAttempt, 6)
            val delayMs = min(60_000L, 1_000L shl (cappedAttempt - 1))
            log("schedule_reconnect", JSONObject().put("attempt", reconnectAttempt).put("delayMillis", delayMs))
            val runnable = Runnable {
                reconnectRunnable = null
                connect()
            }
            reconnectRunnable = runnable
            mainHandler.postDelayed(runnable, delayMs)
        }

        private fun cancelScheduledReconnect() {
            reconnectRunnable?.let { mainHandler.removeCallbacks(it) }
            reconnectRunnable = null
        }

        private fun isCurrent(webSocket: WebSocket): Boolean {
            return !closed && socket === webSocket
        }

        private fun endpointCandidates(): List<String> {
            return listOfNotNull(device.host, device.ip)
                .map { it.trim() }
                .filter { it.isNotBlank() }
                .distinct()
        }

        private fun nextEndpointHost(): String? {
            val candidates = endpointCandidates()
            if (candidates.isEmpty()) return null
            endpointIndex %= candidates.size
            return candidates[endpointIndex]
        }

        private fun rotateEndpoint() {
            val candidates = endpointCandidates()
            if (candidates.size > 1) {
                endpointIndex = (endpointIndex + 1) % candidates.size
            }
        }

        private fun log(event: String, detail: JSONObject = JSONObject()) {
            diagnosticLogStore.append(
                "clipboard-sync",
                event,
                detail
                    .put("deviceId", device.id)
                    .put("deviceName", device.displayName)
                    .put("host", activeHost.ifBlank { device.host ?: "" })
                    .put("configuredHost", device.host ?: JSONObject.NULL)
                    .put("fallbackIp", device.ip ?: JSONObject.NULL)
                    .put("port", device.port ?: JSONObject.NULL)
            )
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
