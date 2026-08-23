package com.hypernova.navigation.ui

import com.hypernova.navigation.persistence.DestinationTokenEntry

/** Selects only a defensible local Places match for a launcher evidence card. */
internal object DestinationIntentMatcher {
    fun select(
        query: String,
        candidates: List<DestinationTokenEntry>,
    ): DestinationTokenEntry? {
        if (candidates.isEmpty()) return null
        val normalizedQuery = normalize(query)
        if (normalizedQuery.isBlank()) return null

        candidates.firstOrNull { normalize(it.record.title) == normalizedQuery }?.let { return it }
        candidates.firstOrNull { candidate ->
            val title = normalize(candidate.record.title)
            title.isNotBlank() &&
                (title.contains(normalizedQuery) || normalizedQuery.contains(title)) &&
                minOf(title.length, normalizedQuery.length) * 100 >=
                    maxOf(title.length, normalizedQuery.length) * 70
        }?.let { return it }

        return candidates.singleOrNull()
    }

    private fun normalize(value: String): String =
        value.lowercase()
            .replace(Regex("[^\\p{L}\\p{N}]+"), " ")
            .trim()
            .replace(Regex("\\s+"), " ")
}
