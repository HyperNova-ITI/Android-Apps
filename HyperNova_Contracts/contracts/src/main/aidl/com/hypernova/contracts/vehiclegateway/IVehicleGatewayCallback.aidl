package com.hypernova.contracts.vehiclegateway;

import com.hypernova.contracts.vehiclegateway.VehicleGatewayResult;

oneway interface IVehicleGatewayCallback {
    void onResult(in VehicleGatewayResult result);
}
