package com.hypernova.phone.ui

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Focused UI-safety checks for the in-call contact photo decision.
 *
 * The renderer must never fabricate a photo: it may only show one when a
 * real ContactsProvider URI is present. These tests pin that rule without
 * needing an Android context.
 */
class PhoneScreenPhotoTest {

    @Test
    fun blankUriNeverUsesPhoto() {
        assertFalse(
            "blank URI must fall back to initial/generic avatar",
            isPhotoUriPresent("")
        )
        assertFalse(
            "whitespace-only URI must fall back",
            isPhotoUriPresent("   ")
        )
    }

    @Test
    fun nullUriNeverUsesPhoto() {
        assertFalse(
            "null URI must fall back to initial/generic avatar",
            isPhotoUriPresent(null)
        )
    }

    @Test
    fun realContactsUriAllowsPhoto() {
        assertTrue(
            "a real ContactsProvider URI may be decoded and shown",
            isPhotoUriPresent(
                "content://com.android.contacts/contacts/123/photo"
            )
        )
    }

    @Test
    fun nonContactsContentAuthorityRejected() {
        assertFalse(
            "media provider content URI is not the ContactsProvider",
            isPhotoUriPresent(
                "content://media/external/images/media/1"
            )
        )
    }

    @Test
    fun httpUriRejected() {
        assertFalse(
            "http URIs must never be used (no network photo)",
            isPhotoUriPresent(
                "http://example.com/avatar.png"
            )
        )
    }

    @Test
    fun fileUriRejected() {
        assertFalse(
            "file URIs are not the ContactsProvider",
            isPhotoUriPresent(
                "file:///storage/emulated/0/avatar.png"
            )
        )
    }
}
