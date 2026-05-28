package com.vibedrop.mobile.nativeapp.data.local

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(
    tableName = "history_items",
    indices = [
        Index(value = ["entryId"]),
        Index(value = ["kind"]),
        Index(value = ["status"])
    ]
)
data class HistoryItemEntity(
    @PrimaryKey val id: String,
    val entryId: String,
    val itemIndex: Int,
    val kind: String,
    val fileName: String?,
    val mimeType: String?,
    val sizeBytes: Long?,
    val localPath: String?,
    val savedPath: String?,
    val thumbnailPath: String?,
    val thumbnailDataUrl: String?,
    val status: String?,
    val error: String?
)
