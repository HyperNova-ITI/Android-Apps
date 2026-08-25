package com.hypernova.contracts.navigation;

import com.hypernova.contracts.navigation.NavigationPlaceContactResult;

oneway interface INavigationPlaceContactCallback {
    void onResult(in NavigationPlaceContactResult result);
}
