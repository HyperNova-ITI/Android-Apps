package com.hypernova.navigation.domain.repository

import java.util.concurrent.atomic.AtomicInteger

/**
 * Small thread-safe generation gate used to reject stale provider callbacks.
 * A new request or explicit cancellation advances the generation exactly once.
 */
internal class RequestGenerationGate {
    private val value = AtomicInteger()

    fun next(): Int = value.incrementAndGet()

    fun cancel(): Int = value.incrementAndGet()

    fun isCurrent(generation: Int): Boolean =
        generation == value.get()

    fun current(): Int = value.get()
}
