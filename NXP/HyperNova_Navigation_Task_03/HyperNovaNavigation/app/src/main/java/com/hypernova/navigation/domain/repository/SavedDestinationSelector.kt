package com.hypernova.navigation.domain.repository

import com.hypernova.navigation.domain.model.DestinationSource
import com.hypernova.navigation.domain.model.Place

data class SavedPlace(
    val place: Place,
    val source: DestinationSource
)

object SavedDestinationSelector {
    fun select(
        home: Place?,
        work: Place?,
        recents: List<Place>,
        limit: Int
    ): List<SavedPlace> {
        if (limit <= 0) return emptyList()

        val selected = mutableListOf<SavedPlace>()
        val seenPlaceIds = mutableSetOf<String>()

        fun add(
            place: Place?,
            source: DestinationSource
        ) {
            if (
                place != null &&
                selected.size < limit &&
                seenPlaceIds.add(place.id)
            ) {
                selected += SavedPlace(place, source)
            }
        }

        add(home, DestinationSource.SAVED_HOME)
        add(work, DestinationSource.SAVED_WORK)
        recents.forEach {
            add(it, DestinationSource.SAVED_FAVORITE)
        }

        return selected
    }
}
