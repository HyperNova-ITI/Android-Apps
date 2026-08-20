package com.hypernova.navigation.persistence

import com.hypernova.contracts.navigation.NavigationContract
import com.hypernova.navigation.core.Clock
import com.hypernova.navigation.core.SystemClock
import com.hypernova.navigation.model.GoogleDestinationRecord
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.util.UUID

data class DestinationTokenEntry(
    val token: String,
    val source: Int,
    val record: GoogleDestinationRecord,
    val createdAtMillis: Long,
    val expiresAtMillis: Long?,
)

interface DestinationTokenPersistence {
    fun load(): List<DestinationTokenEntry>
    fun save(entries: List<DestinationTokenEntry>)
}

sealed interface DestinationResolution {
    data class Found(val entry: DestinationTokenEntry) : DestinationResolution
    data object Expired : DestinationResolution
    data object Unknown : DestinationResolution
}

class DestinationTokenStore(
    private val persistence: DestinationTokenPersistence,
    private val clock: Clock = SystemClock,
    private val searchTtlMillis: Long = NavigationContract.SEARCH_RESULT_TTL_MILLIS,
) {
    private val entries = linkedMapOf<String, DestinationTokenEntry>()

    init {
        persistence.load().forEach { entry -> entries[entry.token] = entry }
        pruneOldTombstones()
    }

    @Synchronized
    fun createSearchTokens(records: List<GoogleDestinationRecord>): List<DestinationTokenEntry> {
        val now = clock.nowMillis()
        val created =
            records.take(NavigationContract.MAX_DESTINATION_RESULTS).map { record ->
                DestinationTokenEntry(
                    token = "nav_search_${UUID.randomUUID()}",
                    source = NavigationContract.SOURCE_SEARCH,
                    record = record,
                    createdAtMillis = now,
                    expiresAtMillis = now + searchTtlMillis,
                ).also { entries[it.token] = it }
            }
        persist()
        return created
    }

    @Synchronized
    fun putSaved(record: GoogleDestinationRecord, source: Int): DestinationTokenEntry {
        require(
            source in
                setOf(
                    NavigationContract.SOURCE_SAVED_HOME,
                    NavigationContract.SOURCE_SAVED_WORK,
                    NavigationContract.SOURCE_SAVED_FAVORITE,
                ),
        )
        val digest =
            MessageDigest.getInstance("SHA-256")
                .digest("$source:${record.placeId}".toByteArray(StandardCharsets.UTF_8))
                .take(12)
                .joinToString("") { byte -> "%02x".format(byte.toInt() and 0xff) }
        val token = "nav_saved_$digest"
        val entry =
            DestinationTokenEntry(
                token = token,
                source = source,
                record = record,
                createdAtMillis = clock.nowMillis(),
                expiresAtMillis = null,
            )
        entries[token] = entry
        persist()
        return entry
    }

    @Synchronized
    fun resolve(token: String): DestinationResolution {
        val entry = entries[token] ?: return DestinationResolution.Unknown
        val expiry = entry.expiresAtMillis
        return if (expiry != null && clock.nowMillis() >= expiry) {
            DestinationResolution.Expired
        } else {
            DestinationResolution.Found(entry)
        }
    }

    @Synchronized
    fun saved(): List<DestinationTokenEntry> =
        entries.values
            .filter { it.expiresAtMillis == null }
            .sortedBy { it.source }

    @Synchronized
    fun allEntriesForTesting(): List<DestinationTokenEntry> = entries.values.toList()

    private fun pruneOldTombstones() {
        val oldestRetained = clock.nowMillis() - EXPIRED_TOMBSTONE_RETENTION_MILLIS
        entries.entries.removeAll { (_, value) ->
            value.expiresAtMillis?.let { it < oldestRetained } == true
        }
        persist()
    }

    private fun persist() = persistence.save(entries.values.toList())

    private companion object {
        const val EXPIRED_TOMBSTONE_RETENTION_MILLIS = 24 * 60 * 60 * 1000L
    }
}
