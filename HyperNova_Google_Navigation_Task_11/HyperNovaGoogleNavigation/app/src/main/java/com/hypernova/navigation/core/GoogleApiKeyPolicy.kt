package com.hypernova.navigation.core

object GoogleApiKeyPolicy {
    // Google Maps Platform API keys use the standard Google API-key shape.
    // Reject arbitrary non-placeholder strings so a copied Map ID, client ID,
    // or stale handoff value cannot make a release build look configured.
    private val googleApiKey = Regex("^AIza[0-9A-Za-z_-]{30,}$")
    private val hexToken = Regex("^[0-9a-fA-F]{64}$")
    private val placeholders =
        setOf(
            "DEFAULT_API_KEY",
            "YOUR_API_KEY",
            "YOUR_GOOGLE_MAPS_API_KEY",
        )

    fun isConfigured(value: String?): Boolean {
        val candidate = value?.trim().orEmpty()
        return candidate.isNotEmpty() &&
            !hexToken.matches(candidate) &&
            candidate !in placeholders &&
            !candidate.startsWith("YOUR", ignoreCase = true) &&
            !candidate.contains("PLACEHOLDER", ignoreCase = true) &&
            !candidate.contains("REPLACEME", ignoreCase = true) &&
            !candidate.contains("REPLACE_ME", ignoreCase = true) &&
            googleApiKey.matches(candidate)
    }
}
