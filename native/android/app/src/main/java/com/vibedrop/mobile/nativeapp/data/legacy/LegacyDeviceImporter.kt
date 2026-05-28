package com.vibedrop.mobile.nativeapp.data.legacy

import android.content.Context
import com.vibedrop.mobile.nativeapp.data.repository.DeviceRepository
import org.json.JSONArray
import org.json.JSONObject

class LegacyDeviceImporter(
    private val context: Context,
    private val deviceRepository: DeviceRepository
) {
    private val prefs = context.getSharedPreferences("native_migration", Context.MODE_PRIVATE)

    suspend fun importIfNeeded(): LegacyDeviceImportResult {
        val currentDeviceCount = deviceRepository.countDevices()
        if (prefs.getBoolean(MIGRATION_KEY, false) && currentDeviceCount > 0) {
            return LegacyDeviceImportResult(imported = 0, skipped = true, sourceExists = hasLegacyConfig())
        }

        val rawConfig = legacyPrefs().getString(LEGACY_CONFIG_KEY, null)
        if (rawConfig.isNullOrBlank()) {
            prefs.edit().putBoolean(MIGRATION_KEY, true).apply()
            return LegacyDeviceImportResult(imported = 0, skipped = false, sourceExists = false)
        }

        val legacyDevices = runCatching { extractLegacyBackgroundClipboardDevices(rawConfig) }.getOrElse {
            return LegacyDeviceImportResult(imported = 0, skipped = false, sourceExists = true, error = it.message)
        }

        legacyDevices.forEach { device ->
            deviceRepository.saveObservedDesktop(
                id = "legacy-background:${device.id}",
                displayName = device.name,
                host = device.ip,
                ip = device.ip,
                port = device.port,
                pin = device.pin
            )
        }
        prefs.edit().putBoolean(MIGRATION_KEY, true).apply()
        return LegacyDeviceImportResult(imported = legacyDevices.size, skipped = false, sourceExists = true)
    }

    private fun hasLegacyConfig(): Boolean {
        return !legacyPrefs().getString(LEGACY_CONFIG_KEY, null).isNullOrBlank()
    }

    private fun legacyPrefs() =
        context.getSharedPreferences(LEGACY_PREFS_NAME, Context.MODE_PRIVATE)

    private companion object {
        const val MIGRATION_KEY = "background_clipboard_devices_v1_imported"
        const val LEGACY_PREFS_NAME = "vibedrop_background_clipboard"
        const val LEGACY_CONFIG_KEY = "config_json"
    }
}

internal data class LegacyBackgroundClipboardDevice(
    val id: String,
    val name: String,
    val ip: String,
    val port: Int,
    val pin: String
)

internal fun extractLegacyBackgroundClipboardDevices(rawJson: String): List<LegacyBackgroundClipboardDevice> {
    val root = JSONObject(rawJson)
    val devicesJson = root.optJSONArray("devices") ?: JSONArray()
    val devices = mutableListOf<LegacyBackgroundClipboardDevice>()
    for (index in 0 until devicesJson.length()) {
        val item = devicesJson.optJSONObject(index) ?: continue
        val ip = item.optString("ip").trim()
        val pin = item.optString("pin").trim()
        if (ip.isBlank() || pin.isBlank()) continue
        val port = item.optString("port").trim().toIntOrNull()?.takeIf { it > 0 } ?: 9001
        val rawId = item.optString("id").trim()
        val id = sanitizeLegacyDeviceId(rawId.ifBlank { "$ip:$port" })
        devices.add(
            LegacyBackgroundClipboardDevice(
                id = id,
                name = item.optString("name").trim().ifBlank { ip },
                ip = ip,
                port = port,
                pin = pin
            )
        )
    }
    return devices
}

private fun sanitizeLegacyDeviceId(value: String): String {
    return value
        .trim()
        .lowercase()
        .replace(Regex("""[^a-z0-9._:-]+"""), "-")
        .trim('-')
        .ifBlank { "unknown" }
}

data class LegacyDeviceImportResult(
    val imported: Int,
    val skipped: Boolean,
    val sourceExists: Boolean,
    val error: String? = null
)
