package com.onlineimoti.calllog

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Test

class ClientsAuthoritativePageRowsTest {
    @Test
    fun parserPreservesServerRowsAndOrderWithoutLocalDedupe() {
        val json = JSONObject(
            """{
                "ok": true,
                "total": 2,
                "limit": 20,
                "offset": 0,
                "contacts": [
                    {"client_id":"same-server-key","phone":"0888123456","name":"First"},
                    {"client_id":"same-server-key","phone":"0899123456","name":"Second"}
                ]
            }""".trimIndent(),
        )

        val page = ServerCrmContactsClient.parsePage(
            json = json,
            filterState = HomeCrmFilterState(),
            requestedLimit = 20,
            requestedOffset = 0,
        )

        assertEquals(2, page.total)
        assertEquals(2, page.clients.size)
        assertEquals(listOf("0888123456", "0899123456"), page.clients.map { it.phone })
    }
}
