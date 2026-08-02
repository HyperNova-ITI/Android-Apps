package com.hypernova.contracts.navigation;

import com.hypernova.contracts.navigation.NavigationRoutePreviewResult;

oneway interface INavigationRoutePreviewCallback {
    void onResult(in NavigationRoutePreviewResult result);
}
