package com.onlineimoti.calllog

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ProfileAccessInputTest {
    @Test
    fun emailUsesEmailChannel() {
        assertEquals(
            ProfileAccessTarget("person@example.com", "email"),
            ProfileAccessInput.parse(" person@example.com "),
        )
    }

    @Test
    fun phoneUsesSmsChannel() {
        assertEquals(
            ProfileAccessTarget("+359 888 123 456", "sms"),
            ProfileAccessInput.parse("+359 888 123 456"),
        )
    }

    @Test
    fun localPhoneUsesSmsChannel() {
        assertEquals(
            ProfileAccessTarget("0888123456", "sms"),
            ProfileAccessInput.parse("0888123456"),
        )
    }

    @Test
    fun invalidIdentifierIsRejected() {
        assertNull(ProfileAccessInput.parse(""))
        assertNull(ProfileAccessInput.parse("not a contact"))
        assertNull(ProfileAccessInput.parse("person@example"))
        assertNull(ProfileAccessInput.parse("1234"))
    }

    @Test
    fun eitherVerifiedContactMakesProfileReady() {
        assertTrue(CompanyAccountApi.ProfileUser(emailVerified = true).profileReady)
        assertTrue(CompanyAccountApi.ProfileUser(phoneVerified = true).profileReady)
        assertFalse(CompanyAccountApi.ProfileUser().profileReady)
    }
}
