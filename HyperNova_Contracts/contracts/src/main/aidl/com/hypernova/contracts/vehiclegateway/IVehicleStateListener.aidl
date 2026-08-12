package com.hypernova.contracts.vehiclegateway;

import com.hypernova.contracts.vehiclegateway.VehicleFaultEvent;
import com.hypernova.contracts.vehiclegateway.VehicleState;

oneway interface IVehicleStateListener {
    void onVehicleState(in VehicleState state);
    void onFault(in VehicleFaultEvent event);
}
