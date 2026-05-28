package com.vibedrop.mobile.nativeapp.data.repository

import com.vibedrop.mobile.nativeapp.core.model.DesktopDevice
import com.vibedrop.mobile.nativeapp.data.local.HistoryDao
import com.vibedrop.mobile.nativeapp.data.local.HistoryEntryEntity
import com.vibedrop.mobile.nativeapp.data.local.HistoryItemEntity
import com.vibedrop.mobile.nativeapp.platform.IncomingFileResult
import kotlinx.coroutines.flow.Flow
import org.json.JSONArray
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Date
import java.util.UUID
import java.util.Locale
import java.util.TimeZone

class HistoryRepository(
    private val historyDao: HistoryDao
) {
    fun observeRecent(limit: Int = 120): Flow<List<HistoryEntryEntity>> {
        return historyDao.observeRecent(limit)
    }

    suspend fun loadAllEntries(): List<HistoryEntryEntity> = historyDao.getAllEntries()

    suspend fun countEntries(): Int = historyDao.countEntries()

    suspend fun recordSentText(
        target: DesktopDevice,
        text: String,
        pressEnter: Boolean,
        status: String = "success"
    ) {
        val entry = HistoryEntryEntity(
            id = "native:${UUID.randomUUID()}",
            timestampMillis = System.currentTimeMillis(),
            direction = "mobile_to_desktop",
            kind = "text",
            status = status,
            text = text,
            senderDeviceId = "native_android_preview",
            senderName = "VibeDrop Native Preview",
            receiverDeviceId = target.stableId,
            receiverName = target.displayName,
            sessionId = null,
            itemCount = null,
            saveTarget = if (pressEnter) "type_enter" else "type",
            rawJson = null
        )
        historyDao.upsertEntry(entry)
    }

    suspend fun recordReceivedFile(
        source: DesktopDevice,
        result: IncomingFileResult
    ) {
        if (!result.historySessionId.isNullOrBlank()) {
            recordReceivedSessionFile(source, result)
            return
        }

        recordReceivedFileWithoutSession(source, result)
    }

    suspend fun recordIncomingHistorySession(
        source: DesktopDevice,
        rawJson: String
    ) {
        val payload = runCatching { JSONObject(rawJson) }.getOrNull() ?: return
        val sessionId = payload.firstString("session_id", "sessionId")
        if (sessionId.isBlank()) return

        val entryId = desktopInboundEntryId(sessionId)
        val existing = historyDao.findEntry(entryId)
        val existingItems = historyDao.getItemsForEntry(entryId).associateBy { it.itemIndex }
        val payloadItems = payload.optJSONArray("items") ?: JSONArray()
        val parsedItems = mutableListOf<HistoryItemEntity>()
        for (index in 0 until payloadItems.length()) {
            val item = payloadItems.optJSONObject(index) ?: continue
            val existingItem = existingItems[index]
            parsedItems += HistoryItemEntity(
                id = "$entryId:item:$index",
                entryId = entryId,
                itemIndex = index,
                kind = item.firstString("kind").ifBlank { kindFromMime(item.firstString("mime_type", "mimeType")) },
                fileName = item.firstString("file_name", "fileName").ifBlank { "文件" },
                mimeType = item.firstString("mime_type", "mimeType").ifBlank { "application/octet-stream" },
                sizeBytes = item.optLongOrNull("size_bytes") ?: item.optLongOrNull("sizeBytes"),
                localPath = item.firstString("file_path", "filePath").takeIf { it.isNotBlank() },
                savedPath = existingItem?.savedPath ?: item.firstString("saved_path", "savedPath").takeIf { it.isNotBlank() },
                thumbnailPath = existingItem?.thumbnailPath,
                thumbnailDataUrl = existingItem?.thumbnailDataUrl ?: item.firstString("thumbnail_data_url", "thumbnailDataUrl").takeIf { it.isNotBlank() },
                status = existingItem?.status ?: item.firstString("status").ifBlank { "pending" },
                error = existingItem?.error ?: item.firstString("error").takeIf { it.isNotBlank() }
            )
        }
        val mergedItems = if (parsedItems.isEmpty()) existingItems.values.sortedBy { it.itemIndex } else parsedItems
        val summary = historyItemsSummary(mergedItems)
        val timestamp = payload.firstString("timestamp").let { value ->
            if (value.isBlank()) existing?.timestampMillis ?: System.currentTimeMillis() else parseTimestampMillis(value)
        }
        val entry = HistoryEntryEntity(
            id = entryId,
            timestampMillis = timestamp,
            direction = "desktop_to_mobile",
            kind = payload.firstString("kind").ifBlank { existing?.kind ?: summary.first },
            status = computeSessionStatus(mergedItems).ifBlank { existing?.status ?: "pending" },
            text = payload.firstString("text").ifBlank { existing?.text ?: summary.second },
            senderDeviceId = source.stableId,
            senderName = source.displayName,
            receiverDeviceId = "native_android_preview",
            receiverName = "VibeDrop Native Preview",
            sessionId = sessionId,
            itemCount = payload.optIntOrNull("item_count") ?: payload.optIntOrNull("itemCount") ?: mergedItems.size.coerceAtLeast(1),
            saveTarget = payload.firstString("save_target", "saveTarget").ifBlank { existing?.saveTarget ?: "download" },
            rawJson = rawJson
        )
        historyDao.upsertEntry(entry)
        if (mergedItems.isNotEmpty()) {
            historyDao.upsertItems(mergedItems)
        }
    }

    suspend fun importEntry(entry: HistoryEntryEntity) {
        if (historyDao.findEntry(entry.id) == null) {
            historyDao.upsertEntry(entry)
        }
    }

    suspend fun importArchive(rawJson: String): Int {
        val root = rawJson.trim()
        val array = when {
            root.startsWith("[") -> JSONArray(root)
            else -> JSONObject(root).optJSONArray("history") ?: JSONArray()
        }
        var imported = 0
        for (index in 0 until array.length()) {
            val item = array.optJSONObject(index) ?: continue
            val entry = item.toHistoryEntry(index) ?: continue
            if (historyDao.findEntry(entry.id) == null) {
                historyDao.upsertEntry(entry)
                imported += 1
            }
        }
        return imported
    }

    fun exportArchive(entries: List<HistoryEntryEntity>): String {
        val history = JSONArray()
        entries.forEach { entry ->
            history.put(
                JSONObject()
                    .put("id", entry.id)
                    .put("timestamp", isoTimestamp(entry.timestampMillis))
                    .put("timestampMillis", entry.timestampMillis)
                    .put("text", entry.text.orEmpty())
                    .put("kind", entry.kind)
                    .put("direction", entry.direction)
                    .put("status", entry.status)
                    .put("senderDeviceId", entry.senderDeviceId.orEmpty())
                    .put("senderName", entry.senderName.orEmpty())
                    .put("receiverDeviceId", entry.receiverDeviceId.orEmpty())
                    .put("receiverName", entry.receiverName.orEmpty())
                    .put("targetDeviceName", entry.receiverName.orEmpty())
                    .put("targetServerId", entry.receiverDeviceId.orEmpty())
                    .put("sessionId", entry.sessionId.orEmpty())
                    .put("itemCount", entry.itemCount ?: JSONObject.NULL)
                    .put("saveTarget", entry.saveTarget.orEmpty())
            )
        }
        return JSONObject()
            .put("schemaVersion", 1)
            .put("app", "VibeDrop")
            .put("deviceId", "native_android_preview")
            .put("deviceName", "VibeDrop Native Preview")
            .put("exportedAt", isoTimestamp(System.currentTimeMillis()))
            .put("history", history)
            .toString(2)
    }

    suspend fun clearHistory() {
        historyDao.deleteAllItems()
        historyDao.deleteAllEntries()
    }

    private fun kindFromMime(mimeType: String): String = when {
        mimeType.startsWith("image/") -> "image"
        mimeType.startsWith("video/") -> "video"
        else -> "file"
    }

    private suspend fun recordReceivedSessionFile(
        source: DesktopDevice,
        result: IncomingFileResult
    ) {
        val sessionId = result.historySessionId ?: return
        val entryId = desktopInboundEntryId(sessionId)
        val existing = historyDao.findEntry(entryId)
        if (existing == null) {
            recordReceivedFileWithoutSession(source, result)
            return
        }

        val existingItems = historyDao.getItemsForEntry(entryId).toMutableList()
        val itemIndex = result.historyItemIndex.coerceAtLeast(0)
        val itemKind = kindFromMime(result.mimeType)
        val item = HistoryItemEntity(
            id = "$entryId:item:$itemIndex",
            entryId = entryId,
            itemIndex = itemIndex,
            kind = itemKind,
            fileName = result.fileName,
            mimeType = result.mimeType,
            sizeBytes = result.sizeBytes,
            localPath = result.savedPath,
            savedPath = result.savedPath,
            thumbnailPath = null,
            thumbnailDataUrl = null,
            status = "success",
            error = null
        )

        if (result.isArchive && result.historyItemCount > 1) {
            val itemsByIndex = existingItems.associateBy { it.itemIndex }.toMutableMap()
            for (index in 0 until result.historyItemCount) {
                val current = itemsByIndex[index]
                itemsByIndex[index] = if (index == itemIndex) {
                    item
                } else {
                    (current ?: item.copy(id = "$entryId:item:$index", itemIndex = index))
                        .copy(status = "success", savedPath = result.savedPath)
                }
            }
            existingItems.clear()
            existingItems.addAll(itemsByIndex.values.sortedBy { it.itemIndex })
        } else {
            val index = existingItems.indexOfFirst { it.itemIndex == itemIndex }
            if (index >= 0) existingItems[index] = item else existingItems.add(item)
        }

        val sortedItems = existingItems.sortedBy { it.itemIndex }
        val updated = existing.copy(
            status = computeSessionStatus(sortedItems),
            itemCount = existing.itemCount ?: result.historyItemCount,
            saveTarget = existing.saveTarget ?: result.saveTarget
        )
        historyDao.upsertEntry(updated)
        historyDao.upsertItems(sortedItems)
    }

    private suspend fun recordReceivedFileWithoutSession(
        source: DesktopDevice,
        result: IncomingFileResult
    ) {
        val entryId = "native-inbound:${result.transferId}"
        val kind = kindFromMime(result.mimeType)
        val label = kindLabel(kind)
        val entry = HistoryEntryEntity(
            id = entryId,
            timestampMillis = System.currentTimeMillis(),
            direction = "desktop_to_mobile",
            kind = kind,
            status = "success",
            text = "[$label] ${result.fileName}",
            senderDeviceId = source.stableId,
            senderName = source.displayName,
            receiverDeviceId = "native_android_preview",
            receiverName = "VibeDrop Native Preview",
            sessionId = null,
            itemCount = 1,
            saveTarget = result.saveTarget,
            rawJson = null
        )
        val item = HistoryItemEntity(
            id = "$entryId:item:0",
            entryId = entryId,
            itemIndex = 0,
            kind = kind,
            fileName = result.fileName,
            mimeType = result.mimeType,
            sizeBytes = result.sizeBytes,
            localPath = result.savedPath,
            savedPath = result.savedPath,
            thumbnailPath = null,
            thumbnailDataUrl = null,
            status = "success",
            error = null
        )
        historyDao.upsertEntry(entry)
        historyDao.upsertItems(listOf(item))
    }

    private fun desktopInboundEntryId(sessionId: String): String = "desktop-inbound:$sessionId"

    private fun computeSessionStatus(items: List<HistoryItemEntity>): String {
        if (items.isEmpty()) return "pending"
        if (items.any { it.status == "pending" || it.status.isNullOrBlank() }) return "pending"
        val successCount = items.count { it.status == "success" }
        val failedCount = items.count { it.status == "failed" }
        return when {
            failedCount == 0 -> "success"
            successCount == 0 -> "failed"
            else -> "partial"
        }
    }

    private fun historyItemsSummary(items: List<HistoryItemEntity>): Pair<String, String> {
        if (items.size == 1) {
            val item = items[0]
            val kind = item.kind
            return kind to "[${kindLabel(kind)}] ${item.fileName ?: "文件"}"
        }
        val imageCount = items.count { it.kind == "image" }
        val videoCount = items.count { it.kind == "video" }
        return when {
            imageCount == items.size -> "image" to "[图片] ${items.size} 张"
            videoCount == items.size -> "video" to "[视频] ${items.size} 个"
            imageCount + videoCount == items.size -> "media" to "[媒体] ${items.size} 项"
            else -> "file" to "[文件] ${items.size} 项"
        }
    }

    private fun kindLabel(kind: String): String = when (kind) {
        "image" -> "图片"
        "video" -> "视频"
        "media" -> "媒体"
        else -> "文件"
    }

    private fun JSONObject.toHistoryEntry(index: Int): HistoryEntryEntity? {
        val timestampMillis = optLongOrNull("timestampMillis")
            ?: parseTimestampMillis(firstString("timestamp", "timestamp_iso", "createdAt"))
        val id = firstString("id").ifBlank { "imported:$timestampMillis:$index" }
        val receiverName = firstString("receiverName", "targetDeviceName", "targetName", "target")
        val receiverId = firstString("receiverDeviceId", "targetServerId", "targetId", "serverId")
            .ifBlank { receiverName }
        return HistoryEntryEntity(
            id = id,
            timestampMillis = timestampMillis,
            direction = firstString("direction").ifBlank { "mobile_to_desktop" },
            kind = firstString("kind").ifBlank { "text" },
            status = firstString("status").ifBlank { "success" },
            text = firstString("text"),
            senderDeviceId = firstString("senderDeviceId", "sourceDeviceId").ifBlank { "imported_android" },
            senderName = firstString("senderName", "sourceDeviceName").ifBlank { "导入历史" },
            receiverDeviceId = receiverId.takeIf { it.isNotBlank() },
            receiverName = receiverName.takeIf { it.isNotBlank() },
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

    private fun JSONObject.optLongOrNull(key: String): Long? {
        return if (has(key) && !isNull(key)) optLong(key) else null
    }

    private fun parseTimestampMillis(value: String): Long {
        if (value.isBlank()) return System.currentTimeMillis()
        val formats = listOf(
            "yyyy-MM-dd'T'HH:mm:ss.SSS'Z'",
            "yyyy-MM-dd'T'HH:mm:ss'Z'",
            "yyyy-MM-dd'T'HH:mm:ss.SSSXXX",
            "yyyy-MM-dd'T'HH:mm:ssXXX",
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

    private fun isoTimestamp(timestampMillis: Long): String {
        val formatter = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'", Locale.US)
        formatter.timeZone = TimeZone.getTimeZone("UTC")
        return formatter.format(Date(timestampMillis))
    }
}
