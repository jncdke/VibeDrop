package com.vibedrop.mobile.nativeapp.data.local

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(
    tableName = "history_entries",
    indices = [
        Index(value = ["timestampMillis"]),
        Index(value = ["senderDeviceId"]),
        Index(value = ["senderBaseDeviceId"]),
        Index(value = ["receiverDeviceId"]),
        Index(value = ["receiverBaseDeviceId"]),
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
    val senderBaseDeviceId: String? = null,
    val senderRole: String? = null,
    val senderHost: String? = null,
    val senderIp: String? = null,
    val senderPort: Int? = null,
    val receiverDeviceId: String?,
    val receiverName: String?,
    val receiverBaseDeviceId: String? = null,
    val receiverRole: String? = null,
    val receiverHost: String? = null,
    val receiverIp: String? = null,
    val receiverPort: Int? = null,
    val sessionId: String?,
    val itemCount: Int?,
    val saveTarget: String?,
    val rawJson: String?
)
