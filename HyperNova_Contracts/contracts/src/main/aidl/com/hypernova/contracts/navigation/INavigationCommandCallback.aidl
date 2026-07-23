package com.hypernova.contracts.navigation;

import com.hypernova.contracts.navigation.NavigationResult;

oneway interface INavigationCommandCallback {
    void onResult(in NavigationResult result);
}
