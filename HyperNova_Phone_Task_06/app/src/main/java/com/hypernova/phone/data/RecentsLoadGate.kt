package com.hypernova.phone.data

/** Small, thread-confined request gate: coalesces taps and rejects stale provider results. */
class RecentsLoadGate {
    private var generation = 0L
    private var inFlight = false

    fun begin(force: Boolean = false): Long? {
        if (inFlight && !force) return null
        inFlight = true
        return ++generation
    }

    fun isCurrent(token: Long): Boolean = token == generation
    fun complete(token: Long) { if (isCurrent(token)) inFlight = false }
}
