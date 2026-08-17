package com.hypernova.navigation.data.persistence

object NavigationPreferenceContract {
    const val GUIDANCE_MUTED = "guidance_muted"
    const val HOME_DESTINATION = "home_destination"
    const val WORK_DESTINATION = "work_destination"
    const val RECENT_DESTINATIONS = "recent_destinations"
    const val LAST_SAFE_SCREEN = "last_safe_screen"
    const val LEGACY_LOCAL_THEME = "theme"

    val currentPersistedKeys =
        setOf(
            GUIDANCE_MUTED,
            HOME_DESTINATION,
            WORK_DESTINATION,
            RECENT_DESTINATIONS,
            LAST_SAFE_SCREEN
        )
}
