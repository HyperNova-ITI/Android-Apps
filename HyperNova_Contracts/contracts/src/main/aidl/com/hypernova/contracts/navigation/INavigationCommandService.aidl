package com.hypernova.contracts.navigation;

import com.hypernova.contracts.navigation.INavigationCommandCallback;

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

    void cancelNavigation(
        String requestId,
        INavigationCommandCallback callback
    );
}
