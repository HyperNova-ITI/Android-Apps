package com.hypernova.contracts.vehiclegateway;

import com.hypernova.contracts.vehiclegateway.IVehicleGatewayCallback;
import com.hypernova.contracts.vehiclegateway.IVehicleStateListener;
import com.hypernova.contracts.vehiclegateway.VehicleClimateCommand;
import com.hypernova.contracts.vehiclegateway.VehicleState;

interface IVehicleGatewayService {
    int getApiVersion();
    int getConnectionState();
    VehicleState getLatestVehicleState();

    void submitClimateCommand(
        in VehicleClimateCommand command,
        IVehicleGatewayCallback callback
    );

    void registerVehicleStateListener(IVehicleStateListener listener);
    void unregisterVehicleStateListener(IVehicleStateListener listener);
}
