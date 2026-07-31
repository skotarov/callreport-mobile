package com.onlineimoti.calllog

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class PhoneNormalizerTest {
    @Before
    fun setUp() {
        PhoneNormalizer.configureNativeCountryCode("+359")
    }

    @After
    fun tearDown() {
        PhoneNormalizer.configureNativeCountryCode("")
    }

    @Test
    fun localAndInternationalBulgarianNumbersUseOneCanonicalValue() {
        assertEquals("+359877904903", PhoneNormalizer.normalize("0877904903"))
        assertEquals("+359877904903", PhoneNormalizer.normalize("359877904903"))
        assertEquals("+359877904903", PhoneNormalizer.normalize("+359 877 904 903"))
        assertEquals("+359877904903", PhoneNormalizer.normalize("00359877904903"))
        assertTrue(PhoneNormalizer.samePhone("0877904903", "+359877904903"))
    }

    @Test
    fun selectedCountryIsShownWithZeroAndForeignCountryKeepsPlus() {
        assertEquals("0877 904 903", PhoneNormalizer.display("+359877904903"))
        assertEquals("+447700900123", PhoneNormalizer.display("+44 7700 900123"))
    }

    @Test
    fun blankCountrySettingDisablesLocalCountryConversion() {
        PhoneNormalizer.configureNativeCountryCode("")
        assertEquals("0877904903", PhoneNormalizer.normalize("0877904903"))
        assertEquals("+359877904903", PhoneNormalizer.normalize("+359877904903"))
        assertEquals("0877904903", PhoneNormalizer.display("0877904903"))
    }

    @Test
    fun differentInternationalCountriesDoNotMatchByLastDigits() {
        assertFalse(PhoneNormalizer.samePhone("+359877904903", "+44877904903"))
    }

    @Test
    fun countryCodeCanBeChanged() {
        PhoneNormalizer.configureNativeCountryCode("+44")
        assertEquals("+447700900123", PhoneNormalizer.normalize("07700900123"))
        assertEquals("07700900123", PhoneNormalizer.display("+447700900123"))
        assertEquals("+359877904903", PhoneNormalizer.display("+359877904903"))
    }
}
