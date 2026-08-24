package com.hypernova.launcher.core.dashboard

import org.junit.Assert.assertEquals
import org.junit.Test

class DashboardLayoutOrderTest {

    @Test
    fun `dashboard keeps only driving priority widgets on home`() {
        assertEquals(
            listOf(
                DashboardCard.CLIMATE,
                DashboardCard.MEDIA,
            ),
            DashboardLayoutOrder.firstRow,
        )

        assertEquals(
            listOf(DashboardCard.NAVIGATION),
            DashboardLayoutOrder.dominantRow,
        )
    }
}
