package com.hypernova.phone.contacts

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PhoneNumberMatchingTest {

    @Test
    fun `normalize trims and drops blank input`() {
        assertEquals("+1 555 123", PhoneNumberMatching.normalize(" +1 555 123 "))
        assertNull(PhoneNumberMatching.normalize("   "))
        assertNull(PhoneNumberMatching.normalize(null))
    }

    @Test
    fun `cache key strips formatting and keeps leading plus`() {
        assertEquals("+15551234567", PhoneNumberMatching.cacheKey("+1 (555) 123-4567"))
        assertEquals("5551234567", PhoneNumberMatching.cacheKey("555.123.4567"))
        assertNull(PhoneNumberMatching.cacheKey("no digits"))
        assertNull(PhoneNumberMatching.cacheKey(null))
    }

    @Test
    fun `same number matches formatted variants`() {
        assertTrue(
            PhoneNumberMatching.sameNumber(
                "+1 (555) 123-4567",
                "555 123 4567"
            )
        )
    }

    @Test
    fun `same number matches country code prefix by suffix`() {
        assertTrue(
            PhoneNumberMatching.sameNumber(
                "+49 30 12345678",
                "030 12345678"
            )
        )
        assertTrue(
            PhoneNumberMatching.sameNumber(
                "+1 5551234567",
                "5551234567"
            )
        )
    }

    @Test
    fun `same number matches Egyptian local and international formats`() {
        assertTrue(
            PhoneNumberMatching.sameNumber(
                "+20 (10) 1234-5678",
                "010 1234 5678"
            )
        )
    }

    @Test
    fun `different numbers never match`() {
        assertFalse(
            PhoneNumberMatching.sameNumber(
                "+1 555 123 4567",
                "+1 555 987 6543"
            )
        )
        assertFalse(
            PhoneNumberMatching.sameNumber(
                "911",
                "112"
            )
        )
    }

    @Test
    fun `short numbers only match when keys are identical`() {
        assertTrue(PhoneNumberMatching.sameNumber("911", " 911 "))
        assertFalse(PhoneNumberMatching.sameNumber("911", "912"))
    }

    @Test
    fun `usable number requires at least one digit`() {
        assertTrue(PhoneNumberMatching.isUsableNumber("(555) 000"))
        assertFalse(PhoneNumberMatching.isUsableNumber("restricted"))
        assertFalse(PhoneNumberMatching.isUsableNumber(""))
        assertFalse(PhoneNumberMatching.isUsableNumber(null))
    }
}
