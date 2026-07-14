package com.vibedrop.mobile.nativeapp.data.repository

import android.content.Context
import com.vibedrop.mobile.nativeapp.platform.AndroidDeviceIdentity
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.TimeUnit

class HomeVaultRepository(
    context: Context,
    private val identity: AndroidDeviceIdentity
) {
    private val prefs = context.getSharedPreferences("home_vault", Context.MODE_PRIVATE)
    private val client = OkHttpClient.Builder()
        .connectTimeout(8, TimeUnit.SECONDS)
        .readTimeout(120, TimeUnit.SECONDS)
        .writeTimeout(30, TimeUnit.SECONDS)
        .build()

    fun loadEndpoint(): String {
        return prefs.getString("endpoint", DEFAULT_ENDPOINT) ?: DEFAULT_ENDPOINT
    }

    fun saveEndpoint(endpoint: String) {
        prefs.edit().putString("endpoint", endpoint.trim()).apply()
    }

    fun syncHistory(
        endpoint: String,
        entries: List<HistoryEntryWithItems>
    ): HomeVaultSyncResult {
        val cleanEndpoint = endpoint.trim().ifBlank { DEFAULT_ENDPOINT }
        val url = normalizeEndpoint(cleanEndpoint)
        val payload = buildPayload(entries)
        val request = Request.Builder()
            .url(url)
            .post(payload.toString().toRequestBody("application/json".toMediaType()))
            .build()
        val response = client.newCall(request).execute()
        val body = response.body?.string().orEmpty()
        if (!response.isSuccessful) {
            throw IllegalStateException("HTTP ${response.code}: ${body.take(160)}")
        }
        val json = JSONObject(body)
        val syncReport = json.optJSONObject("syncReport")
        return HomeVaultSyncResult(
            uploaded = entries.size,
            vaultTotal = syncReport?.optInt("totalEntryCountInDb")
                ?: json.optInt("historyCount", 0),
            savedPath = json.optString("savedPath"),
            rawMessage = body
        )
    }

    private fun buildPayload(entries: List<HistoryEntryWithItems>): JSONObject {
        val history = JSONArray()
        entries.forEach { entry ->
            history.put(entry.toHistoryJsonObject())
        }
        return JSONObject()
            .put("schemaVersion", 1)
            .put("app", "VibeDrop")
            .put("deviceId", identity.deviceId)
            .put("deviceName", identity.deviceName)
            .put("exportedAt", historyIsoTimestamp(System.currentTimeMillis()))
            .put("history", history)
    }

    private fun normalizeEndpoint(endpoint: String): String {
        val trimmed = endpoint.trimEnd('/')
        return if (trimmed.endsWith("/api/android-history")) {
            trimmed
        } else {
            "$trimmed/api/android-history"
        }
    }

    companion object {
        const val DEFAULT_ENDPOINT = "http://192.168.3.2:8788"
    }
}

data class HomeVaultSyncResult(
    val uploaded: Int,
    val vaultTotal: Int,
    val savedPath: String,
    val rawMessage: String
)
