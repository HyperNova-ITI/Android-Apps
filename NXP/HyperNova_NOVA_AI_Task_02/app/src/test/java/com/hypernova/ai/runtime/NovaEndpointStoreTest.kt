package com.hypernova.ai.runtime

import org.junit.Assert.assertEquals
import org.junit.Test

class NovaEndpointStoreTest {
    private val frozenPiHost = "192.168.0.20"

    @Test
    fun missingOrBlankHostUsesBuildDefault() {
        assertEquals(frozenPiHost, NovaEndpointStore.resolveHost(null, frozenPiHost))
        assertEquals(frozenPiHost, NovaEndpointStore.resolveHost("  ", frozenPiHost))
    }

    @Test
    fun historicalPiAddressesMigrateToBuildDefault() {
        assertEquals(
            frozenPiHost,
            NovaEndpointStore.resolveHost("192.168.1.32", frozenPiHost),
        )
        assertEquals(
            frozenPiHost,
            NovaEndpointStore.resolveHost("192.168.10.20", frozenPiHost),
        )
    }

    @Test
    fun intentionalCustomHostIsPreserved() {
        assertEquals(
            "10.0.2.2",
            NovaEndpointStore.resolveHost(" 10.0.2.2 ", frozenPiHost),
        )
    }
}
