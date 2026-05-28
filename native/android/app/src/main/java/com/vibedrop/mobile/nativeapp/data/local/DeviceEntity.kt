package com.vibedrop.mobile.nativeapp.data.local

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(
    tableName = "devices",
    indices = [
        Index(value = ["stableId"]),
        Index(value = ["displayName"])
    ]
)
data class DeviceEntity(
    @PrimaryKey val id: String,
    val stableId: String,
    val displayName: String,
    val role: String,
    val host: String?,
    val ip: String?,
    val port: Int?,
    val pin: String?,
    val aliasesJson: String,
    val capabilitiesJson: String,
    val lastSeenAt: Long?,
    val createdAt: Long,
    val updatedAt: Long
)
