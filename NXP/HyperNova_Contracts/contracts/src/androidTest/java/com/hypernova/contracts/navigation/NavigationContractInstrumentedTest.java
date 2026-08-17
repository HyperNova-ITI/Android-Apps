package com.hypernova.contracts.navigation;

import android.os.Parcel;

import androidx.test.ext.junit.runners.AndroidJUnit4;

import com.hypernova.contracts.HyperNovaContract;

import org.junit.Test;
import org.junit.runner.RunWith;

import java.util.Arrays;
import java.util.Collections;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

/** Device-side ABI checks for the Navigation status extension and parcelables. */
@RunWith(AndroidJUnit4.class)
public final class NavigationContractInstrumentedTest {
    @Test
    public void testCurrentStateOperationAndAidlMethodExist() throws Exception {
        assertEquals("get_current_state", NavigationContract.OP_GET_CURRENT_STATE);
        assertNotNull(
                INavigationCommandService.class.getMethod(
                        "getCurrentNavigationState",
                        String.class,
                        INavigationCommandCallback.class
                )
        );
        assertEquals("get_route_preview", NavigationContract.OP_GET_ROUTE_PREVIEW);
        assertNotNull(
                INavigationCommandService.class.getMethod(
                        "getCurrentNavigationRoutePreview",
                        String.class,
                        INavigationRoutePreviewCallback.class
                )
        );
        assertNotNull(
                INavigationCommandService.class.getMethod(
                        "registerNavigationStatusCallback",
                        INavigationStatusCallback.class
                )
        );
        assertNotNull(
                INavigationCommandService.class.getMethod(
                        "unregisterNavigationStatusCallback",
                        INavigationStatusCallback.class
                )
        );
    }

    @Test
    public void testRoutePointParcelRoundTripPreservesCoordinates() {
        NavigationRoutePoint original = new NavigationRoutePoint(30.0726, 31.0174);
        Parcel parcel = Parcel.obtain();

        try {
            original.writeToParcel(parcel, 0);
            parcel.setDataPosition(0);
            NavigationRoutePoint restored =
                    NavigationRoutePoint.CREATOR.createFromParcel(parcel);

            assertEquals(original.getLatitude(), restored.getLatitude(), 0.0);
            assertEquals(original.getLongitude(), restored.getLongitude(), 0.0);
        } finally {
            parcel.recycle();
        }
    }

    @Test
    public void testNavigationResultParcelRoundTripPreservesOriginalWireFormat() {
        NavigationDestination destination =
                new NavigationDestination(
                        "smart-village",
                        NavigationContract.SOURCE_SEARCH,
                        "Smart Village",
                        "Cairo-Alexandria Desert Road",
                        "business park",
                        25_000L
                );
        NavigationResult original =
                new NavigationResult(
                        "status-request",
                        NavigationContract.OP_GET_CURRENT_STATE,
                        HyperNovaContract.STATUS_CONFIRMED,
                        "Navigation is active.",
                        HyperNovaContract.ERROR_NONE,
                        Collections.emptyList(),
                        destination,
                        NavigationContract.STATE_ACTIVE,
                        1_925L,
                        28_410L
                );
        Parcel parcel = Parcel.obtain();

        try {
            original.writeToParcel(parcel, 0);
            parcel.setDataPosition(0);
            NavigationResult restored =
                    NavigationResult.CREATOR.createFromParcel(parcel);

            assertEquals(original.getRequestId(), restored.getRequestId());
            assertEquals(original.getOperation(), restored.getOperation());
            assertEquals(original.getStatus(), restored.getStatus());
            assertEquals(original.getNavigationState(), restored.getNavigationState());
            assertEquals(original.getEtaSeconds(), restored.getEtaSeconds());
            assertEquals(original.getDistanceMeters(), restored.getDistanceMeters());
            assertNotNull(restored.getSelectedDestination());
            assertEquals(
                    original.getSelectedDestination().getTitle(),
                    restored.getSelectedDestination().getTitle()
            );
        } finally {
            parcel.recycle();
        }
    }

    @Test
    public void testRoutePreviewResultParcelRoundTripPreservesGeometry() {
        NavigationRoutePreviewResult original =
                new NavigationRoutePreviewResult(
                        "preview-request",
                        HyperNovaContract.STATUS_CONFIRMED,
                        "Route preview available.",
                        HyperNovaContract.ERROR_NONE,
                        NavigationContract.STATE_ACTIVE,
                        new NavigationRoutePreview(
                                Arrays.asList(
                                        new NavigationRoutePoint(30.0100, 31.0100),
                                        new NavigationRoutePoint(30.0400, 31.0150),
                                        new NavigationRoutePoint(30.0726, 31.0174)
                                ),
                                new NavigationRoutePoint(30.0250, 31.0120)
                        )
                );
        Parcel parcel = Parcel.obtain();

        try {
            original.writeToParcel(parcel, 0);
            parcel.setDataPosition(0);
            NavigationRoutePreviewResult restored =
                    NavigationRoutePreviewResult.CREATOR.createFromParcel(parcel);

            assertEquals(original.getRequestId(), restored.getRequestId());
            assertEquals(original.getStatus(), restored.getStatus());
            assertEquals(original.getNavigationState(), restored.getNavigationState());
            assertEquals(3, restored.getRoutePreview().getRoutePoints().size());
            assertEquals(
                    30.0726,
                    restored.getRoutePreview().getRoutePoints().get(2).getLatitude(),
                    0.0
            );
            assertNotNull(restored.getRoutePreview().getCurrentPosition());
            assertEquals(
                    31.0120,
                    restored.getRoutePreview().getCurrentPosition().getLongitude(),
                    0.0
            );
        } finally {
            parcel.recycle();
        }
    }

    @Test
    public void testEmptyRoutePreviewParcelRoundTripStaysEmpty() {
        NavigationRoutePreview original = NavigationRoutePreview.empty();
        Parcel parcel = Parcel.obtain();

        try {
            original.writeToParcel(parcel, 0);
            parcel.setDataPosition(0);
            NavigationRoutePreview restored =
                    NavigationRoutePreview.CREATOR.createFromParcel(parcel);

            assertTrue(restored.getRoutePoints().isEmpty());
            assertNull(restored.getCurrentPosition());
        } finally {
            parcel.recycle();
        }
    }

    @Test
    public void testCurrentPositionParcelRoundTripPreservesMotionData() {
        NavigationCurrentPosition original =
                new NavigationCurrentPosition(30.0726, 31.0174, 271.5f, 13.25f, 456_789L);
        Parcel parcel = Parcel.obtain();

        try {
            original.writeToParcel(parcel, 0);
            parcel.setDataPosition(0);
            NavigationCurrentPosition restored =
                    NavigationCurrentPosition.CREATOR.createFromParcel(parcel);

            assertEquals(original.getLatitude(), restored.getLatitude(), 0.0);
            assertEquals(original.getLongitude(), restored.getLongitude(), 0.0);
            assertEquals(original.getBearingDegrees(), restored.getBearingDegrees(), 0.0f);
            assertEquals(
                    original.getSpeedMetersPerSecond(),
                    restored.getSpeedMetersPerSecond(),
                    0.0f
            );
            assertEquals(original.getTimestampMillis(), restored.getTimestampMillis());
        } finally {
            parcel.recycle();
        }
    }

    @Test
    public void testRouteSnapshotParcelRoundTripPreservesIdentityAndGeometry() {
        NavigationDestination destination =
                new NavigationDestination(
                        "valeo",
                        NavigationContract.SOURCE_SEARCH,
                        "Valeo",
                        "Smart Village",
                        "office",
                        25_000L
                );
        NavigationRouteSnapshot original =
                new NavigationRouteSnapshot(
                        "route-42",
                        7L,
                        NavigationContract.STATE_ACTIVE,
                        destination,
                        240L,
                        1_400L,
                        new NavigationRoutePreview(
                                Arrays.asList(
                                        new NavigationRoutePoint(30.0100, 31.0100),
                                        new NavigationRoutePoint(30.0726, 31.0174)
                                ),
                                null
                        )
                );
        Parcel parcel = Parcel.obtain();

        try {
            original.writeToParcel(parcel, 0);
            parcel.setDataPosition(0);
            NavigationRouteSnapshot restored =
                    NavigationRouteSnapshot.CREATOR.createFromParcel(parcel);

            assertEquals("route-42", restored.getRouteId());
            assertEquals(7L, restored.getRouteVersion());
            assertEquals(NavigationContract.STATE_ACTIVE, restored.getNavigationState());
            assertNotNull(restored.getSelectedDestination());
            assertEquals("Valeo", restored.getSelectedDestination().getTitle());
            assertEquals(2, restored.getRoutePreview().getRoutePoints().size());
            assertNull(restored.getRoutePreview().getCurrentPosition());
        } finally {
            parcel.recycle();
        }
    }

    @Test
    public void testProgressSnapshotParcelRoundTripSupportsAvailablePosition() {
        NavigationProgressSnapshot original =
                new NavigationProgressSnapshot(
                        "route-42",
                        7L,
                        NavigationContract.STATE_ACTIVE,
                        19L,
                        new NavigationCurrentPosition(
                                30.0726,
                                31.0174,
                                89.0f,
                                10.0f,
                                987_654L
                        ),
                        900L
                );
        Parcel parcel = Parcel.obtain();

        try {
            original.writeToParcel(parcel, 0);
            parcel.setDataPosition(0);
            NavigationProgressSnapshot restored =
                    NavigationProgressSnapshot.CREATOR.createFromParcel(parcel);

            assertEquals("route-42", restored.getRouteId());
            assertEquals(7L, restored.getRouteVersion());
            assertEquals(19L, restored.getSequenceNumber());
            assertNotNull(restored.getCurrentPosition());
            assertEquals(89.0f, restored.getCurrentPosition().getBearingDegrees(), 0.0f);
            assertEquals(900L, restored.getRemainingDistanceMeters());
        } finally {
            parcel.recycle();
        }
    }

    @Test
    public void testProgressSnapshotParcelRoundTripSupportsUnavailablePosition() {
        NavigationProgressSnapshot original =
                new NavigationProgressSnapshot(
                        "",
                        8L,
                        NavigationContract.STATE_IDLE,
                        20L,
                        null,
                        -1L
                );
        Parcel parcel = Parcel.obtain();

        try {
            original.writeToParcel(parcel, 0);
            parcel.setDataPosition(0);
            NavigationProgressSnapshot restored =
                    NavigationProgressSnapshot.CREATOR.createFromParcel(parcel);

            assertEquals("", restored.getRouteId());
            assertEquals(8L, restored.getRouteVersion());
            assertEquals(NavigationContract.STATE_IDLE, restored.getNavigationState());
            assertNull(restored.getCurrentPosition());
            assertEquals(-1L, restored.getRemainingDistanceMeters());
        } finally {
            parcel.recycle();
        }
    }
}
