package com.vibedrop.mobile.nativeapp.data.repository

import com.vibedrop.mobile.nativeapp.core.model.DesktopDevice
import com.vibedrop.mobile.nativeapp.data.local.HistoryDao
import com.vibedrop.mobile.nativeapp.data.local.HistoryEntryEntity
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

    suspend fun importEntry(entry: HistoryEntryEntity) {
        if (historyDao.findEntry(entry.id) == null) {
            historyDao.upsertEntry(entry)
        }
    }
}
