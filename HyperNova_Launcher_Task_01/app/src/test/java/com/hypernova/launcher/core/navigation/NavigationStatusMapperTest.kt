package com.hypernova.launcher.core.navigation

import com.hypernova.contracts.HyperNovaContract
import com.hypernova.contracts.navigation.NavigationContract
import com.hypernova.contracts.navigation.NavigationCurrentPosition
import com.hypernova.contracts.navigation.NavigationDestination
import com.hypernova.contracts.navigation.NavigationProgressSnapshot
import com.hypernova.contracts.navigation.NavigationRoutePoint
import com.hypernova.contracts.navigation.NavigationRoutePreview
import com.hypernova.contracts.navigation.NavigationRoutePreviewResult
import com.hypernova.contracts.navigation.NavigationRouteSnapshot
import com.hypernova.contracts.navigation.NavigationResult
import com.hypernova.launcher.core.state.AppConnectionState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class NavigationStatusMapperTest {
    @Test
    fun `idle destination with route identity is a preview not active guidance`() {
        val preview =
            NavigationStatusMapper.withRouteSnapshot(
                NavigationStatusSnapshot(AppConnectionState.CONNECTING),
                NavigationRouteSnapshot(
                    "route-preview",
                    3L,
                    NavigationContract.STATE_IDLE,
                    NavigationDestination(
                        "preview-token",
                        NavigationContract.SOURCE_SEARCH,
                        "Maintenance Center",
                        "Smart Village",
                        "vehicle service",
                        -1L,
                    ),
                    600L,
                    2_000L,
                    NavigationRoutePreview.empty(),
                ),
            )

        assertTrue(preview.hasRoutePreview)
        assertEquals(NavigationRuntimeState.IDLE, preview.runtimeState)
        assertFalse(preview.copy(routeId = "").hasRoutePreview)
        assertFalse(preview.copy(runtimeState = NavigationRuntimeState.ACTIVE).hasRoutePreview)
    }

    @Test
    fun `maps every frozen navigation state`() {
        assertEquals(
            NavigationRuntimeState.IDLE,
            NavigationStatusMapper.runtimeState(NavigationContract.STATE_IDLE),
        )
        assertEquals(
            NavigationRuntimeState.CALCULATING,
            NavigationStatusMapper.runtimeState(NavigationContract.STATE_CALCULATING),
        )
        assertEquals(
            NavigationRuntimeState.ACTIVE,
            NavigationStatusMapper.runtimeState(NavigationContract.STATE_ACTIVE),
        )
        assertEquals(
            NavigationRuntimeState.ARRIVED,
            NavigationStatusMapper.runtimeState(NavigationContract.STATE_ARRIVED),
        )
        assertEquals(
            NavigationRuntimeState.ERROR,
            NavigationStatusMapper.runtimeState(NavigationContract.STATE_ERROR),
        )
    }

    @Test
    fun `unknown contract state is unavailable`() {
        assertEquals(
            NavigationRuntimeState.UNAVAILABLE,
            NavigationStatusMapper.runtimeState(Int.MAX_VALUE),
        )
    }

    @Test
    fun `maps confirmed active current-state result with real details`() {
        val result = NavigationResult(
            "launcher-request",
            NavigationContract.OP_GET_CURRENT_STATE,
            HyperNovaContract.STATUS_CONFIRMED,
            "Navigation is active.",
            HyperNovaContract.ERROR_NONE,
            emptyList(),
            NavigationDestination(
                "smart-village",
                NavigationContract.SOURCE_SEARCH,
                "Smart Village",
                "Cairo-Alexandria Desert Road",
                "business park",
                25_000L,
            ),
            NavigationContract.STATE_ACTIVE,
            1_925L,
            28_410L,
        )
        val previewResult = previewResult(
            state = NavigationContract.STATE_ACTIVE,
            preview = NavigationRoutePreview(
                listOf(
                    NavigationRoutePoint(30.0100, 31.0100),
                    NavigationRoutePoint(30.0400, 31.0150),
                    NavigationRoutePoint(30.0726, 31.0174),
                ),
                NavigationRoutePoint(30.0250, 31.0120),
            ),
        )

        val snapshot = NavigationStatusMapper.withRoutePreview(
            NavigationStatusMapper.fromResult(result),
            previewResult,
        )

        assertEquals(AppConnectionState.READY, snapshot.connectionState)
        assertEquals(NavigationRuntimeState.ACTIVE, snapshot.runtimeState)
        assertEquals("Smart Village", snapshot.destinationTitle)
        assertEquals("Cairo-Alexandria Desert Road", snapshot.destinationSubtitle)
        assertEquals(1_925L, snapshot.etaSeconds)
        assertEquals(28_410L, snapshot.distanceMeters)
        assertEquals(3, snapshot.routePoints.size)
        assertEquals(30.0100, snapshot.routePoints.first().latitude, 0.0)
        assertEquals(31.0174, snapshot.routePoints.last().longitude, 0.0)
        assertEquals(30.0250, snapshot.currentPosition?.latitude ?: 0.0, 0.0)
        assertNull(snapshot.errorMessage)
    }

    @Test
    fun `unavailable sentinels remain absent from launcher snapshot`() {
        val result = NavigationResult(
            "launcher-request",
            NavigationContract.OP_GET_CURRENT_STATE,
            HyperNovaContract.STATUS_CONFIRMED,
            "Navigation is idle.",
            HyperNovaContract.ERROR_NONE,
            emptyList(),
            null,
            NavigationContract.STATE_IDLE,
            -1L,
            -1L,
        )

        val snapshot = NavigationStatusMapper.fromResult(result)

        assertEquals(NavigationRuntimeState.IDLE, snapshot.runtimeState)
        assertNull(snapshot.destinationTitle)
        assertNull(snapshot.etaSeconds)
        assertNull(snapshot.distanceMeters)
        assertEquals(emptyList<Any>(), snapshot.routePoints)
        assertNull(snapshot.currentPosition)
    }

    @Test
    fun `current-state destination token is a route preview identity fallback`() {
        val result = NavigationResult(
            "launcher-request",
            NavigationContract.OP_GET_CURRENT_STATE,
            HyperNovaContract.STATUS_CONFIRMED,
            "Route preview ready",
            HyperNovaContract.ERROR_NONE,
            emptyList(),
            NavigationDestination(
                "google-place-token",
                NavigationContract.SOURCE_SEARCH,
                "TyrePro Continental Egypt",
                "Smart Village",
                "vehicle service",
                -1L,
            ),
            NavigationContract.STATE_IDLE,
            300L,
            2_500L,
        )

        val snapshot = NavigationStatusMapper.fromResult(result)

        assertEquals("google-place-token", snapshot.routeId)
        assertTrue(snapshot.hasRoutePreview)
        assertEquals(300L, snapshot.etaSeconds)
        assertEquals(2_500L, snapshot.distanceMeters)
    }

    @Test
    fun `non-current command destination is not treated as route identity`() {
        val result = NavigationResult(
            "search-request",
            NavigationContract.OP_SEARCH_DESTINATIONS,
            HyperNovaContract.STATUS_CONFIRMED,
            "Search complete",
            HyperNovaContract.ERROR_NONE,
            emptyList(),
            NavigationDestination(
                "search-token",
                NavigationContract.SOURCE_SEARCH,
                "A place",
                "Cairo",
                "place",
                -1L,
            ),
            NavigationContract.STATE_IDLE,
            -1L,
            -1L,
        )

        assertEquals("", NavigationStatusMapper.fromResult(result).routeId)
    }

    @Test
    fun `invalid coordinates are removed but two valid points remain usable`() {
        val result = previewResult(
            state = NavigationContract.STATE_ACTIVE,
            preview = NavigationRoutePreview(
                listOf(
                    NavigationRoutePoint(Double.NaN, 31.0),
                    NavigationRoutePoint(30.0, 31.0),
                    NavigationRoutePoint(91.0, 31.1),
                    NavigationRoutePoint(30.2, 31.2),
                    NavigationRoutePoint(30.3, 181.0),
                ),
                null,
            ),
        )

        val snapshot = NavigationStatusMapper.withRoutePreview(
            NavigationStatusMapper.fromResult(
                currentStateResult(NavigationContract.STATE_ACTIVE),
            ),
            result,
        )

        assertEquals(
            listOf(
                NavigationPreviewPoint(30.0, 31.0),
                NavigationPreviewPoint(30.2, 31.2),
            ),
            snapshot.routePoints,
        )
    }

    @Test
    fun `fewer than two valid points disables preview`() {
        val result = previewResult(
            state = NavigationContract.STATE_ACTIVE,
            preview = NavigationRoutePreview(
                listOf(
                    NavigationRoutePoint(30.0, 31.0),
                    NavigationRoutePoint(Double.POSITIVE_INFINITY, 31.2),
                ),
                NavigationRoutePoint(30.0, 31.0),
            ),
        )

        val snapshot = NavigationStatusMapper.withRoutePreview(
            NavigationStatusMapper.fromResult(
                currentStateResult(NavigationContract.STATE_ACTIVE),
            ),
            result,
        )

        assertEquals(emptyList<Any>(), snapshot.routePoints)
        assertNull(snapshot.currentPosition)
    }

    @Test
    fun `idle state clears preview even if a producer sends geometry`() {
        val result = previewResult(
            state = NavigationContract.STATE_IDLE,
            preview = NavigationRoutePreview(
                listOf(
                    NavigationRoutePoint(30.0, 31.0),
                    NavigationRoutePoint(30.2, 31.2),
                ),
                null,
            ),
        )

        assertEquals(
            emptyList<Any>(),
            NavigationStatusMapper.withRoutePreview(
                NavigationStatusMapper.fromResult(
                    currentStateResult(NavigationContract.STATE_IDLE),
                ),
                result,
            ).routePoints,
        )
    }

    @Test
    fun `versioned route snapshot maps full route without a progress position`() {
        val mapped = NavigationStatusMapper.withRouteSnapshot(
            NavigationStatusSnapshot(AppConnectionState.CONNECTING),
            routeSnapshot(routeVersion = 4L),
        )

        assertEquals(AppConnectionState.READY, mapped.connectionState)
        assertEquals("route-valeo", mapped.routeId)
        assertEquals(4L, mapped.routeVersion)
        assertEquals("Valeo", mapped.destinationTitle)
        assertEquals(2, mapped.routePoints.size)
        assertNull(mapped.currentPosition)
    }

    @Test
    fun `progress maps real position and normalizes bearing`() {
        val route = NavigationStatusMapper.withRouteSnapshot(
            NavigationStatusSnapshot(AppConnectionState.CONNECTING),
            routeSnapshot(routeVersion = 4L),
        )
        val mapped = NavigationStatusMapper.withProgressSnapshot(
            route,
            NavigationProgressSnapshot(
                "route-valeo",
                4L,
                NavigationContract.STATE_ACTIVE,
                12L,
                NavigationCurrentPosition(30.05, 31.05, 725f, 10f, 100_000L),
                700L,
            ),
        )

        assertEquals(NavigationPreviewPoint(30.05, 31.05), mapped.currentPosition)
        assertEquals(5f, mapped.currentBearingDegrees)
        assertEquals(10f, mapped.currentSpeedMetersPerSecond)
        assertEquals(100_000L, mapped.currentPositionTimestampMillis)
        assertEquals(12L, mapped.progressSequenceNumber)
        assertEquals(700L, mapped.remainingDistanceMeters)
    }

    @Test
    fun `invalid progress position is removed without inventing a marker`() {
        val route = NavigationStatusMapper.withRouteSnapshot(
            NavigationStatusSnapshot(AppConnectionState.CONNECTING),
            routeSnapshot(routeVersion = 4L),
        )
        val mapped = NavigationStatusMapper.withProgressSnapshot(
            route,
            NavigationProgressSnapshot(
                "route-valeo",
                4L,
                NavigationContract.STATE_ACTIVE,
                12L,
                NavigationCurrentPosition(95.0, 31.05, Float.NaN, Float.NaN, 100_000L),
                -1L,
            ),
        )

        assertNull(mapped.currentPosition)
        assertNull(mapped.currentBearingDegrees)
        assertNull(mapped.currentSpeedMetersPerSecond)
    }

    @Test
    fun `stale sequence and mismatched route version are ignored`() {
        val route = NavigationStatusMapper.withRouteSnapshot(
            NavigationStatusSnapshot(AppConnectionState.CONNECTING),
            routeSnapshot(routeVersion = 4L),
        ).copy(progressSequenceNumber = 20L)
        val stale = NavigationProgressSnapshot(
            "route-valeo",
            4L,
            NavigationContract.STATE_ACTIVE,
            19L,
            null,
            -1L,
        )
        val mismatched = NavigationProgressSnapshot(
            "route-new",
            5L,
            NavigationContract.STATE_ACTIVE,
            21L,
            null,
            -1L,
        )

        assertSame(route, NavigationStatusMapper.withProgressSnapshot(route, stale))
        assertSame(route, NavigationStatusMapper.withProgressSnapshot(route, mismatched))
    }

    @Test
    fun `new idle route version clears geometry and marker`() {
        val active = NavigationStatusMapper.withProgressSnapshot(
            NavigationStatusMapper.withRouteSnapshot(
                NavigationStatusSnapshot(AppConnectionState.CONNECTING),
                routeSnapshot(routeVersion = 4L),
            ),
            NavigationProgressSnapshot(
                "route-valeo",
                4L,
                NavigationContract.STATE_ACTIVE,
                12L,
                NavigationCurrentPosition(30.05, 31.05, 90f, 10f, 100_000L),
                700L,
            ),
        )
        val idle = NavigationStatusMapper.withRouteSnapshot(
            active,
            NavigationRouteSnapshot(
                "",
                5L,
                NavigationContract.STATE_IDLE,
                null,
                -1L,
                -1L,
                NavigationRoutePreview.empty(),
            ),
        )

        assertEquals(NavigationRuntimeState.IDLE, idle.runtimeState)
        assertEquals(emptyList<Any>(), idle.routePoints)
        assertNull(idle.currentPosition)
        assertNull(idle.currentBearingDegrees)
    }

    private fun currentStateResult(state: Int): NavigationResult =
        NavigationResult(
            "launcher-request",
            NavigationContract.OP_GET_CURRENT_STATE,
            HyperNovaContract.STATUS_CONFIRMED,
            "Current Navigation state.",
            HyperNovaContract.ERROR_NONE,
            emptyList(),
            null,
            state,
            -1L,
            -1L,
        )

    private fun previewResult(
        state: Int,
        preview: NavigationRoutePreview,
    ): NavigationRoutePreviewResult =
        NavigationRoutePreviewResult(
            "launcher-preview-request",
            HyperNovaContract.STATUS_CONFIRMED,
            "Route preview available.",
            HyperNovaContract.ERROR_NONE,
            state,
            preview,
        )

    private fun routeSnapshot(routeVersion: Long): NavigationRouteSnapshot =
        NavigationRouteSnapshot(
            "route-valeo",
            routeVersion,
            NavigationContract.STATE_ACTIVE,
            NavigationDestination(
                "valeo",
                NavigationContract.SOURCE_SEARCH,
                "Valeo",
                "Smart Village",
                "office",
                1_400L,
            ),
            240L,
            1_400L,
            NavigationRoutePreview(
                listOf(
                    NavigationRoutePoint(30.0, 31.0),
                    NavigationRoutePoint(30.1, 31.1),
                ),
                null,
            ),
        )
}
