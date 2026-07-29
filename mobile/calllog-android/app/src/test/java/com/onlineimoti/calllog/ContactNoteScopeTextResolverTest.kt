package com.onlineimoti.calllog

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ContactNoteScopeTextResolverTest {
    @Test
    fun pendingServerEditOverridesOlderServerValue() {
        val merged = ContactNoteScopeTextResolver.overlayPendingValues(
            serverValues = mapOf(
                "company-1" to ContactNoteScopeValue(
                    text = "Стара бележка от сървъра",
                    serverClientEventId = "server-event",
                ),
            ),
            pendingValues = mapOf(
                "company-1" to ContactNoteScopeValue(
                    text = "Нова редакция от телефона",
                    serverClientEventId = "pending-event",
                ),
            ),
        )

        assertEquals("Нова редакция от телефона", merged.getValue("company-1").text)
        assertEquals("pending-event", merged.getValue("company-1").serverClientEventId)
    }

    @Test
    fun pendingDeletionKeepsOlderServerTextHidden() {
        val merged = ContactNoteScopeTextResolver.overlayPendingValues(
            serverValues = mapOf(
                "company-1" to ContactNoteScopeValue("Стара бележка", "server-event"),
            ),
            pendingValues = mapOf(
                "company-1" to ContactNoteScopeValue("", "pending-delete-event"),
            ),
        )

        assertTrue(merged.getValue("company-1").text.isBlank())
        assertEquals("pending-delete-event", merged.getValue("company-1").serverClientEventId)
    }
}
