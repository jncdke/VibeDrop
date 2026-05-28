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
        .put("senderBaseDeviceId", senderBaseDeviceId.orEmpty())
        .put("senderRole", senderRole.orEmpty())
        .put("senderHost", senderHost.orEmpty())
        .put("senderIp", senderIp.orEmpty())
        .putNullable("senderPort", senderPort)
        .put("sourceDeviceId", senderDeviceId.orEmpty())
        .put("sourceDeviceName", senderName.orEmpty())
        .put("sourceBaseDeviceId", senderBaseDeviceId.orEmpty())
        .put("sourceRole", senderRole.orEmpty())
        .put("sourceHost", senderHost.orEmpty())
        .put("sourceIp", senderIp.orEmpty())
        .putNullable("sourcePort", senderPort)
        .put("receiverDeviceId", receiverDeviceId.orEmpty())
        .put("receiverName", receiverName.orEmpty())
        .put("receiverBaseDeviceId", receiverBaseDeviceId.orEmpty())
        .put("receiverRole", receiverRole.orEmpty())
        .put("receiverHost", receiverHost.orEmpty())
        .put("receiverIp", receiverIp.orEmpty())
        .putNullable("receiverPort", receiverPort)
        .put("target", receiverName.orEmpty())
        .put("targetName", receiverName.orEmpty())
        .put("targetDeviceName", receiverHost?.takeIf { it.isNotBlank() } ?: receiverName.orEmpty())
        .put("targetHost", receiverHost.orEmpty())
        .put("targetBaseDeviceId", receiverBaseDeviceId.orEmpty())
        .put("targetRole", receiverRole.orEmpty())
        .put("targetIp", receiverIp.orEmpty())
        .putNullable("targetPort", receiverPort)
        .put("targetServerId", receiverDeviceId.orEmpty())
        .put("sender_device_id", senderDeviceId.orEmpty())
        .put("sender_name", senderName.orEmpty())
        .put("sender_base_device_id", senderBaseDeviceId.orEmpty())
        .put("sender_role", senderRole.orEmpty())
        .put("sender_host", senderHost.orEmpty())
        .put("sender_ip", senderIp.orEmpty())
        .putNullable("sender_port", senderPort)
        .put("source_device_id", senderDeviceId.orEmpty())
        .put("source_device_name", senderName.orEmpty())
        .put("source_base_device_id", senderBaseDeviceId.orEmpty())
        .put("source_role", senderRole.orEmpty())
        .put("source_host", senderHost.orEmpty())
        .put("source_ip", senderIp.orEmpty())
        .putNullable("source_port", senderPort)
        .put("receiver_device_id", receiverDeviceId.orEmpty())
        .put("receiver_name", receiverName.orEmpty())
        .put("receiver_base_device_id", receiverBaseDeviceId.orEmpty())
        .put("receiver_role", receiverRole.orEmpty())
        .put("receiver_host", receiverHost.orEmpty())
        .put("receiver_ip", receiverIp.orEmpty())
        .putNullable("receiver_port", receiverPort)
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
