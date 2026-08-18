package com.hypernova.contracts.navigation;

import com.hypernova.contracts.navigation.NavigationProgressSnapshot;
import com.hypernova.contracts.navigation.NavigationRouteSnapshot;

/** Read-only, one-way Navigation observer. */
oneway interface INavigationStatusCallback {
    /** Sent on registration and whenever the active route identity changes. */
    void onRouteSnapshot(in NavigationRouteSnapshot snapshot);

    /** Lightweight position/progress update; never contains route geometry. */
    void onProgressSnapshot(in NavigationProgressSnapshot snapshot);
}
