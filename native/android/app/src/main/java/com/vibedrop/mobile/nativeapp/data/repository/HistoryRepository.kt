package com.vibedrop.mobile.nativeapp.data.repository

import com.vibedrop.mobile.nativeapp.core.model.DesktopDevice
import com.vibedrop.mobile.nativeapp.data.local.HistoryDao
import com.vibedrop.mobile.nativeapp.data.local.HistoryEntryEntity
import com.vibedrop.mobile.nativeapp.data.local.HistoryItemEntity
import com.vibedrop.mobile.nativeapp.platform.IncomingFileResult
import kotlinx.coroutines.flow.Flow
import java.util.UUID

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
        val entryId = "native-inbound:${result.transferId}"
        val kind = kindFromMime(result.mimeType)
        val label = when (kind) {
            "image" -> "图片"
            "video" -> "视频"
            else -> "文件"
        }
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

    suspend fun importEntry(entry: HistoryEntryEntity) {
        if (historyDao.findEntry(entry.id) == null) {
            historyDao.upsertEntry(entry)
        }
    }

    private fun kindFromMime(mimeType: String): String = when {
        mimeType.startsWith("image/") -> "image"
        mimeType.startsWith("video/") -> "video"
        else -> "file"
    }
}
