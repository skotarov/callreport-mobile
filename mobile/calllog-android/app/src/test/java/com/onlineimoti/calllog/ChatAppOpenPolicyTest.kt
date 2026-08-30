package com.onlineimoti.calllog

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ChatAppOpenPolicyTest {
    @Test
    fun viberKeepsThePhoneDestinationInsteadOfANameSearch() {
        assertTrue(ChatAppOpenPolicy.usesPhone(ChatApp.VIBER))
        assertNull(ChatAppOpenPolicy.searchQuery(ChatApp.VIBER, "Иван Иванов"))
    }

    @Test
    fun appsWithoutPhoneAddressingReceiveTheKnownContactNameAsSearchQuery() {
        assertEquals("Иван Иванов", ChatAppOpenPolicy.searchQuery(ChatApp.MESSENGER, "  Иван Иванов  "))
    }

    @Test
    fun blankContactNameDoesNotTriggerAnEmptyAppSearch() {
        assertNull(ChatAppOpenPolicy.searchQuery(ChatApp.INSTAGRAM, "  "))
    }
}
