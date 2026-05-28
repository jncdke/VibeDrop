package com.vibedrop.mobile.nativeapp.data.local

import androidx.room.Dao
import androidx.room.Query
import androidx.room.Upsert
import kotlinx.coroutines.flow.Flow

@Dao
interface DeviceDao {
    @Query("SELECT * FROM devices ORDER BY COALESCE(lastSeenAt, updatedAt) DESC")
    fun observeDevices(): Flow<List<DeviceEntity>>

    @Query("SELECT * FROM devices WHERE id = :id LIMIT 1")
    suspend fun findById(id: String): DeviceEntity?

    @Query("SELECT COUNT(*) FROM devices")
    suspend fun countDevices(): Int

    @Upsert
    suspend fun upsert(device: DeviceEntity)
}
