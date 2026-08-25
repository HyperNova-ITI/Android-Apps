package com.hypernova.navigation.persistence

import com.hypernova.contracts.navigation.NavigationContract
import com.hypernova.navigation.model.GoogleDestinationRecord

/** Development-image fallback used only while no driver profile supplies a saved Home. */
object SavedDestinationDefaults {
    fun seedMissingHome(store: DestinationTokenStore) {
        if (store.saved().any { it.source == NavigationContract.SOURCE_SAVED_HOME }) return
        store.putSaved(DEVELOPMENT_HOME, NavigationContract.SOURCE_SAVED_HOME)
    }

    /**
     * Provider identity was verified through the production Google Places surface on 2026-08-25.
     * The public contract intentionally exposes the friendly role "Home" rather than a residence.
     */
    val DEVELOPMENT_HOME = GoogleDestinationRecord(
        placeId = "ChIJg9FTi-JbWBQRbCXcZWgh35w",
        title = "Home",
        subtitle = "ZED Sheikh Zayed, Giza",
        category = "Saved home",
        latitude = 30.0464687,
        longitude = 30.9998022,
    )
}
