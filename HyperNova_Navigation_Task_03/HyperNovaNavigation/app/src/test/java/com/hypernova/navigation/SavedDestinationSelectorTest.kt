package com.hypernova.navigation

import com.hypernova.navigation.domain.model.DestinationSource
import com.hypernova.navigation.domain.model.Place
import com.hypernova.navigation.domain.repository.SavedDestinationSelector
import org.junit.Assert.assertEquals
import org.junit.Test

class SavedDestinationSelectorTest {
    @Test
    fun homeMissing_startsWithWorkThenRecents() {
        val work = place("Work")
        val recent = place("Recent")

        val selected =
            SavedDestinationSelector.select(
                home = null,
                work = work,
                recents = listOf(recent),
                limit = 4
            )

        assertEquals(
            listOf(
                DestinationSource.SAVED_WORK,
                DestinationSource.SAVED_FAVORITE
            ),
            selected.map { it.source }
        )
    }

    @Test
    fun workMissing_startsWithHomeThenRecents() {
        val home = place("Home")
        val recent = place("Recent")

        val selected =
            SavedDestinationSelector.select(
                home = home,
                work = null,
                recents = listOf(recent),
                limit = 4
            )

        assertEquals(
            listOf(
                DestinationSource.SAVED_HOME,
                DestinationSource.SAVED_FAVORITE
            ),
            selected.map { it.source }
        )
    }

    @Test
    fun savedOrdering_isHomeWorkThenRecent_andMaximumFour() {
        val home = place("Home")
        val work = place("Work")
        val recents =
            (1..4).map { place("Recent $it") }

        val selected =
            SavedDestinationSelector.select(
                home = home,
                work = work,
                recents = recents,
                limit = 4
            )

        assertEquals(
            listOf(
                home,
                work,
                recents[0],
                recents[1]
            ),
            selected.map { it.place }
        )
    }

    private fun place(name: String): Place =
        Place(
            displayName = name,
            latitude = 30.0,
            longitude = 31.0,
            providerId = "provider:$name"
        )
}
