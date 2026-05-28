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
import java.util.concurrent.TimeUnit

class DesktopConnectionController(
    private val device: DesktopDevice,
    private val clientId: String,
    private val clientName: String,
    private val incomingFileReceiver: IncomingFileReceiver? = null,
    private val onIncomingFileSaved: (IncomingFileResult) -> Unit = {}
) {
    private val mainHandler = Handler(Looper.getMainLooper())
    private val client = OkHttpClient.Builder()
        .pingInterval(15, TimeUnit.SECONDS)
        .build()

    private var socket: WebSocket? = null

    var connection by mutableStateOf(device.connection)
        private set

    fun connect() {
        if (connection.status == ConnectionStatus.Connected || connection.status == ConnectionStatus.Connecting) {
            return
        }
        val pin = device.pin
        if (pin.isNullOrBlank()) {
            update(ConnectionSnapshot(ConnectionStatus.Error, "缺少 PIN"))
            return
        }

        update(ConnectionSnapshot(ConnectionStatus.Connecting))
        val request = Request.Builder()
            .url("ws://${device.host}:${device.port}/ws")
            .build()

        socket = client.newWebSocket(
            request,
            object : WebSocketListener() {
                override fun onOpen(webSocket: WebSocket, response: Response) {
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
                    handleMessage(webSocket, text)
                }

                override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
                    webSocket.close(code, reason)
                    update(ConnectionSnapshot(ConnectionStatus.Disconnected, reason.takeIf { it.isNotBlank() }))
                }

                override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                    update(ConnectionSnapshot(ConnectionStatus.Disconnected, reason.takeIf { it.isNotBlank() }))
                }

                override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                    update(ConnectionSnapshot(ConnectionStatus.Error, t.message ?: "连接失败"))
                }
            }
        )
    }

    fun sendText(text: String, pressEnter: Boolean): Boolean {
        val action = if (pressEnter) VibeDropActions.TypeEnter else VibeDropActions.Type
        return socket?.send(TextPayload(action, text).toJson().toString()) == true
    }

    fun sendEnter(): Boolean {
        return socket?.send(JSONObject().put("action", VibeDropActions.Enter).toString()) == true
    }

    fun sendImageClipboard(
        fileName: String,
        mimeType: String,
        imageBase64: String
    ): Boolean {
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
        return socket?.send(
            JSONObject()
                .put("action", VibeDropActions.IncomingFileChunk)
                .put("transfer_id", transferId)
                .put("chunk_base64", chunkBase64)
                .toString()
        ) == true
    }

    fun sendIncomingFileComplete(transferId: String): Boolean {
        return socket?.send(
            JSONObject()
                .put("action", VibeDropActions.IncomingFileComplete)
                .put("transfer_id", transferId)
                .toString()
        ) == true
    }

    fun close() {
        socket?.close(1000, "dispose")
        socket = null
        update(ConnectionSnapshot(ConnectionStatus.Disconnected))
    }

    private fun handleMessage(webSocket: WebSocket, text: String) {
        val objectValue = runCatching { JSONObject(text) }.getOrNull() ?: return
        val status = objectValue.optString("status")
        val action = objectValue.optString("action")
        when {
            status == "ok" -> update(ConnectionSnapshot(ConnectionStatus.Connected))
            status == "error" -> update(
                ConnectionSnapshot(
                    ConnectionStatus.Error,
                    objectValue.optString("error", "认证或发送失败")
                )
            )
            action == VibeDropActions.Pong -> Unit
            action == VibeDropActions.IncomingHistorySessionStart -> Unit
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

    private fun update(snapshot: ConnectionSnapshot) {
        mainHandler.post {
            connection = snapshot
        }
    }
}
