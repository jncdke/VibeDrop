package com.vibedrop.mobile.nativeapp.data.legacy

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class LegacyHistoryImporterTest {
    @Test
    fun extractsTopLevelHistoryArray() {
        val array = extractLegacyHistoryEntries(
            """
            [
              { "id": "legacy_1", "text": "hello" },
              { "id": "legacy_2", "text": "world" }
            ]
            """.trimIndent()
        )

        assertEquals(2, array.length())
        assertEquals("legacy_1", array.getJSONObject(0).getString("id"))
    }

    @Test
    fun extractsNativeHistoryEnvelope() {
        val array = extractLegacyHistoryEntries(
            """
            {
              "schemaVersion": 1,
              "history": [
                { "id": "native_export_1", "text": "hello" }
              ]
            }
            """.trimIndent()
        )

        assertEquals(1, array.length())
        assertEquals("native_export_1", array.getJSONObject(0).getString("id"))
    }

    @Test
    fun extractsCompatibleEntriesEnvelope() {
        val array = extractLegacyHistoryEntries(
            """
            {
              "entries": [
                { "id": "manual_backup_1", "text": "hello" }
              ]
            }
            """.trimIndent()
        )

        assertEquals(1, array.length())
        assertEquals("manual_backup_1", array.getJSONObject(0).getString("id"))
    }

    @Test
    fun rejectsObjectWithoutHistoryArray() {
        val error = assertThrows(IllegalArgumentException::class.java) {
            extractLegacyHistoryEntries("""{ "schemaVersion": 1 }""")
        }

        assertEquals(
            "history archive must be a JSON array or contain a history/entries/items/data array",
            error.message
        )
    }
}
