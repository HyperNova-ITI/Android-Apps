package com.hypernova.navigation.core

object GoogleApiKeyPolicy {
    private val placeholders =
        setOf(
            "DEFAULT_API_KEY",
            "YOUR_API_KEY",
            "YOUR_GOOGLE_MAPS_API_KEY",
        )

    fun isConfigured(value: String?): Boolean {
        val candidate = value?.trim().orEmpty()
        return candidate.isNotEmpty() &&
            candidate !in placeholders &&
            !candidate.startsWith("YOUR", ignoreCase = true) &&
            !candidate.contains("PLACEHOLDER", ignoreCase = true) &&
            !candidate.contains("REPLACEME", ignoreCase = true) &&
            !candidate.contains("REPLACE_ME", ignoreCase = true)
    }
}
