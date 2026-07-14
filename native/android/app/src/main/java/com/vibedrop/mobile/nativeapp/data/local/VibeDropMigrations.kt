package com.vibedrop.mobile.nativeapp.data.local

import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

object VibeDropMigrations {
    val MIGRATION_1_2 = object : Migration(1, 2) {
        override fun migrate(db: SupportSQLiteDatabase) {
            db.execSQL("ALTER TABLE devices ADD COLUMN sortOrder INTEGER NOT NULL DEFAULT 0")
        }
    }

    val MIGRATION_2_3 = object : Migration(2, 3) {
        override fun migrate(db: SupportSQLiteDatabase) {
            db.execSQL("ALTER TABLE history_entries ADD COLUMN senderBaseDeviceId TEXT")
            db.execSQL("ALTER TABLE history_entries ADD COLUMN senderRole TEXT")
            db.execSQL("ALTER TABLE history_entries ADD COLUMN senderHost TEXT")
            db.execSQL("ALTER TABLE history_entries ADD COLUMN senderIp TEXT")
            db.execSQL("ALTER TABLE history_entries ADD COLUMN senderPort INTEGER")
            db.execSQL("ALTER TABLE history_entries ADD COLUMN receiverBaseDeviceId TEXT")
            db.execSQL("ALTER TABLE history_entries ADD COLUMN receiverRole TEXT")
            db.execSQL("ALTER TABLE history_entries ADD COLUMN receiverHost TEXT")
            db.execSQL("ALTER TABLE history_entries ADD COLUMN receiverIp TEXT")
            db.execSQL("ALTER TABLE history_entries ADD COLUMN receiverPort INTEGER")
            db.execSQL("CREATE INDEX IF NOT EXISTS index_history_entries_senderBaseDeviceId ON history_entries(senderBaseDeviceId)")
            db.execSQL("CREATE INDEX IF NOT EXISTS index_history_entries_receiverBaseDeviceId ON history_entries(receiverBaseDeviceId)")
        }
    }

    val ALL = arrayOf(MIGRATION_1_2, MIGRATION_2_3)
}
