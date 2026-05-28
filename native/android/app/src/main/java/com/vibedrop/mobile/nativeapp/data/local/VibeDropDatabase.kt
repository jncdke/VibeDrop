package com.vibedrop.mobile.nativeapp.data.local

import androidx.room.Database
import androidx.room.RoomDatabase

@Database(
    entities = [
        DeviceEntity::class,
        HistoryEntryEntity::class,
        HistoryItemEntity::class
    ],
    version = 2,
    exportSchema = false
)
abstract class VibeDropDatabase : RoomDatabase() {
    abstract fun deviceDao(): DeviceDao
    abstract fun historyDao(): HistoryDao
}
