package com.hypernova.launcher.core.dashboard

enum class DashboardCard {
    CLIMATE,
    MEDIA,
    SETTINGS,
    PHONE,
    NAVIGATION,
}

/** Product-approved HOME hierarchy, kept testable outside Android Views. */
object DashboardLayoutOrder {
    val firstRow = listOf(DashboardCard.CLIMATE, DashboardCard.MEDIA)
    val secondRow = listOf(DashboardCard.SETTINGS, DashboardCard.PHONE)
    val dominantRow = listOf(DashboardCard.NAVIGATION)
}
