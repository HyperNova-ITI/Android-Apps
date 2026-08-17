package com.hypernova.navigation

import com.hypernova.navigation.domain.model.GeoPoint
import com.hypernova.navigation.ui.NavigationFormatters
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.ZoneOffset

class NavigationFormattersTest {

    @Test
    fun arrivalTime_addsRealRouteDuration() {
        val formatted =
            NavigationFormatters.arrivalTime(
                durationSeconds = 3_600.0,
                nowEpochMillis = 0L,
                zoneId = ZoneOffset.UTC
            )

        assertEquals("01:00", formatted)
    }

    @Test
    fun arrivalTime_respectsTwelveHourDevicePreference() {
        val formatted =
            NavigationFormatters.arrivalTime(
                durationSeconds = 3_600.0,
                nowEpochMillis = 0L,
                zoneId = ZoneOffset.UTC,
                use24HourFormat = false
            )

        assertEquals("1:00 AM", formatted)
    }

    @Test
    fun distanceFormatting_usesAutomotiveFriendlyUnits() {
        assertEquals(
            "1.5 km",
            NavigationFormatters.routeDistance(1_500.0)
        )
        assertEquals(
            "450 m",
            NavigationFormatters.routeDistance(450.0)
        )
    }

    @Test
    fun straightLineDistance_isZeroForSameCoordinate() {
        val point = GeoPoint(30.07112, 31.02075)

        assertTrue(
            NavigationFormatters.straightLineDistance(
                point,
                point
            ) < 0.001
        )
    }
}
