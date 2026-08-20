package com.hypernova.navigation.service

import com.hypernova.contracts.navigation.NavigationContract
import com.hypernova.navigation.model.GeoPoint
import com.hypernova.navigation.model.NavigationInitializationState
import com.hypernova.navigation.model.NavigationPhase
import com.hypernova.navigation.model.NavigationSessionState
import com.hypernova.navigation.model.VehiclePosition
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ContractProjectionTest {
    @Test
    fun previewIsPublicIdleAndGuidanceIsPublicActive() {
        assertEquals(
            NavigationContract.STATE_IDLE,
            ContractProjection.state(state(phase = NavigationPhase.PREVIEW_READY)),
        )
        assertEquals(
            NavigationContract.STATE_ACTIVE,
            ContractProjection.state(state(phase = NavigationPhase.GUIDING)),
        )
        assertEquals(
            NavigationContract.STATE_ACTIVE,
            ContractProjection.state(state(phase = NavigationPhase.REROUTING)),
        )
    }

    @Test
    fun missingConfigurationProjectsToPublicError() {
        assertEquals(
            NavigationContract.STATE_ERROR,
            ContractProjection.state(
                state(
                    initialization = NavigationInitializationState.CONFIGURATION_REQUIRED,
                    phase = NavigationPhase.IDLE,
                ),
            ),
        )
    }

    @Test
    fun googleRouteGeometryIsNotRepublishedForMapLibreRendering() {
        val projected =
            ContractProjection.preview(
                state(
                    phase = NavigationPhase.PREVIEW_READY,
                    routePoints = listOf(GeoPoint(30.0, 31.0), GeoPoint(30.1, 31.1)),
                ),
            )

        assertTrue(projected.routePoints.isEmpty())
        assertNull(projected.currentPosition)
    }

    @Test
    fun progressRejectsInvalidCoordinatesAndNormalizesSdkMeasurements() {
        val invalid =
            state(
                phase = NavigationPhase.GUIDING,
                position = vehicle(latitude = 91.0, longitude = 31.0),
            )
        assertNull(ContractProjection.progressSnapshot(invalid).currentPosition)

        val valid =
            state(
                phase = NavigationPhase.GUIDING,
                position =
                    vehicle(
                        latitude = 30.0,
                        longitude = 31.0,
                        bearing = -15f,
                        speed = -1f,
                    ),
            )
        val position = ContractProjection.progressSnapshot(valid).currentPosition
        requireNotNull(position)
        assertEquals(345f, position.bearingDegrees, 0f)
        assertTrue(position.speedMetersPerSecond.isNaN())
    }

    private fun state(
        initialization: NavigationInitializationState = NavigationInitializationState.READY_IDLE,
        phase: NavigationPhase,
        position: VehiclePosition? = null,
        routePoints: List<GeoPoint> = emptyList(),
    ): NavigationSessionState =
        NavigationSessionState(
            initialization = initialization,
            phase = phase,
            statusMessage = "test",
            routeId = if (phase == NavigationPhase.IDLE) "" else "route-test",
            vehiclePosition = position,
            routePoints = routePoints,
        )

    private fun vehicle(
        latitude: Double,
        longitude: Double,
        bearing: Float = 0f,
        speed: Float = 0f,
    ) = VehiclePosition(
        point = GeoPoint(latitude, longitude),
        bearingDegrees = bearing,
        speedMetersPerSecond = speed,
        timestampMillis = 123L,
    )
}
