package com.vibedrop.mobile.nativeapp.protocol

import com.vibedrop.mobile.nativeapp.FixtureFiles
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class VibeDropProtocolFixtureTest {
    @Test
    fun authPayloadMatchesPrimaryConnectionFixture() {
        val expected = JSONObject(FixtureFiles.protocolMessage("auth-primary.json"))
        val actual = AuthPayload(
            pin = "1234",
            deviceId = "client_android_demo",
            baseDeviceId = "client_android_demo",
            deviceName = "一加 Ace 5",
            canReceiveFiles = true,
            receivesClipboard = false,
            deviceRole = "primary"
        ).toJson()

        assertTrue(actual.similar(expected))
    }

    @Test
    fun authPayloadMatchesClipboardSyncFixture() {
        val expected = JSONObject(FixtureFiles.protocolMessage("auth-clipboard-sync.json"))
        val actual = AuthPayload(
            pin = "1234",
            deviceId = "client_android_demo_clipboard",
            baseDeviceId = "client_android_demo",
            deviceName = "一加 Ace 5",
            canReceiveFiles = false,
            receivesClipboard = true,
            deviceRole = "clipboard_sync"
        ).toJson()

        assertTrue(actual.similar(expected))
    }

    @Test
    fun parseActionRecognizesAllWebSocketActionFixtures() {
        val expectedActions = mapOf(
            "auth-primary.json" to VibeDropActions.Auth,
            "auth-clipboard-sync.json" to VibeDropActions.Auth,
            "clipboard.json" to VibeDropActions.Clipboard,
            "enter.json" to VibeDropActions.Enter,
            "image-clipboard.json" to VibeDropActions.ImageClipboard,
            "incoming-history-session-start.json" to VibeDropActions.IncomingHistorySessionStart,
            "incoming-file-start.json" to VibeDropActions.IncomingFileStart,
            "incoming-file-chunk.json" to VibeDropActions.IncomingFileChunk,
            "incoming-file-complete.json" to VibeDropActions.IncomingFileComplete,
            "incoming-file-saved.json" to VibeDropActions.IncomingFileSaved,
            "incoming-file-error.json" to VibeDropActions.IncomingFileError,
            "ping.json" to VibeDropActions.Ping,
            "pong.json" to VibeDropActions.Pong,
            "type.json" to VibeDropActions.Type,
            "type-enter.json" to VibeDropActions.TypeEnter
        )

        expectedActions.forEach { (fileName, action) ->
            assertEquals(action, parseAction(FixtureFiles.protocolMessage(fileName)))
        }
    }

    @Test
    fun parseActionIgnoresHttpAndDiscoveryFixtures() {
        listOf(
            "auth-ok.json",
            "discover-probe.json",
            "discover-response.json",
            "pair-request.json",
            "pair-status-approved.json"
        ).forEach { fileName ->
            assertNull(parseAction(FixtureFiles.protocolMessage(fileName)))
        }
    }
}
