package com.hypernova.navigation.service

data class RequestKey(val requestId: String, val operation: String)

sealed interface RequestRegistration<out R> {
    data object New : RequestRegistration<Nothing>
    data class InFlight<R>(val accepted: R) : RequestRegistration<R>
    data class Completed<R>(val result: R) : RequestRegistration<R>
    data object Conflict : RequestRegistration<Nothing>
}

class RequestRegistry<R, C>(
    private val retentionMillis: Long,
    private val clockMillis: () -> Long = System::currentTimeMillis,
) {
    private val entries = linkedMapOf<RequestKey, Entry<R, C>>()

    @Synchronized
    fun begin(
        key: RequestKey,
        fingerprint: String,
        accepted: R,
        callback: C,
    ): RequestRegistration<R> {
        removeExpired()
        val existing = entries[key]
        if (existing != null) {
            if (existing.fingerprint != fingerprint) return RequestRegistration.Conflict
            existing.finalResult?.let { return RequestRegistration.Completed(it) }
            if (callback !in existing.callbacks) existing.callbacks += callback
            return RequestRegistration.InFlight(existing.accepted)
        }
        entries[key] =
            Entry(
                fingerprint = fingerprint,
                accepted = accepted,
                callbacks = mutableListOf(callback),
                createdAtMillis = clockMillis(),
            )
        return RequestRegistration.New
    }

    @Synchronized
    fun complete(key: RequestKey, result: R): List<C>? {
        val entry = entries[key] ?: return null
        if (entry.finalResult != null) return null
        entry.finalResult = result
        entry.completedAtMillis = clockMillis()
        return entry.callbacks.toList().also { entry.callbacks.clear() }
    }

    @Synchronized
    fun size(): Int {
        removeExpired()
        return entries.size
    }

    private fun removeExpired() {
        val now = clockMillis()
        entries.entries.removeAll { (_, entry) ->
            now - (entry.completedAtMillis ?: entry.createdAtMillis) >= retentionMillis
        }
    }

    private data class Entry<R, C>(
        val fingerprint: String,
        val accepted: R,
        val callbacks: MutableList<C>,
        val createdAtMillis: Long,
        var completedAtMillis: Long? = null,
        var finalResult: R? = null,
    )
}
