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

object BackgroundClipboardConfigStore {
  private const val PREFS_NAME = "vibedrop_background_clipboard"
  private const val KEY_CONFIG_JSON = "config_json"

  fun save(context: Context, configJson: String) {
    context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
      .edit()
      .putString(KEY_CONFIG_JSON, configJson)
      .apply()
  }

  fun load(context: Context): BackgroundClipboardConfig? {
    val raw = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
      .getString(KEY_CONFIG_JSON, null)
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

class BackgroundClipboardSyncManager(private val context: Context) {

  companion object {
    private const val TAG = "VibeDrop.BgClipboard"
    private const val RECONNECT_DELAY_MS = 3000L
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

  fun reloadConfig() {
    if (shuttingDown) return

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
    closeAllConnections()
    httpClient.dispatcher.executorService.shutdown()
  }

  private fun closeAllConnections() {
    connections.values.forEach { it.close() }
    connections.clear()
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
        Log.d(TAG, "后台剪贴板连接中: ${device.name} (${device.ip}:${device.port})")
      } catch (error: Exception) {
        Log.e(TAG, "启动后台剪贴板连接失败: ${device.name}", error)
        scheduleReconnect()
      }
    }

    fun close() {
      manualClose = true
      handler.removeCallbacks(reconnectRunnable)
      webSocket?.close(1000, "stop")
      webSocket = null
    }

    private fun reopen() {
      close()
      open()
    }

    override fun onOpen(webSocket: WebSocket, response: Response) {
      webSocket.send(authPayload())
      Log.d(TAG, "后台剪贴板 WebSocket 已打开: ${device.name}")
    }

    override fun onMessage(webSocket: WebSocket, text: String) {
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
      webSocket.close(code, reason)
    }

    override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
      this.webSocket = null
      Log.d(TAG, "后台剪贴板连接关闭: ${device.name}, code=$code, reason=$reason")
      if (!manualClose) {
        scheduleReconnect()
      }
    }

    override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
      this.webSocket = null
      Log.w(TAG, "后台剪贴板连接失败: ${device.name}", t)
      if (!manualClose) {
        scheduleReconnect()
      }
    }

    private fun scheduleReconnect() {
      if (shuttingDown || reconnectScheduled) return
      reconnectScheduled = true
      handler.postDelayed(reconnectRunnable, RECONNECT_DELAY_MS)
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
