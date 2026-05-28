package com.vibedrop.mobile.nativeapp.network

import android.os.Handler
import android.os.Looper
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.vibedrop.mobile.nativeapp.core.model.ConnectionSnapshot
import com.vibedrop.mobile.nativeapp.core.model.ConnectionStatus
import com.vibedrop.mobile.nativeapp.core.model.DesktopDevice
import com.vibedrop.mobile.nativeapp.protocol.AuthPayload
import com.vibedrop.mobile.nativeapp.protocol.TextPayload
import com.vibedrop.mobile.nativeapp.protocol.VibeDropActions
import com.vibedrop.mobile.nativeapp.platform.IncomingFileReceiver
import com.vibedrop.mobile.nativeapp.platform.IncomingFileResult
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import org.json.JSONObject
import kotlin.math.min
import java.util.concurrent.TimeUnit

class DesktopConnectionController(
    private val device: DesktopDevice,
    private val clientId: String,
    private val clientName: String,
    private val incomingFileReceiver: IncomingFileReceiver? = null,
    private val onIncomingHistorySession: (String) -> Unit = {},
    private val onIncomingFileSaved: (IncomingFileResult) -> Unit = {}
) {
    private val mainHandler = Handler(Looper.getMainLooper())
    private val client = OkHttpClient.Builder()
        .pingInterval(15, TimeUnit.SECONDS)
        .build()

    private var socket: WebSocket? = null
    @Volatile private var manuallyClosed = false
    @Volatile private var connectionToken = 0L
    private var reconnectAttempt = 0
    private var reconnectRunnable: Runnable? = null

    var connection by mutableStateOf(device.connection)
        private set

    fun connect() {
        if (connection.status == ConnectionStatus.Connected || connection.status == ConnectionStatus.Connecting) {
            return
        }
        manuallyClosed = false
        connectInternal()
    }

    private fun connectInternal() {
        cancelScheduledReconnect()
        val pin = device.pin
        if (pin.isNullOrBlank()) {
            update(ConnectionSnapshot(ConnectionStatus.Error, "缺少 PIN"))
            return
        }

        update(ConnectionSnapshot(ConnectionStatus.Connecting))
        val request = Request.Builder()
            .url("ws://${device.host}:${device.port}/ws")
            .build()

        val token = ++connectionToken
        socket = client.newWebSocket(
            request,
            object : WebSocketListener() {
                override fun onOpen(webSocket: WebSocket, response: Response) {
                    if (!isCurrent(webSocket, token)) return
                    webSocket.send(
                        AuthPayload(
                            pin = pin,
                            deviceId = clientId,
                            baseDeviceId = clientId,
                            deviceName = clientName,
                            canReceiveFiles = true,
                            receivesClipboard = false,
                            deviceRole = "primary"
                        ).toJson().toString()
                    )
                }

                override fun onMessage(webSocket: WebSocket, text: String) {
                    if (!isCurrent(webSocket, token)) return
                    handleMessage(webSocket, text)
                }

                override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
                    if (!isCurrent(webSocket, token)) return
                    webSocket.close(code, reason)
                    update(
                        ConnectionSnapshot(
                            ConnectionStatus.Connecting,
                            reason.takeIf { it.isNotBlank() } ?: "连接关闭",
                            reconnectAttempt
                        )
                    )
                }

                override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                    if (!isCurrent(webSocket, token)) return
                    scheduleReconnect(reason.takeIf { it.isNotBlank() } ?: "连接断开")
                }

                override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                    if (!isCurrent(webSocket, token)) return
                    scheduleReconnect(t.message ?: "连接失败")
                }
            }
        )
    }

    fun sendText(text: String, pressEnter: Boolean): Boolean {
        if (!connection.canSend) return false
        val action = if (pressEnter) VibeDropActions.TypeEnter else VibeDropActions.Type
        return socket?.send(TextPayload(action, text).toJson().toString()) == true
    }

    fun sendEnter(): Boolean {
        if (!connection.canSend) return false
        return socket?.send(JSONObject().put("action", VibeDropActions.Enter).toString()) == true
    }

    fun sendImageClipboard(
        fileName: String,
        mimeType: String,
        imageBase64: String
    ): Boolean {
        if (!connection.canSend) return false
        return socket?.send(
            JSONObject()
                .put("action", VibeDropActions.ImageClipboard)
                .put("file_name", fileName)
                .put("mime_type", mimeType)
                .put("image_base64", imageBase64)
                .toString()
        ) == true
    }

    fun sendIncomingFileStart(
        transferId: String,
        fileName: String,
        mimeType: String,
        sizeBytes: Long
    ): Boolean {
        if (!connection.canSend) return false
        return socket?.send(
            JSONObject()
                .put("action", VibeDropActions.IncomingFileStart)
                .put("transfer_id", transferId)
                .put("file_name", fileName)
                .put("mime_type", mimeType)
                .put("size_bytes", sizeBytes)
                .toString()
        ) == true
    }

    fun sendIncomingFileChunk(
        transferId: String,
        chunkBase64: String
    ): Boolean {
        if (!connection.canSend) return false
        return socket?.send(
            JSONObject()
                .put("action", VibeDropActions.IncomingFileChunk)
                .put("transfer_id", transferId)
                .put("chunk_base64", chunkBase64)
                .toString()
        ) == true
    }

    fun sendIncomingFileComplete(transferId: String): Boolean {
        if (!connection.canSend) return false
        return socket?.send(
            JSONObject()
                .put("action", VibeDropActions.IncomingFileComplete)
                .put("transfer_id", transferId)
                .toString()
        ) == true
    }

    fun close() {
        manuallyClosed = true
        connectionToken += 1
        cancelScheduledReconnect()
        socket?.close(1000, "dispose")
        socket = null
        update(ConnectionSnapshot(ConnectionStatus.Disconnected))
    }

    private fun handleMessage(webSocket: WebSocket, text: String) {
        val objectValue = runCatching { JSONObject(text) }.getOrNull() ?: return
        val status = objectValue.optString("status")
        val action = objectValue.optString("action")
        when {
            status == "ok" -> {
                reconnectAttempt = 0
                update(ConnectionSnapshot(ConnectionStatus.Connected))
            }
            status == "error" -> update(
                ConnectionSnapshot(
                    ConnectionStatus.Error,
                    objectValue.optString("error", "认证或发送失败")
                )
            )
            action == VibeDropActions.Pong -> Unit
            action == VibeDropActions.IncomingHistorySessionStart -> onIncomingHistorySession(objectValue.toString())
            action == VibeDropActions.IncomingFileStart -> handleIncomingFileMessage(webSocket, objectValue)
            action == VibeDropActions.IncomingFileChunk -> handleIncomingFileMessage(webSocket, objectValue)
            action == VibeDropActions.IncomingFileComplete -> handleIncomingFileMessage(webSocket, objectValue)
        }
    }

    private fun handleIncomingFileMessage(webSocket: WebSocket, payload: JSONObject) {
        val receiver = incomingFileReceiver ?: return
        val transferId = payload.optString("transfer_id")
        try {
            when (payload.optString("action")) {
                VibeDropActions.IncomingFileStart -> receiver.begin(payload)
                VibeDropActions.IncomingFileChunk -> receiver.append(payload)
                VibeDropActions.IncomingFileComplete -> {
                    val result = receiver.complete(payload)
                    webSocket.send(
                        JSONObject()
                            .put("action", VibeDropActions.IncomingFileSaved)
                            .put("transfer_id", result.transferId)
                            .put("saved_path", result.savedPath)
                            .toString()
                    )
                    onIncomingFileSaved(result)
                }
            }
        } catch (error: Exception) {
            receiver.cancel(transferId)
            if (transferId.isNotBlank()) {
                webSocket.send(
                    JSONObject()
                        .put("action", VibeDropActions.IncomingFileError)
                        .put("transfer_id", transferId)
                        .put("error", error.message ?: "接收失败")
                        .toString()
                )
            }
        }
    }

    private fun scheduleReconnect(reason: String) {
        if (manuallyClosed) {
            update(ConnectionSnapshot(ConnectionStatus.Disconnected, reason))
            return
        }
        reconnectAttempt += 1
        val delayMillis = reconnectDelayMillis(reconnectAttempt)
        update(
            ConnectionSnapshot(
                status = ConnectionStatus.Connecting,
                lastError = "重连中：$reason",
                reconnectAttempt = reconnectAttempt
            )
        )
        cancelScheduledReconnect()
        val runnable = Runnable {
            if (!manuallyClosed) {
                connectInternal()
            }
        }
        reconnectRunnable = runnable
        mainHandler.postDelayed(runnable, delayMillis)
    }

    private fun cancelScheduledReconnect() {
        reconnectRunnable?.let { mainHandler.removeCallbacks(it) }
        reconnectRunnable = null
    }

    private fun reconnectDelayMillis(attempt: Int): Long {
        val cappedAttempt = min(attempt, 6)
        return 1_000L shl (cappedAttempt - 1)
    }

    private fun isCurrent(webSocket: WebSocket, token: Long): Boolean {
        return token == connectionToken && socket === webSocket
    }

    private fun update(snapshot: ConnectionSnapshot) {
        mainHandler.post {
            connection = snapshot
        }
    }
}
