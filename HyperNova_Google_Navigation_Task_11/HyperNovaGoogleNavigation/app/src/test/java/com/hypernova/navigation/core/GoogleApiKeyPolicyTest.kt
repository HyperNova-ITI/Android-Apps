package com.hypernova.navigation.core

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class GoogleApiKeyPolicyTest {
    @Test
    fun nullAndBlankKeysAreNotConfigured() {
        assertFalse(GoogleApiKeyPolicy.isConfigured(null))
        assertFalse(GoogleApiKeyPolicy.isConfigured(""))
        assertFalse(GoogleApiKeyPolicy.isConfigured("   "))
        assertFalse(GoogleApiKeyPolicy.isConfigured("\t\n"))
    }

    @Test
    fun exactPlaceholdersAreNotConfigured() {
        assertFalse(GoogleApiKeyPolicy.isConfigured("DEFAULT_API_KEY"))
        assertFalse(GoogleApiKeyPolicy.isConfigured("YOUR_API_KEY"))
        assertFalse(GoogleApiKeyPolicy.isConfigured("YOUR_GOOGLE_MAPS_API_KEY"))
    }

    @Test
    fun placeholderSurroundedByWhitespaceIsNotConfigured() {
        assertFalse(GoogleApiKeyPolicy.isConfigured("  DEFAULT_API_KEY  "))
        assertFalse(GoogleApiKeyPolicy.isConfigured(" YOUR_GOOGLE_MAPS_API_KEY\n"))
    }

    @Test
    fun yourPrefixKeysAreNotConfigured() {
        assertFalse(GoogleApiKeyPolicy.isConfigured("YOUR_ANYTHING"))
        assertFalse(GoogleApiKeyPolicy.isConfigured("your_api_key_123"))
        assertFalse(GoogleApiKeyPolicy.isConfigured("Your_Google_Maps_API_Key_Here"))
        assertFalse(GoogleApiKeyPolicy.isConfigured("YOUR "))
    }

    @Test
    fun placeholderSubstringKeysAreNotConfigured() {
        assertFalse(GoogleApiKeyPolicy.isConfigured("PLACEHOLDER_IN_KEY"))
        assertFalse(GoogleApiKeyPolicy.isConfigured("ai-xyz-placeholder-abc"))
        assertFalse(GoogleApiKeyPolicy.isConfigured("AIzaSyREPLACEME"))
        assertFalse(GoogleApiKeyPolicy.isConfigured("A placeholder key"))
    }

    @Test
    fun nonGoogleIdentifiersAreNotConfigured() {
        assertFalse(GoogleApiKeyPolicy.isConfigured("configured-key-value"))
        assertFalse(GoogleApiKeyPolicy.isConfigured("20d0a8fe56e67ae4e0d3323d"))
        assertFalse(GoogleApiKeyPolicy.isConfigured("not-an-api-key-even-when-it-is-long-enough"))
        assertFalse(
            GoogleApiKeyPolicy.isConfigured(
                "0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef",
            ),
        )
    }

    @Test
    fun googleApiKeysAreConfigured() {
        val shapedLikeAKey = "AIza" + "a".repeat(35)
        assertTrue(GoogleApiKeyPolicy.isConfigured(shapedLikeAKey))
        assertTrue(GoogleApiKeyPolicy.isConfigured("  $shapedLikeAKey  "))
    }
}
