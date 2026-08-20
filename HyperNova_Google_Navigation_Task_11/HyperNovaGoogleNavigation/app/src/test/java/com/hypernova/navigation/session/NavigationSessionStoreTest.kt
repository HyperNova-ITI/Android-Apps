package com.hypernova.navigation.session

import com.hypernova.contracts.navigation.NavigationContract
import com.hypernova.navigation.model.FailureKind
import com.hypernova.navigation.model.GeoPoint
import com.hypernova.navigation.model.GoogleDestinationRecord
import com.hypernova.navigation.model.NavigationInitializationState
import com.hypernova.navigation.model.NavigationPhase
import com.hypernova.navigation.model.NavigationSessionState
import com.hypernova.navigation.model.RouteData
import com.hypernova.navigation.model.VehiclePosition
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class NavigationSessionStoreTest {
    private val destination =
        GoogleDestinationRecord(
            placeId = "place_A",
            title = "Title",
            subtitle = "Subtitle",
            category = "Category",
            latitude = 30.0,
            longitude = 31.0,
        )
    private val route =
        RouteData(
            points = listOf(GeoPoint(0.0, 0.0), GeoPoint(1.0, 1.0)),
            etaSeconds = 300L,
            distanceMeters = 5_000L,
        )

    @Test
    fun previewThenGuidanceThenRerouteThenArrivalThenCancelLifecycle() {
        val store = store()

        store.calculating("token-search", destination, NavigationContract.SOURCE_SEARCH)
        val calculating = store.current()
        assertEquals(NavigationPhase.CALCULATING, calculating.phase)
        assertTrue(calculating.routeId.isNotBlank())
        assertEquals("token-search", calculating.selectedToken)
        assertEquals(destination, calculating.selectedDestination)
        assertEquals(NavigationContract.SOURCE_SEARCH, calculating.selectedSource)
        assertEquals(1L, calculating.routeVersion)
        assertEquals(1L, calculating.progressSequence)
        assertEquals(emptyList<GeoPoint>(), calculating.routePoints)
        assertEquals(-1L, calculating.etaSeconds)
        assertEquals(-1L, calculating.distanceMeters)
        assertNull(calculating.vehiclePosition)
        assertFalse(calculating.simulated)

        val preview = store.routeReady(route)
        assertEquals(NavigationPhase.PREVIEW_READY, preview.phase)
        assertEquals("Route preview ready", preview.statusMessage)
        assertEquals(2L, preview.routeVersion)
        assertEquals(2L, preview.progressSequence)
        assertEquals(route.points, preview.routePoints)
        assertEquals(300L, preview.etaSeconds)
        assertEquals(5_000L, preview.distanceMeters)

        store.guidanceStarted()
        val guiding = store.current()
        assertEquals(NavigationPhase.GUIDING, guiding.phase)
        assertEquals("Guidance active", guiding.statusMessage)
        assertEquals(3L, guiding.routeVersion)
        assertEquals(3L, guiding.progressSequence)

        store.progress(etaSeconds = 250L, distanceMeters = 4_000L)
        val progressed = store.current()
        assertEquals(3L, progressed.routeVersion)
        assertEquals(4L, progressed.progressSequence)
        assertEquals(250L, progressed.etaSeconds)
        assertEquals(4_000L, progressed.distanceMeters)

        store.rerouting()
        val rerouting = store.current()
        assertEquals(NavigationPhase.REROUTING, rerouting.phase)
        assertEquals("Rerouting…", rerouting.statusMessage)
        assertEquals(3L, rerouting.routeVersion)
        assertEquals(5L, rerouting.progressSequence)

        val replacement = route.copy(etaSeconds = 120L, distanceMeters = 800L)
        store.routeChanged(replacement)
        val changed = store.current()
        assertEquals(NavigationPhase.GUIDING, changed.phase)
        assertEquals("Route updated", changed.statusMessage)
        assertEquals(4L, changed.routeVersion)
        assertEquals(6L, changed.progressSequence)
        assertEquals(120L, changed.etaSeconds)
        assertEquals(800L, changed.distanceMeters)

        val position =
            VehiclePosition(
                point = GeoPoint(1.5, 1.5),
                bearingDegrees = 45f,
                speedMetersPerSecond = 10f,
                timestampMillis = 100L,
            )
        store.position(position)
        assertEquals(7L, store.current().progressSequence)
        assertEquals(position, store.current().vehiclePosition)

        store.progress(etaSeconds = 90L, distanceMeters = 500L)
        val keptPosition = store.current()
        assertEquals(8L, keptPosition.progressSequence)
        assertEquals(position, keptPosition.vehiclePosition)

        store.arrived()
        val arrived = store.current()
        assertEquals(NavigationPhase.ARRIVED, arrived.phase)
        assertEquals("Arrived", arrived.statusMessage)
        assertEquals(5L, arrived.routeVersion)
        assertEquals(9L, arrived.progressSequence)
        assertEquals(0L, arrived.etaSeconds)
        assertEquals(0L, arrived.distanceMeters)

        store.cancelled()
        val cancelled = store.current()
        assertEquals(NavigationPhase.IDLE, cancelled.phase)
        assertEquals("Navigation idle", cancelled.statusMessage)
        assertEquals("", cancelled.errorCode)
        assertNull(cancelled.selectedToken)
        assertNull(cancelled.selectedDestination)
        assertEquals(0, cancelled.selectedSource)
        assertEquals("", cancelled.routeId)
        assertEquals(6L, cancelled.routeVersion)
        assertEquals(10L, cancelled.progressSequence)
        assertEquals(emptyList<GeoPoint>(), cancelled.routePoints)
        assertEquals(-1L, cancelled.etaSeconds)
        assertEquals(-1L, cancelled.distanceMeters)
        assertNull(cancelled.vehiclePosition)
        assertFalse(cancelled.simulated)
    }

    @Test
    fun progressAdvancesSequenceButNotRouteVersion() {
        val store = store()
        store.calculating("token", destination, NavigationContract.SOURCE_SEARCH)
        store.routeReady(route)
        store.guidanceStarted()
        val before = store.current()

        store.progress(10L, 100L)

        val after = store.current()
        assertEquals(before.routeVersion, after.routeVersion)
        assertEquals(before.progressSequence + 1, after.progressSequence)
    }

    @Test
    fun reroutingAdvancesSequenceButNotRouteVersion() {
        val store = store()
        store.calculating("token", destination, NavigationContract.SOURCE_SEARCH)
        store.routeReady(route)
        store.guidanceStarted()
        val before = store.current()

        store.rerouting()

        val after = store.current()
        assertEquals(NavigationPhase.REROUTING, after.phase)
        assertEquals(before.routeVersion, after.routeVersion)
        assertEquals(before.progressSequence + 1, after.progressSequence)
    }

    @Test
    fun searchingOnlyTransitionsFromIdleOrSearching() {
        val store = store()
        store.searching(true)
        assertEquals(NavigationPhase.SEARCHING, store.current().phase)
        assertEquals("Searching Google Places…", store.current().statusMessage)

        store.searching(false)
        assertEquals(NavigationPhase.IDLE, store.current().phase)
        assertEquals("Ready", store.current().statusMessage)

        store.calculating("token", destination, NavigationContract.SOURCE_SEARCH)
        store.searching(true)
        assertEquals(NavigationPhase.CALCULATING, store.current().phase)
    }

    @Test
    fun guidanceStartedOnlyFromPreviewReady() {
        val store = store()
        store.calculating("token", destination, NavigationContract.SOURCE_SEARCH)

        store.guidanceStarted()

        assertEquals(NavigationPhase.CALCULATING, store.current().phase)
    }

    @Test
    fun reroutingOnlyFromGuidance() {
        val store = store()
        store.calculating("token", destination, NavigationContract.SOURCE_SEARCH)
        store.routeReady(route)

        store.rerouting()

        assertEquals(NavigationPhase.PREVIEW_READY, store.current().phase)
    }

    @Test
    fun sessionTransitionsAreNoOpsWhileRouteIdIsBlank() {
        val store = store()

        store.routeChanged(route)
        store.progress(10L, 100L)
        store.position(vehiclePosition())
        store.arrived()

        val state = store.current()
        assertEquals(NavigationPhase.IDLE, state.phase)
        assertEquals(0L, state.routeVersion)
        assertEquals(0L, state.progressSequence)
    }

    @Test
    fun routeChangedDuringPreviewKeepsPreviewReadyAndAdvancesRoute() {
        val store = store()
        store.calculating("token", destination, NavigationContract.SOURCE_SEARCH)
        store.routeReady(route)
        val versionBefore = store.current().routeVersion

        store.routeChanged(route.copy(etaSeconds = 999L))

        val state = store.current()
        assertEquals(NavigationPhase.PREVIEW_READY, state.phase)
        assertEquals(versionBefore + 1, state.routeVersion)
        assertEquals(999L, state.etaSeconds)
    }

    @Test
    fun routeFailureMovesToErrorAndClearsRouteDetails() {
        val store = store()
        store.calculating("token", destination, NavigationContract.SOURCE_SEARCH)
        store.routeReady(route)
        store.guidanceStarted()

        store.routeFailure(FailureKind.NETWORK, "Network down")

        val state = store.current()
        assertEquals(NavigationPhase.ERROR, state.phase)
        assertEquals("Network down", state.statusMessage)
        assertEquals(FailureKind.NETWORK.name, state.errorCode)
        assertEquals(emptyList<GeoPoint>(), state.routePoints)
        assertEquals(-1L, state.etaSeconds)
        assertEquals(-1L, state.distanceMeters)
    }

    @Test
    fun simulatedGuidanceSurfacesSimulatedStatusMessages() {
        val store = store()
        store.calculating("token", destination, NavigationContract.SOURCE_SEARCH)
        store.routeReady(route, simulated = true)

        store.guidanceStarted()
        assertEquals("Simulated guidance", store.current().statusMessage)

        store.arrived()
        assertEquals("Simulated arrival", store.current().statusMessage)
    }

    @Test
    fun initializationAdvancesVersionAndSequenceAndRecordsErrorCode() {
        val store = store()
        assertEquals(0L, store.current().routeVersion)

        store.initialization(
            NavigationInitializationState.GOOGLE_SERVICES_UNAVAILABLE,
            "Services unavailable",
            "GOOGLE_PLAY_SERVICES_UNAVAILABLE",
        )

        val state = store.current()
        assertEquals(NavigationInitializationState.GOOGLE_SERVICES_UNAVAILABLE, state.initialization)
        assertEquals("Services unavailable", state.statusMessage)
        assertEquals("GOOGLE_PLAY_SERVICES_UNAVAILABLE", state.errorCode)
        assertEquals(1L, state.routeVersion)
        assertEquals(1L, state.progressSequence)
    }

    @Test
    fun progressClampsValuesAtMinusOne() {
        val store = store()
        store.calculating("token", destination, NavigationContract.SOURCE_SEARCH)
        store.routeReady(route)

        store.progress(etaSeconds = -5L, distanceMeters = -20L)

        assertEquals(-1L, store.current().etaSeconds)
        assertEquals(-1L, store.current().distanceMeters)
    }

    private fun store(): NavigationSessionStore =
        NavigationSessionStore(
            NavigationSessionState(
                initialization = NavigationInitializationState.INITIALIZING,
                statusMessage = "Initializing",
            ),
        )

    private fun vehiclePosition(): VehiclePosition =
        VehiclePosition(
            point = GeoPoint(1.0, 1.0),
            bearingDegrees = 0f,
            speedMetersPerSecond = 0f,
            timestampMillis = 0L,
        )
}