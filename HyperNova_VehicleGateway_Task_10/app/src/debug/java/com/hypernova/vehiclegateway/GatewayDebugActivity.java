package com.hypernova.vehiclegateway;

import android.app.Activity;
import android.content.Intent;
import android.os.Bundle;

/**
 * Temporary debug-only manual bring-up entry point for the Vehicle Gateway.
 *
 * It intentionally has no UI and is excluded from release builds. Starting
 * the service from this foreground Activity satisfies Android background-start
 * restrictions without weakening the service's signature permission.
 */
public final class GatewayDebugActivity extends Activity {

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        startService(new Intent(this, VehicleGatewayService.class));
        finish();
    }
}
