package com.vibedrop.mobile.nativeapp.data.local

import androidx.room.Dao
import androidx.room.Query
import androidx.room.Upsert
import kotlinx.coroutines.flow.Flow

@Dao
interface HistoryDao {
    @Query("SELECT * FROM history_entries ORDER BY timestampMillis DESC LIMIT :limit OFFSET :offset")
    fun observeRecent(limit: Int, offset: Int = 0): Flow<List<HistoryEntryEntity>>

    @Query("SELECT * FROM history_entries ORDER BY timestampMillis DESC LIMIT :limit OFFSET :offset")
    suspend fun getRecent(limit: Int, offset: Int = 0): List<HistoryEntryEntity>

    @Query("SELECT * FROM history_entries ORDER BY timestampMillis DESC")
    suspend fun getAllEntries(): List<HistoryEntryEntity>

    @Query("SELECT * FROM history_entries WHERE id = :id LIMIT 1")
    suspend fun findEntry(id: String): HistoryEntryEntity?

    @Query("SELECT COUNT(*) FROM history_entries")
    suspend fun countEntries(): Int

    @Upsert
    suspend fun upsertEntry(entry: HistoryEntryEntity)

    @Upsert
    suspend fun upsertItems(items: List<HistoryItemEntity>)
}
