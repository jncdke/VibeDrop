package com.vibedrop.mobile

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.os.Handler
import android.os.Looper
import android.util.Log
import androidx.core.content.ContextCompat
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.TimeUnit

private const val BACKGROUND_CLIPBOARD_PREFS_NAME = "vibedrop_background_clipboard"
private const val BACKGROUND_CLIPBOARD_KEY_CONFIG_JSON = "config_json"
private const val BACKGROUND_CLIPBOARD_KEY_FRONTEND_SYNC_GATE_UNTIL_MS = "frontend_sync_gate_until_ms"
private const val BACKGROUND_CLIPBOARD_FRONTEND_SYNC_GATE_MS = 20_000L

data class BackgroundClipboardIdentity(
  val id: String,
  val name: String
)

data class BackgroundClipboardDevice(
  val id: String,
  val name: String,
  val ip: String,
  val port: String,
  val pin: String
)

data class BackgroundClipboardConfig(
  val identity: BackgroundClipboardIdentity,
  val devices: List<BackgroundClipboardDevice>
)

data class BackgroundClipboardConnectionDiagnostics(
  val id: String,
  val name: String,
  val ip: String,
  val port: String,
  val status: String,
  val authenticated: Boolean,
  val reconnectScheduled: Boolean,
  val lastError: String,
  val lastStateAtMs: Long
)

object BackgroundClipboardConfigStore {
  fun save(context: Context, configJson: String) {
    context.getSharedPreferences(BACKGROUND_CLIPBOARD_PREFS_NAME, Context.MODE_PRIVATE)
      .edit()
      .putString(BACKGROUND_CLIPBOARD_KEY_CONFIG_JSON, configJson)
      .apply()
  }

  fun load(context: Context): BackgroundClipboardConfig? {
    val raw = context.getSharedPreferences(BACKGROUND_CLIPBOARD_PREFS_NAME, Context.MODE_PRIVATE)
      .getString(BACKGROUND_CLIPBOARD_KEY_CONFIG_JSON, null)
      ?: return null
    return parse(raw)
  }

  private fun parse(raw: String): BackgroundClipboardConfig? {
    return try {
      val root = JSONObject(raw)
      val identityJson = root.optJSONObject("identity") ?: return null
      val identityId = identityJson.optString("id").trim()
      if (identityId.isEmpty()) return null
      val identity = BackgroundClipboardIdentity(
        id = identityId,
        name = identityJson.optString("name").trim().ifEmpty { "Android 手机" }
      )

      val devicesJson = root.optJSONArray("devices") ?: JSONArray()
      val devices = mutableListOf<BackgroundClipboardDevice>()
      for (index in 0 until devicesJson.length()) {
        val item = devicesJson.optJSONObject(index) ?: continue
        val id = item.optString("id").trim()
        val ip = item.optString("ip").trim()
        val pin = item.optString("pin").trim()
        if (id.isEmpty() || ip.isEmpty() || pin.isEmpty()) {
          continue
        }
        devices.add(
          BackgroundClipboardDevice(
            id = id,
            name = item.optString("name").trim().ifEmpty { "设备" },
            ip = ip,
            port = item.optString("port").trim().ifEmpty { "9001" },
            pin = pin
          )
        )
      }

      BackgroundClipboardConfig(identity = identity, devices = devices)
    } catch (error: Exception) {
      Log.e("VibeDrop.BgClipboard", "解析后台剪贴板配置失败", error)
      null
    }
  }
}

object BackgroundClipboardStartupGate {
  private fun prefs(context: Context) =
    context.getSharedPreferences(BACKGROUND_CLIPBOARD_PREFS_NAME, Context.MODE_PRIVATE)

  fun arm(context: Context, durationMs: Long = BACKGROUND_CLIPBOARD_FRONTEND_SYNC_GATE_MS): Long {
    val untilMs = System.currentTimeMillis() + durationMs
    prefs(context)
      .edit()
      .putLong(BACKGROUND_CLIPBOARD_KEY_FRONTEND_SYNC_GATE_UNTIL_MS, untilMs)
      .apply()
    return untilMs
  }

  fun clear(context: Context) {
    prefs(context)
      .edit()
      .remove(BACKGROUND_CLIPBOARD_KEY_FRONTEND_SYNC_GATE_UNTIL_MS)
      .apply()
  }

  fun remainingMs(context: Context, nowMs: Long = System.currentTimeMillis()): Long {
    val untilMs = prefs(context).getLong(BACKGROUND_CLIPBOARD_KEY_FRONTEND_SYNC_GATE_UNTIL_MS, 0L)
    val remainingMs = untilMs - nowMs
    if (remainingMs <= 0L) {
      if (untilMs != 0L) {
        clear(context)
      }
      return 0L
    }
    return remainingMs
  }
}

object BackgroundClipboardDiagnosticsStore {
  private val lock = Any()
  private val connections = LinkedHashMap<String, BackgroundClipboardConnectionDiagnostics>()
  private var lastReloadAtMs: Long = 0L
  private var lastClipboardAppliedAtMs: Long = 0L
  private var lastClipboardSourceName: String = ""

  fun markReload() {
    synchronized(lock) {
      lastReloadAtMs = System.currentTimeMillis()
    }
  }

  fun markClipboardApplied(sourceDeviceName: String) {
    synchronized(lock) {
      lastClipboardAppliedAtMs = System.currentTimeMillis()
      lastClipboardSourceName = sourceDeviceName
    }
  }

  fun upsertConnection(
    device: BackgroundClipboardDevice,
    status: String,
    authenticated: Boolean = false,
    reconnectScheduled: Boolean = false,
    lastError: String? = null
  ) {
    synchronized(lock) {
      val existing = connections[device.id]
      connections[device.id] = BackgroundClipboardConnectionDiagnostics(
        id = device.id,
        name = device.name,
        ip = device.ip,
        port = device.port,
        status = status,
        authenticated = authenticated,
        reconnectScheduled = reconnectScheduled,
        lastError = lastError ?: existing?.lastError.orEmpty(),
        lastStateAtMs = System.currentTimeMillis()
      )
    }
  }

  fun clearConnection(deviceId: String) {
    synchronized(lock) {
      connections.remove(deviceId)
    }
  }

  fun clearAllConnections() {
    synchronized(lock) {
      connections.clear()
    }
  }

  fun toJson(context: Context): String {
    val gateRemainingMs = BackgroundClipboardStartupGate.remainingMs(context)
    val config = BackgroundClipboardConfigStore.load(context)
    val root = JSONObject()
    synchronized(lock) {
      root.put("gateActive", gateRemainingMs > 0L)
      root.put("gateRemainingMs", gateRemainingMs)
      root.put("lastReloadAtMs", lastReloadAtMs)
      root.put("lastClipboardAppliedAtMs", lastClipboardAppliedAtMs)
      root.put("lastClipboardSourceName", lastClipboardSourceName)

      val configuredDevices = JSONArray()
      config?.devices?.forEach { device ->
        configuredDevices.put(JSONObject().apply {
          put("id", device.id)
          put("name", device.name)
          put("ip", device.ip)
          put("port", device.port)
          put("pinPresent", device.pin.isNotEmpty())
        })
      }
      root.put("configuredDevices", configuredDevices)
      root.put("configuredDeviceCount", config?.devices?.size ?: 0)

      val connectionItems = JSONArray()
      connections.values.forEach { connection ->
        connectionItems.put(JSONObject().apply {
          put("id", connection.id)
          put("name", connection.name)
          put("ip", connection.ip)
          put("port", connection.port)
          put("status", connection.status)
          put("authenticated", connection.authenticated)
          put("reconnectScheduled", connection.reconnectScheduled)
          put("lastError", connection.lastError)
          put("lastStateAtMs", connection.lastStateAtMs)
        })
      }
      root.put("connections", connectionItems)
    }
    return root.toString()
  }
}

class BackgroundClipboardSyncManager(private val context: Context) {

  companion object {
    private const val TAG = "VibeDrop.BgClipboard"
    private const val RECONNECT_BASE_DELAY_MS = 3000L
    private const val RECONNECT_MAX_DELAY_MS = 60_000L
  }

  private val appContext = context.applicationContext
  private val handler = Handler(Looper.getMainLooper())
  private val httpClient = OkHttpClient.Builder()
    .retryOnConnectionFailure(true)
    .pingInterval(15, TimeUnit.SECONDS)
    .build()
  private val connections = LinkedHashMap<String, DeviceConnection>()

  @Volatile
  private var shuttingDown = false

  @Volatile
  private var lastAppliedClipboardText: String? = null

  @Volatile
  private var lastAppliedClipboardAt: Long = 0L

  private var gateReloadScheduled = false
  private val gateReloadRunnable = Runnable {
    gateReloadScheduled = false
    if (!shuttingDown) {
      Log.d(TAG, "后台剪贴板启动门控超时，回退读取持久化配置")
      reloadConfig()
    }
  }

  fun reloadConfig() {
    if (shuttingDown) return
    BackgroundClipboardDiagnosticsStore.markReload()

    val gateRemainingMs = BackgroundClipboardStartupGate.remainingMs(appContext)
    if (gateRemainingMs > 0L) {
      closeAllConnections()
      scheduleGateReload(gateRemainingMs)
      Log.d(TAG, "后台剪贴板启动门控生效，等待前端同步，剩余=${gateRemainingMs}ms")
      return
    }
    cancelGateReload()

    val config = BackgroundClipboardConfigStore.load(appContext)
    if (config == null || config.devices.isEmpty()) {
      closeAllConnections()
      Log.d(TAG, "后台剪贴板同步未配置设备，已清空连接")
      return
    }

    val desiredIds = config.devices.map { it.id }.toSet()
    val staleIds = connections.keys.filter { it !in desiredIds }
    staleIds.forEach { staleId ->
      connections.remove(staleId)?.close()
      BackgroundClipboardDiagnosticsStore.clearConnection(staleId)
    }

    config.devices.forEach { device ->
      val existing = connections[device.id]
      if (existing == null) {
        connections[device.id] = DeviceConnection(config.identity, device).also { it.open() }
      } else {
        existing.update(config.identity, device)
      }
    }
  }

  fun shutdown() {
    shuttingDown = true
    cancelGateReload()
    closeAllConnections()
    httpClient.dispatcher.executorService.shutdown()
  }

  private fun closeAllConnections() {
    connections.values.forEach { it.close() }
    connections.clear()
    BackgroundClipboardDiagnosticsStore.clearAllConnections()
  }

  private fun scheduleGateReload(delayMs: Long) {
    if (shuttingDown || gateReloadScheduled) return
    gateReloadScheduled = true
    handler.postDelayed(gateReloadRunnable, delayMs + 50L)
  }

  private fun cancelGateReload() {
    if (!gateReloadScheduled) return
    handler.removeCallbacks(gateReloadRunnable)
    gateReloadScheduled = false
  }

  private fun applyIncomingClipboard(text: String, sourceDevice: BackgroundClipboardDevice) {
    if (text.isEmpty()) return

    val now = System.currentTimeMillis()
    if (lastAppliedClipboardText == text && now - lastAppliedClipboardAt < 1200L) {
      return
    }
    lastAppliedClipboardText = text
    lastAppliedClipboardAt = now

    try {
      val clipboardManager = ContextCompat.getSystemService(appContext, ClipboardManager::class.java)
      clipboardManager?.setPrimaryClip(ClipData.newPlainText("VibeDrop", text))
      BackgroundClipboardDiagnosticsStore.markClipboardApplied(sourceDevice.name)
      Log.d(TAG, "后台已同步剪贴板: ${sourceDevice.name}, 长度=${text.length}")
    } catch (error: Exception) {
      Log.w(TAG, "后台直接写剪贴板失败，尝试透明 Activity", error)
      try {
        ClipboardFloatingActivity.launchWrite(appContext, text)
      } catch (fallbackError: Exception) {
        Log.e(TAG, "透明 Activity 写剪贴板也失败", fallbackError)
      }
    }
  }

  private inner class DeviceConnection(
    private var identity: BackgroundClipboardIdentity,
    private var device: BackgroundClipboardDevice
  ) : WebSocketListener() {

    private var webSocket: WebSocket? = null
    private var manualClose = false
    private var reconnectScheduled = false
    private var reconnectAttempts = 0

    private val reconnectRunnable = Runnable {
      reconnectScheduled = false
      if (!shuttingDown) {
        open()
      }
    }

    fun update(nextIdentity: BackgroundClipboardIdentity, nextDevice: BackgroundClipboardDevice) {
      val changed = identity != nextIdentity || device != nextDevice
      identity = nextIdentity
      device = nextDevice
      if (changed) {
        reconnectAttempts = 0
        reopen()
      }
    }

    fun open() {
      if (shuttingDown) return

      manualClose = false
      handler.removeCallbacks(reconnectRunnable)
      val request = Request.Builder()
        .url("ws://${device.ip}:${device.port}/ws")
        .build()

      try {
        webSocket = httpClient.newWebSocket(request, this)
        BackgroundClipboardDiagnosticsStore.upsertConnection(device, "connecting")
        Log.d(TAG, "后台剪贴板连接中: ${device.name} (${device.ip}:${device.port})")
      } catch (error: Exception) {
        BackgroundClipboardDiagnosticsStore.upsertConnection(
          device,
          status = "failed",
          lastError = error.message ?: error.javaClass.simpleName
        )
        Log.e(TAG, "启动后台剪贴板连接失败: ${device.name}", error)
        scheduleReconnect()
      }
    }

    fun close() {
      manualClose = true
      handler.removeCallbacks(reconnectRunnable)
      reconnectScheduled = false
      reconnectAttempts = 0
      webSocket?.close(1000, "stop")
      webSocket = null
      BackgroundClipboardDiagnosticsStore.upsertConnection(device, "idle")
    }

    private fun reopen() {
      close()
      open()
    }

    override fun onOpen(webSocket: WebSocket, response: Response) {
      if (this.webSocket !== webSocket) return
      webSocket.send(authPayload())
      BackgroundClipboardDiagnosticsStore.upsertConnection(device, "open")
      Log.d(TAG, "后台剪贴板 WebSocket 已打开: ${device.name}")
    }

    override fun onMessage(webSocket: WebSocket, text: String) {
      if (this.webSocket !== webSocket) return
      try {
        val json = JSONObject(text)
        when (json.optString("action")) {
          "pong" -> return
          "clipboard" -> {
            val clipboardText = json.optString("text")
            if (clipboardText.isNotEmpty()) {
              applyIncomingClipboard(clipboardText, device)
            }
            return
          }
        }

        if (json.optString("status") == "ok") {
          reconnectAttempts = 0
          BackgroundClipboardDiagnosticsStore.upsertConnection(
            device,
            status = "authenticated",
            authenticated = true
          )
          Log.d(TAG, "后台剪贴板认证成功: ${device.name}")
          return
        }

        if (json.optString("status") == "error") {
          Log.w(TAG, "后台剪贴板服务端返回错误: ${json.optString("error")}")
        }
      } catch (error: Exception) {
        Log.w(TAG, "解析后台剪贴板消息失败: $text", error)
      }
    }

    override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
      if (this.webSocket !== webSocket) return
      webSocket.close(code, reason)
    }

    override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
      if (this.webSocket !== webSocket) return
      this.webSocket = null
      BackgroundClipboardDiagnosticsStore.upsertConnection(device, "closed")
      Log.d(TAG, "后台剪贴板连接关闭: ${device.name}, code=$code, reason=$reason")
      if (!manualClose) {
        scheduleReconnect()
      }
    }

    override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
      if (this.webSocket !== webSocket) return
      this.webSocket = null
      BackgroundClipboardDiagnosticsStore.upsertConnection(
        device,
        status = "failed",
        lastError = t.message ?: t.javaClass.simpleName
      )
      Log.w(TAG, "后台剪贴板连接失败: ${device.name}", t)
      if (!manualClose) {
        scheduleReconnect()
      }
    }

    private fun scheduleReconnect() {
      if (shuttingDown || reconnectScheduled) return
      reconnectScheduled = true
      val attempt = reconnectAttempts.coerceAtLeast(0)
      val multiplier = 1L shl attempt.coerceAtMost(5)
      val delayMs = (RECONNECT_BASE_DELAY_MS * multiplier).coerceAtMost(RECONNECT_MAX_DELAY_MS)
      reconnectAttempts = attempt + 1
      BackgroundClipboardDiagnosticsStore.upsertConnection(
        device,
        status = "scheduled_reconnect",
        reconnectScheduled = true
      )
      Log.d(TAG, "后台剪贴板计划重连: ${device.name}, delay=${delayMs}ms, attempt=$reconnectAttempts")
      handler.postDelayed(reconnectRunnable, delayMs)
    }

    private fun authPayload(): String {
      val connectionId = "${identity.id}::clipboard"
      return JSONObject().apply {
        put("action", "auth")
        put("pin", device.pin)
        put("device_id", connectionId)
        put("base_device_id", identity.id)
        put("device_name", identity.name)
        put("device_role", "clipboard_sync")
        put("can_receive_files", false)
        put("receives_clipboard", true)
      }.toString()
    }
  }
}
