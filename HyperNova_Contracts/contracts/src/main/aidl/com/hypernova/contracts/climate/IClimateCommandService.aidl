package com.hypernova.contracts.climate;

import com.hypernova.contracts.climate.IClimateCommandCallback;

interface IClimateCommandService {
    int getApiVersion();

    void getCapabilities(
        String requestId,
        IClimateCommandCallback callback
    );

    void getCurrentState(
        String requestId,
        IClimateCommandCallback callback
    );

    void setPowerEnabled(
        String requestId,
        boolean enabled,
        IClimateCommandCallback callback
    );

    void setTargetTemperature(
        String requestId,
        int zone,
        float temperatureC,
        IClimateCommandCallback callback
    );

    void setFanLevel(
        String requestId,
        int fanLevel,
        IClimateCommandCallback callback
    );

    void setAcEnabled(
        String requestId,
        boolean enabled,
        IClimateCommandCallback callback
    );

    void setAutoModeEnabled(
        String requestId,
        boolean enabled,
        IClimateCommandCallback callback
    );

    void setRecirculationEnabled(
        String requestId,
        boolean enabled,
        IClimateCommandCallback callback
    );
}
