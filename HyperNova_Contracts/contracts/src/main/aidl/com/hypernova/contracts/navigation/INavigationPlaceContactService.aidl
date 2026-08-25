package com.hypernova.contracts.navigation;

import com.hypernova.contracts.navigation.INavigationPlaceContactCallback;

/** Additive read-only boundary; the frozen Navigation command interface remains unchanged. */
interface INavigationPlaceContactService {
    int getApiVersion();

    void getDestinationContact(
        String requestId,
        String destinationId,
        INavigationPlaceContactCallback callback
    );
}
