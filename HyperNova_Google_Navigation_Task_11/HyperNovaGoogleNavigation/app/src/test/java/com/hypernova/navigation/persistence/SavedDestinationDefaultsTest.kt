package com.hypernova.navigation.persistence

import com.hypernova.contracts.navigation.NavigationContract
import com.hypernova.navigation.core.Clock
import com.hypernova.navigation.model.GoogleDestinationRecord
import org.junit.Assert.assertEquals
import org.junit.Test

class SavedDestinationDefaultsTest {
    @Test
    fun seedsHomeOnlyWhenItIsMissing() {
        val persistence = MemoryPersistence()
        val store = DestinationTokenStore(persistence, clock = Clock { 1_000L })

        SavedDestinationDefaults.seedMissingHome(store)
        SavedDestinationDefaults.seedMissingHome(store)

        val saved = store.saved()
        assertEquals(1, saved.size)
        assertEquals(NavigationContract.SOURCE_SAVED_HOME, saved.single().source)
        assertEquals("Home", saved.single().record.title)
        assertEquals("ChIJg9FTi-JbWBQRbCXcZWgh35w", saved.single().record.placeId)
    }

    @Test
    fun preservesAnExistingDriverHome() {
        val persistence = MemoryPersistence()
        val store = DestinationTokenStore(persistence, clock = Clock { 1_000L })
        store.putSaved(
            GoogleDestinationRecord(
                placeId = "driver-home",
                title = "Home",
                subtitle = "Driver configured",
                category = "Saved home",
                latitude = 30.0,
                longitude = 31.0,
            ),
            NavigationContract.SOURCE_SAVED_HOME,
        )

        SavedDestinationDefaults.seedMissingHome(store)

        assertEquals(1, store.saved().size)
        assertEquals("driver-home", store.saved().single().record.placeId)
    }

    private class MemoryPersistence : DestinationTokenPersistence {
        private var values = emptyList<DestinationTokenEntry>()
        override fun load(): List<DestinationTokenEntry> = values
        override fun save(entries: List<DestinationTokenEntry>) {
            values = entries.toList()
        }
    }
}
