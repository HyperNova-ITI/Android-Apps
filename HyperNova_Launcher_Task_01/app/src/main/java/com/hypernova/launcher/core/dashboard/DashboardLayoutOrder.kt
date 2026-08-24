package com.hypernova.launcher.core.dashboard

enum class DashboardCard {
    CLIMATE,
    MEDIA,
    SETTINGS,
    PHONE,
    NAVIGATION,
}

/**
 * Product-approved HOME hierarchy.
 *
 * Phone and Settings remain available from the fixed bottom navigation bar
 * and are intentionally not rendered as HOME dashboard widgets.
 */
object DashboardLayoutOrder {

    val firstRow = listOf(
        DashboardCard.CLIMATE,
        DashboardCard.MEDIA,
    )

    val dominantRow = listOf(
        DashboardCard.NAVIGATION,
    )
}
