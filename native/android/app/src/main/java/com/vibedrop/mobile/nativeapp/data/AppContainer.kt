package com.vibedrop.mobile.nativeapp.data

import android.content.Context
import androidx.room.Room
import com.vibedrop.mobile.nativeapp.data.legacy.LegacyHistoryImporter
import com.vibedrop.mobile.nativeapp.data.local.VibeDropDatabase
import com.vibedrop.mobile.nativeapp.data.local.VibeDropMigrations
import com.vibedrop.mobile.nativeapp.data.repository.DeviceRepository
import com.vibedrop.mobile.nativeapp.data.repository.DiscoveryRepository
import com.vibedrop.mobile.nativeapp.data.repository.HomeVaultRepository
import com.vibedrop.mobile.nativeapp.data.repository.HistoryRepository
import com.vibedrop.mobile.nativeapp.platform.DiagnosticLogStore
import com.vibedrop.mobile.nativeapp.platform.MediaOpenPreferences
import com.vibedrop.mobile.nativeapp.platform.loadAndroidDeviceIdentity

class AppContainer(context: Context) {
    private val appContext = context.applicationContext
    val androidIdentity = loadAndroidDeviceIdentity(appContext)

    val database: VibeDropDatabase = Room.databaseBuilder(
        appContext,
        VibeDropDatabase::class.java,
        "vibedrop-native.db"
    )
        .addMigrations(*VibeDropMigrations.ALL)
        .build()

    val deviceRepository = DeviceRepository(database.deviceDao())
    val historyRepository = HistoryRepository(database.historyDao(), androidIdentity)
    val discoveryRepository = DiscoveryRepository(appContext)
    val homeVaultRepository = HomeVaultRepository(appContext, androidIdentity)
    val mediaOpenPreferences = MediaOpenPreferences(appContext)
    val diagnosticLogStore = DiagnosticLogStore(appContext)
    val legacyHistoryImporter = LegacyHistoryImporter(
        context = appContext,
        deviceRepository = deviceRepository,
        historyRepository = historyRepository
    )

}
