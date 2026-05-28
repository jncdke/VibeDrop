package com.vibedrop.mobile.nativeapp.platform

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

data class DiagnosticLogEvent(
    val timestampMillis: Long,
    val scope: String,
    val event: String,
    val detail: String
) {
    val label: String
        get() = "${formatDiagnosticTime(timestampMillis)} · $scope · $event"
}

class DiagnosticLogStore(context: Context) {
    private val file = File(context.filesDir, "diagnostics/events.jsonl")
    private val lock = Any()

    fun append(scope: String, event: String, detail: JSONObject = JSONObject()) {
        val line = JSONObject()
            .put("timestampMillis", System.currentTimeMillis())
            .put("timestamp", isoTimestamp(System.currentTimeMillis()))
            .put("scope", scope.take(64))
            .put("event", event.take(96))
            .put("detail", detail)
            .toString()
        synchronized(lock) {
            file.parentFile?.mkdirs()
            file.appendText(line + "\n")
            trimIfNeeded()
        }
    }

    fun recent(limit: Int = 80): List<DiagnosticLogEvent> {
        synchronized(lock) {
            if (!file.exists()) return emptyList()
            return file.readLines()
                .takeLast(limit)
                .mapNotNull { line ->
                    val json = runCatching { JSONObject(line) }.getOrNull() ?: return@mapNotNull null
                    DiagnosticLogEvent(
                        timestampMillis = json.optLong("timestampMillis"),
                        scope = json.optString("scope"),
                        event = json.optString("event"),
                        detail = json.optJSONObject("detail")?.toString() ?: "{}"
                    )
                }
                .asReversed()
        }
    }

    fun exportSnapshot(
        deviceName: String,
        deviceId: String,
        network: AndroidNetworkDiagnosticsSnapshot?,
        background: BackgroundRunDiagnosticsSnapshot?
    ): String {
        val events = JSONArray()
        recent(300).asReversed().forEach { event ->
            events.put(
                JSONObject()
                    .put("timestampMillis", event.timestampMillis)
                    .put("timestamp", isoTimestamp(event.timestampMillis))
                    .put("scope", event.scope)
                    .put("event", event.event)
                    .put("detail", runCatching { JSONObject(event.detail) }.getOrElse { JSONObject() })
            )
        }
        return JSONObject()
            .put("schemaVersion", 1)
            .put("app", "VibeDrop Native Android")
            .put("exportedAt", isoTimestamp(System.currentTimeMillis()))
            .put("deviceName", deviceName)
            .put("deviceId", deviceId)
            .put("network", network?.toJson() ?: JSONObject.NULL)
            .put("background", background?.toJson() ?: JSONObject.NULL)
            .put("events", events)
            .toString(2)
    }

    private fun trimIfNeeded() {
        if (!file.exists() || file.length() < MAX_BYTES) return
        val lines = file.readLines().takeLast(MAX_LINES)
        file.writeText(lines.joinToString(separator = "\n", postfix = "\n"))
    }

    private fun AndroidNetworkDiagnosticsSnapshot.toJson(): JSONObject {
        val addresses = JSONArray()
        localAddresses.forEach { address ->
            addresses.put(
                JSONObject()
                    .put("interfaceName", address.interfaceName)
                    .put("hostAddress", address.hostAddress)
            )
        }
        return JSONObject()
            .put("activeTransports", JSONArray(activeTransports))
            .put("hasInternetCapability", hasInternetCapability)
            .put("isValidated", isValidated)
            .put("localAddresses", addresses)
    }

    private fun BackgroundRunDiagnosticsSnapshot.toJson(): JSONObject {
        return JSONObject()
            .put("notificationsEnabled", notificationsEnabled)
            .put("ignoresBatteryOptimizations", ignoresBatteryOptimizations)
            .put("autoRevokeWhitelisted", autoRevokeWhitelisted ?: JSONObject.NULL)
            .put("batteryCheckAvailable", batteryCheckAvailable)
            .put("issueCount", issueCount)
    }

    private companion object {
        private const val MAX_BYTES = 512 * 1024
        private const val MAX_LINES = 500
    }
}

private fun isoTimestamp(timestampMillis: Long): String {
    val formatter = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'", Locale.US)
    formatter.timeZone = TimeZone.getTimeZone("UTC")
    return formatter.format(Date(timestampMillis))
}

private fun formatDiagnosticTime(timestampMillis: Long): String {
    return SimpleDateFormat("MM/dd HH:mm:ss", Locale.CHINA).format(Date(timestampMillis))
}
