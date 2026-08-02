package com.onlineimoti.calllog

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Test

class CallReportHistoryLookupPayloadTest {
    @Test
    fun parsesDedicatedCompanyMainNotesAlongsideHistoryItems() {
        val payload = JSONObject()
            .put("ok", true)
            .put(
                "principal",
                JSONObject().put(
                    "companies",
                    JSONArray().put(JSONObject().put("id", "firm-1").put("name", "Фирма 1")),
                ),
            )
            .put(
                "company_main_note_items",
                JSONArray().put(
                    JSONObject()
                        .put("id", "main-1")
                        .put("phone", "+359888111222")
                        .put("company_id", "firm-1")
                        .put("note", "Обща бележка от сървъра")
                        .put("updated_at_ms", 200L),
                ),
            )
            .put(
                "history_items",
                JSONArray().put(
                    JSONObject()
                        .put("id", "call-note-1")
                        .put("communication_type", "note")
                        .put("phone", "+359888111222")
                        .put("direction", "in")
                        .put("note", "Синя бележка")
                        .put("occurred_at_ms", 100L),
                ),
            )

        val result = CallReportHistoryLookupClient.parsePayload(payload)

        assertEquals(1, result.companyMainNotes.size)
        assertEquals("Фирма 1", result.companyMainNotes.single().companyName)
        assertEquals("Обща бележка от сървъра", result.companyMainNotes.single().note)
        assertEquals(1, result.events.size)
    }
}
