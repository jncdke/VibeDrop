package com.vibedrop.mobile.nativeapp.data.legacy

import org.junit.Assert.assertEquals
import org.junit.Test

class LegacyDeviceImporterTest {
    @Test
    fun extractsLegacyBackgroundClipboardDevices() {
        val devices = extractLegacyBackgroundClipboardDevices(
            """
            {
              "identity": { "id": "client_android_demo", "name": "一加 Ace 5" },
              "devices": [
                {
                  "id": "dev_overlord",
                  "name": "overlorddeMacBook-Air-4.local",
                  "ip": "192.168.3.10",
                  "port": "9001",
                  "pin": "1234"
                },
                {
                  "id": "dev_mini",
                  "name": "minideMac-mini.local",
                  "ip": "192.168.3.2",
                  "port": "9001",
                  "pin": "5678"
                }
              ]
            }
            """.trimIndent()
        )

        assertEquals(2, devices.size)
        assertEquals("dev_overlord", devices[0].id)
        assertEquals("overlorddeMacBook-Air-4.local", devices[0].name)
        assertEquals("192.168.3.10", devices[0].ip)
        assertEquals(9001, devices[0].port)
        assertEquals("1234", devices[0].pin)
    }

    @Test
    fun skipsDevicesWithoutIpOrPin() {
        val devices = extractLegacyBackgroundClipboardDevices(
            """
            {
              "devices": [
                { "id": "missing_pin", "name": "Mac", "ip": "192.168.3.10", "port": "9001" },
                { "id": "missing_ip", "name": "Mac", "pin": "1234", "port": "9001" },
                { "id": "ok", "name": "Mac", "ip": "192.168.3.11", "pin": "1234" }
              ]
            }
            """.trimIndent()
        )

        assertEquals(1, devices.size)
        assertEquals("ok", devices.single().id)
        assertEquals(9001, devices.single().port)
    }

    @Test
    fun fallsBackToIpPortIdAndDefaultPort() {
        val devices = extractLegacyBackgroundClipboardDevices(
            """
            {
              "devices": [
                { "name": "", "ip": "192.168.3.12", "port": "bad", "pin": "1234" }
              ]
            }
            """.trimIndent()
        )

        assertEquals(1, devices.size)
        assertEquals("192.168.3.12:9001", devices.single().id)
        assertEquals("192.168.3.12", devices.single().name)
        assertEquals(9001, devices.single().port)
    }
}
