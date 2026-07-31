package com.hypernova.navigation.domain.repository

import com.hypernova.contracts.navigation.NavigationContract
import com.hypernova.navigation.domain.model.Place

object DestinationSearchPolicy {
    fun normalizedQuery(query: String?): String? =
        query?.trim()?.takeIf { it.isNotBlank() }

    fun limitResults(places: List<Place>): List<Place> =
        places.take(
            NavigationContract.MAX_DESTINATION_RESULTS
        )
}
