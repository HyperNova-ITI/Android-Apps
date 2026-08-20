package com.hypernova.launcher.core.dashboard

import org.junit.Assert.assertEquals
import org.junit.Test

class DashboardLayoutOrderTest {
    @Test
    fun `dashboard uses the approved production card hierarchy`() {
        assertEquals(
            listOf(DashboardCard.CLIMATE, DashboardCard.MEDIA),
            DashboardLayoutOrder.firstRow,
        )
        assertEquals(
            listOf(DashboardCard.SETTINGS, DashboardCard.PHONE),
            DashboardLayoutOrder.secondRow,
        )
        assertEquals(
            listOf(DashboardCard.NAVIGATION),
            DashboardLayoutOrder.dominantRow,
        )
    }
}
