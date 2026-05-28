package com.vibedrop.mobile.nativeapp.data.legacy

import android.content.Context
import com.vibedrop.mobile.nativeapp.data.local.HistoryEntryEntity
import com.vibedrop.mobile.nativeapp.data.repository.DeviceRepository
import com.vibedrop.mobile.nativeapp.data.repository.HistoryRepository
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.TimeZone

class LegacyHistoryImporter(
    private val context: Context,
    private val deviceRepository: DeviceRepository,
    private val historyRepository: HistoryRepository
) {
    private val prefs = context.getSharedPreferences("native_migration", Context.MODE_PRIVATE)

    suspend fun importIfNeeded(): ImportResult {
        if (prefs.getBoolean("history_json_v1_imported", false)) {
            return ImportResult(imported = 0, skipped = true, sourceExists = File(context.filesDir, "history.json").exists())
        }

        val file = File(context.filesDir, "history.json")
        if (!file.exists()) {
            prefs.edit().putBoolean("history_json_v1_imported", true).apply()
            return ImportResult(imported = 0, skipped = false, sourceExists = false)
        }

        val array = runCatching { JSONArray(file.readText()) }.getOrElse {
            prefs.edit().putBoolean("history_json_v1_imported", true).apply()
            return ImportResult(imported = 0, skipped = false, sourceExists = true, error = it.message)
        }

        var imported = 0
        for (index in 0 until array.length()) {
            val item = array.optJSONObject(index) ?: continue
            val entry = item.toHistoryEntry(index) ?: continue
            historyRepository.importEntry(entry)
            importTargetDevice(item)
            imported += 1
        }
        prefs.edit().putBoolean("history_json_v1_imported", true).apply()
        return ImportResult(imported = imported, skipped = false, sourceExists = true)
    }

    private suspend fun importTargetDevice(item: JSONObject) {
        val targetName = item.firstString("targetDeviceName", "targetName", "targetAlias", "target", "targetHost")
        val targetId = item.firstString("targetServerId", "targetId", "serverId")
            .ifBlank { targetName }
        if (targetName.isBlank() || targetId.isBlank()) return
        deviceRepository.saveObservedDesktop(
            id = "legacy:$targetId",
            displayName = targetName,
            host = item.firstString("targetHost", "targetDeviceName", "hostname").ifBlank { targetName },
            ip = null,
            port = null,
            pin = null
        )
    }

    private fun JSONObject.toHistoryEntry(index: Int): HistoryEntryEntity? {
        val timestampText = firstString("timestamp", "timestamp_iso", "createdAt")
        val timestampMillis = parseTimestampMillis(timestampText)
        val targetName = firstString("targetDeviceName", "targetName", "targetAlias", "target", "targetHost")
        val targetId = firstString("targetServerId", "targetId", "serverId").ifBlank { targetName }
        val id = firstString("id").ifBlank { "legacy:$timestampMillis:$index" }
        return HistoryEntryEntity(
            id = id,
            timestampMillis = timestampMillis,
            direction = firstString("direction").ifBlank { "mobile_to_desktop" },
            kind = firstString("kind").ifBlank { "text" },
            status = firstString("status").ifBlank { "success" },
            text = firstString("text"),
            senderDeviceId = firstString("senderDeviceId", "sourceDeviceId").ifBlank { "legacy_android" },
            senderName = firstString("senderName", "sourceDeviceName").ifBlank { "旧 Android 历史" },
            receiverDeviceId = targetId.takeIf { it.isNotBlank() },
            receiverName = targetName.takeIf { it.isNotBlank() },
            sessionId = firstString("sessionId", "session_id").takeIf { it.isNotBlank() },
            itemCount = optIntOrNull("itemCount") ?: optIntOrNull("item_count"),
            saveTarget = firstString("saveTarget", "save_target").takeIf { it.isNotBlank() },
            rawJson = toString()
        )
    }

    private fun JSONObject.firstString(vararg keys: String): String {
        for (key in keys) {
            val value = optString(key, "")
            if (value.isNotBlank() && value != "null") return value
        }
        return ""
    }

    private fun JSONObject.optIntOrNull(key: String): Int? {
        return if (has(key) && !isNull(key)) optInt(key) else null
    }

    private fun parseTimestampMillis(value: String): Long {
        if (value.isBlank()) return System.currentTimeMillis()
        val formats = listOf(
            "yyyy-MM-dd'T'HH:mm:ss.SSS'Z'",
            "yyyy-MM-dd'T'HH:mm:ss'Z'",
            "yyyy-MM-dd HH:mm:ss",
            "yyyy/MM/dd HH:mm:ss"
        )
        for (pattern in formats) {
            val formatter = SimpleDateFormat(pattern, Locale.US)
            if (pattern.endsWith("'Z'")) {
                formatter.timeZone = TimeZone.getTimeZone("UTC")
            }
            val parsed = runCatching { formatter.parse(value)?.time }.getOrNull()
            if (parsed != null) return parsed
        }
        return runCatching { value.toLong() }.getOrDefault(System.currentTimeMillis())
    }
}

data class ImportResult(
    val imported: Int,
    val skipped: Boolean,
    val sourceExists: Boolean,
    val error: String? = null
)
