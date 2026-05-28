package com.vibedrop.mobile.nativeapp.data.local

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(
    tableName = "history_entries",
    indices = [
        Index(value = ["timestampMillis"]),
        Index(value = ["senderDeviceId"]),
        Index(value = ["receiverDeviceId"]),
        Index(value = ["kind"]),
        Index(value = ["status"])
    ]
)
data class HistoryEntryEntity(
    @PrimaryKey val id: String,
    val timestampMillis: Long,
    val direction: String,
    val kind: String,
    val status: String,
    val text: String?,
    val senderDeviceId: String?,
    val senderName: String?,
    val receiverDeviceId: String?,
    val receiverName: String?,
    val sessionId: String?,
    val itemCount: Int?,
    val saveTarget: String?,
    val rawJson: String?
)
