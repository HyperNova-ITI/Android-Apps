package com.hypernova.phone.ui

import com.hypernova.phone.domain.ContactEntry
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ContactSearchTest {

    private val contact =
        ContactEntry(
            id = 17L,
            displayName = "Saved Contact",
            number = "+20 (10) 1234-5678",
            label = "Mobile",
            isFavorite = false
        )

    @Test
    fun `search matches real provider display name`() {
        assertTrue(
            contactMatchesQuery(
                contact,
                "saved"
            )
        )
    }

    @Test
    fun `search matches formatted local number`() {
        assertTrue(
            contactMatchesQuery(
                contact,
                "0101234"
            )
        )
    }

    @Test
    fun `search rejects unrelated query`() {
        assertFalse(
            contactMatchesQuery(
                contact,
                "unrelated"
            )
        )
    }
}
