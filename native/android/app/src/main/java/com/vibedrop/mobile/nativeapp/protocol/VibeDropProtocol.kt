package com.vibedrop.mobile.nativeapp.protocol

import org.json.JSONObject

object VibeDropActions {
    const val Auth = "auth"
    const val Ping = "ping"
    const val Pong = "pong"
    const val Clipboard = "clipboard"
    const val Type = "type"
    const val TypeEnter = "type_enter"
    const val Enter = "enter"
    const val ImageClipboard = "image_clipboard"
    const val IncomingHistorySessionStart = "incoming_history_session_start"
    const val IncomingFileStart = "incoming_file_start"
    const val IncomingFileChunk = "incoming_file_chunk"
    const val IncomingFileComplete = "incoming_file_complete"
    const val IncomingFileSaved = "incoming_file_saved"
    const val IncomingFileError = "incoming_file_error"
}

data class AuthPayload(
    val pin: String,
    val deviceId: String,
    val baseDeviceId: String,
    val deviceName: String,
    val canReceiveFiles: Boolean,
    val receivesClipboard: Boolean,
    val deviceRole: String
) {
    fun toJson(): JSONObject = JSONObject()
        .put("action", VibeDropActions.Auth)
        .put("pin", pin)
        .put("device_id", deviceId)
        .put("base_device_id", baseDeviceId)
        .put("device_name", deviceName)
        .put("can_receive_files", canReceiveFiles)
        .put("receives_clipboard", receivesClipboard)
        .put("device_role", deviceRole)
}

data class TextPayload(
    val action: String,
    val text: String
) {
    fun toJson(): JSONObject = JSONObject()
        .put("action", action)
        .put("text", text)
}

fun parseAction(jsonText: String): String? = runCatching {
    JSONObject(jsonText).optString("action").takeIf { it.isNotBlank() }
}.getOrNull()
