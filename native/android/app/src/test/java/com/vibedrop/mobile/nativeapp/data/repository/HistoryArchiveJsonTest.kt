package com.vibedrop.mobile.nativeapp.data.repository

import com.vibedrop.mobile.nativeapp.data.local.HistoryEntryEntity
import com.vibedrop.mobile.nativeapp.data.local.HistoryItemEntity
import org.junit.Assert.assertEquals
import org.junit.Test

class HistoryArchiveJsonTest {
    @Test
    fun exportedHistoryPreservesEndpointIdentityMetadataAndThumbnailFields() {
        val entry = HistoryEntryEntity(
            id = "android_entry_demo_002",
            timestampMillis = 1_779_953_940_000L,
            direction = "mobile_to_desktop",
            kind = "image",
            status = "success",
            text = "[图片] demo.png",
            senderDeviceId = "client_android_demo",
            senderName = "一加 Ace 5",
            senderBaseDeviceId = "client_android_demo",
            senderRole = "primary",
            senderHost = "oneplus.local",
            senderIp = "192.168.3.20",
            senderPort = 0,
            receiverDeviceId = "desktop_demo_macbook",
            receiverName = "MacBook",
            receiverBaseDeviceId = "desktop_demo_macbook",
            receiverRole = "desktop",
            receiverHost = "overlorddeMacBook-Air-4.local",
            receiverIp = "192.168.3.10",
            receiverPort = 9001,
            sessionId = "session_demo_001",
            itemCount = 1,
            saveTarget = "clipboard",
            rawJson = null
        )
        val item = HistoryItemEntity(
            id = "android_item_demo_001",
            entryId = entry.id,
            itemIndex = 0,
            kind = "image",
            fileName = "demo.png",
            mimeType = "image/png",
            sizeBytes = 128,
            localPath = null,
            savedPath = null,
            thumbnailPath = null,
            thumbnailDataUrl = "data:image/png;base64,demo",
            status = "success",
            error = null
        )

        val json = entry.toHistoryJsonObject(listOf(item))
        val itemJson = json.getJSONArray("items").getJSONObject(0)

        assertEquals("client_android_demo", json.getString("senderBaseDeviceId"))
        assertEquals("client_android_demo", json.getString("sender_base_device_id"))
        assertEquals("primary", json.getString("senderRole"))
        assertEquals("primary", json.getString("sender_role"))
        assertEquals("desktop_demo_macbook", json.getString("receiverBaseDeviceId"))
        assertEquals("desktop_demo_macbook", json.getString("receiver_base_device_id"))
        assertEquals("overlorddeMacBook-Air-4.local", json.getString("receiverHost"))
        assertEquals("overlorddeMacBook-Air-4.local", json.getString("receiver_host"))
        assertEquals("overlorddeMacBook-Air-4.local", json.getString("targetHost"))
        assertEquals("overlorddeMacBook-Air-4.local", json.getString("targetDeviceName"))
        assertEquals(9001, json.getInt("receiverPort"))
        assertEquals(9001, json.getInt("targetPort"))
        assertEquals("data:image/png;base64,demo", itemJson.getString("thumbnailDataUrl"))
        assertEquals("data:image/png;base64,demo", itemJson.getString("thumbnail_data_url"))
    }
}
