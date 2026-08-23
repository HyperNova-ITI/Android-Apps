package com.hypernova.phone.contacts

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class CallerIdentityFallbacksTest {

    @Test
    fun `contacts provider name wins and carries its real photo uri`() {
        val identity =
            CallerIdentityFallbacks.resolve(
                contactsProviderName = "Ayman Hassan",
                contactsProviderPhotoUri = "content://com.android.contacts/photo/42",
                callLogCachedName = "Stale Cached",
                number = "+1 555 123 4567"
            )

        assertEquals("Ayman Hassan", identity.displayName)
        assertEquals("content://com.android.contacts/photo/42", identity.photoUri)
    }

    @Test
    fun `call log cached name is used without any photo`() {
        val identity =
            CallerIdentityFallbacks.resolve(
                contactsProviderName = null,
                contactsProviderPhotoUri = null,
                callLogCachedName = "Old Contact",
                number = "5551234567"
            )

        assertEquals("Old Contact", identity.displayName)
        assertNull(identity.photoUri)
    }

    @Test
    fun `raw number is the final fallback`() {
        val identity =
            CallerIdentityFallbacks.resolve(
                contactsProviderName = null,
                contactsProviderPhotoUri = null,
                callLogCachedName = null,
                number = " +1 (555) 123-4567 "
            )

        assertEquals("+1 (555) 123-4567", identity.displayName)
        assertNull(identity.photoUri)
    }

    @Test
    fun `number-like strings are never treated as names`() {
        val identity =
            CallerIdentityFallbacks.resolve(
                contactsProviderName = "555-123-4567",
                contactsProviderPhotoUri = "content://photo/x",
                callLogCachedName = "(555) 123 4567",
                number = "5551234567"
            )

        assertEquals("5551234567", identity.displayName)
        assertNull(identity.photoUri)
    }

    @Test
    fun `name equal to the number is not an identity`() {
        val identity =
            CallerIdentityFallbacks.resolve(
                contactsProviderName = "5551234567",
                contactsProviderPhotoUri = null,
                callLogCachedName = null,
                number = "5551234567"
            )

        assertEquals("5551234567", identity.displayName)
    }

    @Test
    fun `no usable data yields empty identity`() {
        val identity =
            CallerIdentityFallbacks.resolve(
                contactsProviderName = " ",
                contactsProviderPhotoUri = null,
                callLogCachedName = "",
                number = "restricted"
            )

        assertNull(identity.displayName)
        assertNull(identity.photoUri)
    }

    @Test
    fun `contacts denied still reaches permitted call log name`() {
        val identity =
            CallerIdentityFallbacks.resolveGated(
                contactsLookupAllowed = false,
                callLogLookupAllowed = true,
                contactsProviderName = "Ayman Hassan",
                contactsProviderPhotoUri = "content://com.android.contacts/photo/42",
                callLogCachedName = "Cached Name",
                number = "5551234567"
            )

        assertEquals("Cached Name", identity.displayName)
        assertNull(identity.photoUri)
    }

    @Test
    fun `both lookups denied falls back to raw number`() {
        val identity =
            CallerIdentityFallbacks.resolveGated(
                contactsLookupAllowed = false,
                callLogLookupAllowed = false,
                contactsProviderName = "Ayman Hassan",
                contactsProviderPhotoUri = "content://com.android.contacts/photo/42",
                callLogCachedName = "Cached Name",
                number = "+1 (555) 123-4567"
            )

        assertEquals("+1 (555) 123-4567", identity.displayName)
        assertNull(identity.photoUri)
    }
}
