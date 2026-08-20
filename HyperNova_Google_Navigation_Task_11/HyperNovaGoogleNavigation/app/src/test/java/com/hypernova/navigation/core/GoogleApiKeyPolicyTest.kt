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
    fun unrelatedHexTokensAreNotMistakenForGoogleApiKeys() {
        assertFalse(
            GoogleApiKeyPolicy.isConfigured(
                "0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef",
            ),
        )
    }

    @Test
    fun realKeysAreConfigured() {
        assertTrue(GoogleApiKeyPolicy.isConfigured("configured-key-value"))
        assertTrue(GoogleApiKeyPolicy.isConfigured("a-real-key-value"))
        assertTrue(GoogleApiKeyPolicy.isConfigured("  configured-key-with-spaces  "))
    }
}
