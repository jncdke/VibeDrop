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
import com.vibedrop.mobile.nativeapp.platform.DiagnosticLogStore
import com.vibedrop.mobile.nativeapp.platform.IncomingFileReceiver
import com.vibedrop.mobile.nativeapp.platform.IncomingFileResult
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.withTimeout
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import org.json.JSONObject
import kotlin.math.min
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit

data class IncomingFileAck(
    val transferId: String,
    val savedPath: String?
)

class DesktopConnectionController(
    private val device: DesktopDevice,
    private val clientId: String,
    private val clientName: String,
    private val incomingFileReceiver: IncomingFileReceiver? = null,
    private val diagnosticLogStore: DiagnosticLogStore? = null,
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
    private val pendingIncomingFileAcks = ConcurrentHashMap<String, CompletableDeferred<IncomingFileAck>>()

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
            log("missing_pin")
            update(ConnectionSnapshot(ConnectionStatus.Error, "缺少 PIN"))
            return
        }

        log("connect_start", endpointDetail())
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
                    log("socket_open", endpointDetail().put("httpCode", response.code))
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
                    log("auth_sent", endpointDetail())
                }

                override fun onMessage(webSocket: WebSocket, text: String) {
                    if (!isCurrent(webSocket, token)) return
                    handleMessage(webSocket, text)
                }

                override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
                    if (!isCurrent(webSocket, token)) return
                    log("socket_closing", endpointDetail().put("code", code).put("reason", reason))
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
                    log("socket_closed", endpointDetail().put("code", code).put("reason", reason))
                    scheduleReconnect(reason.takeIf { it.isNotBlank() } ?: "连接断开")
                }

                override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                    if (!isCurrent(webSocket, token)) return
                    log(
                        "socket_failure",
                        endpointDetail()
                            .put("message", t.message ?: t.javaClass.simpleName)
                            .put("httpCode", response?.code ?: JSONObject.NULL)
                    )
                    scheduleReconnect(t.message ?: "连接失败")
                }
            }
        )
    }

    fun sendText(text: String, pressEnter: Boolean): Boolean {
        if (!connection.canSend) return false
        val action = if (pressEnter) VibeDropActions.TypeEnter else VibeDropActions.Type
        val accepted = socket?.send(TextPayload(action, text).toJson().toString()) == true
        log("send_text", endpointDetail().put("action", action).put("length", text.length).put("accepted", accepted))
        return accepted
    }

    fun sendEnter(): Boolean {
        if (!connection.canSend) return false
        val accepted = socket?.send(JSONObject().put("action", VibeDropActions.Enter).toString()) == true
        log("send_enter", endpointDetail().put("accepted", accepted))
        return accepted
    }

    fun sendImageClipboard(
        fileName: String,
        mimeType: String,
        imageBase64: String
    ): Boolean {
        if (!connection.canSend) return false
        val accepted = socket?.send(
            JSONObject()
                .put("action", VibeDropActions.ImageClipboard)
                .put("file_name", fileName)
                .put("mime_type", mimeType)
                .put("image_base64", imageBase64)
                .toString()
        ) == true
        log("send_image_clipboard", endpointDetail().put("fileName", fileName).put("mimeType", mimeType).put("accepted", accepted))
        return accepted
    }

    fun sendIncomingFileStart(
        transferId: String,
        fileName: String,
        mimeType: String,
        sizeBytes: Long
    ): Boolean {
        if (!connection.canSend) return false
        val accepted = socket?.send(
            JSONObject()
                .put("action", VibeDropActions.IncomingFileStart)
                .put("transfer_id", transferId)
                .put("file_name", fileName)
                .put("mime_type", mimeType)
                .put("size_bytes", sizeBytes)
                .toString()
        ) == true
        log("send_file_start", endpointDetail().put("transferId", transferId).put("fileName", fileName).put("sizeBytes", sizeBytes).put("accepted", accepted))
        return accepted
    }

    fun sendIncomingFileChunk(
        transferId: String,
        chunkBase64: String
    ): Boolean {
        if (!connection.canSend) return false
        val accepted = socket?.send(
            JSONObject()
                .put("action", VibeDropActions.IncomingFileChunk)
                .put("transfer_id", transferId)
                .put("chunk_base64", chunkBase64)
                .toString()
        ) == true
        if (!accepted) log("send_file_chunk_rejected", endpointDetail().put("transferId", transferId))
        return accepted
    }

    fun sendIncomingFileComplete(transferId: String): Boolean {
        if (!connection.canSend) return false
        val accepted = socket?.send(
            JSONObject()
                .put("action", VibeDropActions.IncomingFileComplete)
                .put("transfer_id", transferId)
                .toString()
        ) == true
        log("send_file_complete", endpointDetail().put("transferId", transferId).put("accepted", accepted))
        return accepted
    }

    fun trackIncomingFileAck(transferId: String) {
        pendingIncomingFileAcks[transferId] = CompletableDeferred()
    }

    suspend fun awaitIncomingFileAck(
        transferId: String,
        timeoutMillis: Long = 30_000L
    ): IncomingFileAck {
        val deferred = pendingIncomingFileAcks[transferId]
            ?: CompletableDeferred<IncomingFileAck>().also { pendingIncomingFileAcks[transferId] = it }
        return try {
            withTimeout(timeoutMillis) {
                deferred.await()
            }
        } finally {
            pendingIncomingFileAcks.remove(transferId)
        }
    }

    fun cancelIncomingFileAck(transferId: String) {
        pendingIncomingFileAcks.remove(transferId)?.cancel()
    }

    fun close() {
        manuallyClosed = true
        log("manual_close", endpointDetail())
        connectionToken += 1
        cancelScheduledReconnect()
        failPendingIncomingFileAcks("连接已关闭")
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
                log("auth_ok", endpointDetail())
                update(ConnectionSnapshot(ConnectionStatus.Connected))
            }
            status == "error" -> {
                val error = objectValue.optString("error", "认证或发送失败")
                log("server_error", endpointDetail().put("error", error))
                update(
                    ConnectionSnapshot(
                        ConnectionStatus.Error,
                        error
                    )
                )
            }
            action == VibeDropActions.Pong -> Unit
            action == VibeDropActions.IncomingHistorySessionStart -> onIncomingHistorySession(objectValue.toString())
            action == VibeDropActions.IncomingFileStart -> handleIncomingFileMessage(webSocket, objectValue)
            action == VibeDropActions.IncomingFileChunk -> handleIncomingFileMessage(webSocket, objectValue)
            action == VibeDropActions.IncomingFileComplete -> handleIncomingFileMessage(webSocket, objectValue)
            action == VibeDropActions.IncomingFileSaved -> completeIncomingFileAck(objectValue)
            action == VibeDropActions.IncomingFileError -> failIncomingFileAck(objectValue)
        }
    }

    private fun completeIncomingFileAck(payload: JSONObject) {
        val transferId = payload.optString("transfer_id")
        if (transferId.isBlank()) return
        val ack = IncomingFileAck(
            transferId = transferId,
            savedPath = payload.optString("saved_path").takeIf { it.isNotBlank() }
        )
        pendingIncomingFileAcks.remove(transferId)?.complete(ack)
        log("send_file_saved", endpointDetail().put("transferId", transferId))
    }

    private fun failIncomingFileAck(payload: JSONObject) {
        val transferId = payload.optString("transfer_id")
        if (transferId.isBlank()) return
        val message = payload.optString("error").ifBlank { "Mac 保存文件失败" }
        pendingIncomingFileAcks.remove(transferId)?.completeExceptionally(IllegalStateException(message))
        log("send_file_error", endpointDetail().put("transferId", transferId).put("error", message))
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
                    log("incoming_file_saved", endpointDetail().put("transferId", result.transferId).put("savedPath", result.savedPath))
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
            log("incoming_file_error", endpointDetail().put("transferId", transferId).put("error", error.message ?: "接收失败"))
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
        failPendingIncomingFileAcks(reason)
        reconnectAttempt += 1
        val delayMillis = reconnectDelayMillis(reconnectAttempt)
        log(
            "schedule_reconnect",
            endpointDetail()
                .put("reason", reason)
                .put("attempt", reconnectAttempt)
                .put("delayMillis", delayMillis)
        )
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

    private fun failPendingIncomingFileAcks(reason: String) {
        val error = IllegalStateException(reason)
        pendingIncomingFileAcks.values.forEach { deferred ->
            deferred.completeExceptionally(error)
        }
        pendingIncomingFileAcks.clear()
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

    private fun endpointDetail(): JSONObject {
        return JSONObject()
            .put("deviceId", device.id)
            .put("deviceName", device.displayName)
            .put("host", device.host ?: JSONObject.NULL)
            .put("port", device.port ?: JSONObject.NULL)
    }

    private fun log(event: String, detail: JSONObject = JSONObject()) {
        diagnosticLogStore?.append("foreground-ws", event, detail)
    }
}
