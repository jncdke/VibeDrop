package com.vibedrop.mobile.nativeapp.data.legacy

import android.content.Context
import com.vibedrop.mobile.nativeapp.data.repository.DeviceRepository
import com.vibedrop.mobile.nativeapp.data.repository.HistoryRepository
import com.vibedrop.mobile.nativeapp.data.repository.extractHistoryArchiveEntries
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

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

        val rawText = file.readText()
        val array = runCatching { extractLegacyHistoryEntries(rawText) }.getOrElse {
            return ImportResult(imported = 0, skipped = false, sourceExists = true, error = it.message)
        }

        val imported = runCatching { historyRepository.importArchive(rawText) }.getOrElse {
            return ImportResult(imported = 0, skipped = false, sourceExists = true, error = it.message)
        }
        for (index in 0 until array.length()) {
            val item = array.optJSONObject(index) ?: continue
            importTargetDevice(item)
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

    private fun JSONObject.firstString(vararg keys: String): String {
        for (key in keys) {
            val value = optString(key, "")
            if (value.isNotBlank() && value != "null") return value
        }
        return ""
    }
}

internal fun extractLegacyHistoryEntries(rawText: String): JSONArray {
    return extractHistoryArchiveEntries(rawText)
}

data class ImportResult(
    val imported: Int,
    val skipped: Boolean,
    val sourceExists: Boolean,
    val error: String? = null
)
