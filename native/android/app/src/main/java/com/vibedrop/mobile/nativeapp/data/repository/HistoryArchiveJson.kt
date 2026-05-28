package com.vibedrop.mobile.nativeapp.data.repository

import com.vibedrop.mobile.nativeapp.data.local.HistoryEntryEntity
import com.vibedrop.mobile.nativeapp.data.local.HistoryItemEntity
import org.json.JSONArray
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

data class HistoryEntryWithItems(
    val entry: HistoryEntryEntity,
    val items: List<HistoryItemEntity>
)

internal fun HistoryEntryWithItems.toHistoryJsonObject(): JSONObject {
    return entry.toHistoryJsonObject(items)
}

internal fun HistoryEntryEntity.toHistoryJsonObject(
    items: List<HistoryItemEntity> = emptyList()
): JSONObject {
    val effectiveItemCount = itemCount ?: items.size.takeIf { it > 0 }
    return JSONObject()
        .put("id", id)
        .put("timestamp", historyIsoTimestamp(timestampMillis))
        .put("timestampMillis", timestampMillis)
        .put("text", text.orEmpty())
        .put("kind", kind)
        .put("direction", direction)
        .put("status", status)
        .put("senderDeviceId", senderDeviceId.orEmpty())
        .put("senderName", senderName.orEmpty())
        .put("sourceDeviceId", senderDeviceId.orEmpty())
        .put("sourceDeviceName", senderName.orEmpty())
        .put("receiverDeviceId", receiverDeviceId.orEmpty())
        .put("receiverName", receiverName.orEmpty())
        .put("target", receiverName.orEmpty())
        .put("targetName", receiverName.orEmpty())
        .put("targetDeviceName", receiverName.orEmpty())
        .put("targetServerId", receiverDeviceId.orEmpty())
        .put("sender_device_id", senderDeviceId.orEmpty())
        .put("sender_name", senderName.orEmpty())
        .put("source_device_id", senderDeviceId.orEmpty())
        .put("source_device_name", senderName.orEmpty())
        .put("receiver_device_id", receiverDeviceId.orEmpty())
        .put("receiver_name", receiverName.orEmpty())
        .putNullable("sessionId", sessionId)
        .putNullable("session_id", sessionId)
        .putNullable("itemCount", effectiveItemCount)
        .putNullable("item_count", effectiveItemCount)
        .putNullable("saveTarget", saveTarget)
        .putNullable("save_target", saveTarget)
        .put("items", JSONArray().apply {
            items.sortedBy { it.itemIndex }.forEach { item ->
                put(item.toHistoryJsonObject())
            }
        })
}

internal fun HistoryItemEntity.toHistoryJsonObject(): JSONObject {
    val path = localPath ?: savedPath
    return JSONObject()
        .put("id", id)
        .put("entryId", entryId)
        .put("entry_id", entryId)
        .put("itemIndex", itemIndex)
        .put("item_index", itemIndex)
        .put("kind", kind)
        .putNullable("fileName", fileName)
        .putNullable("file_name", fileName)
        .putNullable("mimeType", mimeType)
        .putNullable("mime_type", mimeType)
        .putNullable("sizeBytes", sizeBytes)
        .putNullable("size_bytes", sizeBytes)
        .putNullable("localPath", localPath)
        .putNullable("local_path", localPath)
        .putNullable("filePath", path)
        .putNullable("file_path", path)
        .putNullable("savedPath", savedPath)
        .putNullable("saved_path", savedPath)
        .putNullable("thumbnailPath", thumbnailPath)
        .putNullable("thumbnail_path", thumbnailPath)
        .putNullable("thumbnailDataUrl", thumbnailDataUrl)
        .putNullable("thumbnail_data_url", thumbnailDataUrl)
        .putNullable("status", status)
        .putNullable("error", error)
}

internal fun historyIsoTimestamp(timestampMillis: Long): String {
    val formatter = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'", Locale.US)
    formatter.timeZone = TimeZone.getTimeZone("UTC")
    return formatter.format(Date(timestampMillis))
}

private fun JSONObject.putNullable(key: String, value: Any?): JSONObject {
    return put(key, value ?: JSONObject.NULL)
}
