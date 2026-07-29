package com.onlineimoti.calllog

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CommunicationAddressTest {
    @Test
    fun alphanumericSenderRemainsAValidUnifiedIdentity() {
        val address = CommunicationAddress.resolved(" Vivacom ", phoneKey = "")

        assertTrue(address.isValid)
        assertFalse(address.isPhone)
        assertTrue(address.matches("VIVACOM"))
        assertFalse(address.matches("A1"))
    }

    @Test
    fun resolvedPhoneKeepsTheExistingPhoneBranch() {
        val address = CommunicationAddress.resolved("+359898482359", phoneKey = "898482359")

        assertTrue(address.isValid)
        assertTrue(address.isPhone)
    }

    @Test
    fun blankAddressIsNotAConversationIdentity() {
        val address = CommunicationAddress.resolved("   ", phoneKey = "")

        assertFalse(address.isValid)
        assertFalse(address.matches("Vivacom"))
    }
}
