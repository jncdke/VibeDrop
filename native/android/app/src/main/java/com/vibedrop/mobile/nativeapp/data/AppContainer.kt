package com.vibedrop.mobile.nativeapp.data

import android.content.Context
import androidx.room.Room
import com.vibedrop.mobile.nativeapp.data.legacy.LegacyHistoryImporter
import com.vibedrop.mobile.nativeapp.data.local.VibeDropDatabase
import com.vibedrop.mobile.nativeapp.data.repository.DeviceRepository
import com.vibedrop.mobile.nativeapp.data.repository.HistoryRepository

class AppContainer(context: Context) {
    private val appContext = context.applicationContext

    val database: VibeDropDatabase = Room.databaseBuilder(
        appContext,
        VibeDropDatabase::class.java,
        "vibedrop-native.db"
    ).build()

    val deviceRepository = DeviceRepository(database.deviceDao())
    val historyRepository = HistoryRepository(database.historyDao())
    val legacyHistoryImporter = LegacyHistoryImporter(
        context = appContext,
        deviceRepository = deviceRepository,
        historyRepository = historyRepository
    )
}
