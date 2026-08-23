package com.hypernova.contracts.navigation;

import com.hypernova.contracts.navigation.INavigationCommandCallback;
import com.hypernova.contracts.navigation.INavigationRoutePreviewCallback;
import com.hypernova.contracts.navigation.INavigationStatusCallback;

interface INavigationCommandService {
    int getApiVersion();

    void searchDestinations(
        String requestId,
        String query,
        INavigationCommandCallback callback
    );

    void getSavedDestinations(
        String requestId,
        INavigationCommandCallback callback
    );

    void setDestination(
        String requestId,
        String destinationId,
        INavigationCommandCallback callback
    );

    /** Starts the already prepared route; it never selects or changes a destination. */
    void startNavigation(
        String requestId,
        INavigationCommandCallback callback
    );

    void cancelNavigation(
        String requestId,
        INavigationCommandCallback callback
    );

    void getCurrentNavigationState(
        String requestId,
        INavigationCommandCallback callback
    );

    void getCurrentNavigationRoutePreview(
        String requestId,
        INavigationRoutePreviewCallback callback
    );

    /** Registers a read-only observer and immediately publishes current snapshots. */
    void registerNavigationStatusCallback(INavigationStatusCallback callback);

    /** Stops status delivery for a previously registered observer. */
    void unregisterNavigationStatusCallback(INavigationStatusCallback callback);
}
