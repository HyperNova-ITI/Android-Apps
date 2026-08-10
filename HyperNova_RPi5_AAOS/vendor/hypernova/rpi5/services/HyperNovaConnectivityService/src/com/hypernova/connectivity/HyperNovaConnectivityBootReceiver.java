/*
 * Copyright (C) 2026 HyperNova
 *
 * Licensed under the Apache License, Version 2.0.
 */

package com.hypernova.connectivity;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.util.Log;

/**
 * Starts the HyperNova connectivity backend during system boot.
 */
public final class HyperNovaConnectivityBootReceiver
        extends BroadcastReceiver {

    private static final String TAG =
            "HyperNovaConnectivity";

    @Override
    public void onReceive(
            Context context,
            Intent intent) {

        if (context == null || intent == null) {
            return;
        }

        Log.i(
                TAG,
                "Boot event received: "
                        + intent.getAction());

        Intent serviceIntent =
                new Intent(
                        context,
                        HyperNovaConnectivityService.class);

        serviceIntent.setAction(
                HyperNovaConnectivityService.ACTION_START);

        try {

            context.startService(
                    serviceIntent);

        } catch (RuntimeException exception) {

            Log.e(
                    TAG,
                    "Unable to start connectivity service",
                    exception);
        }
    }
}
