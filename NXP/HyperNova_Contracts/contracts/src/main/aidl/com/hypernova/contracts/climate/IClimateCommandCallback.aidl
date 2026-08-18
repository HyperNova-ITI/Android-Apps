package com.hypernova.contracts.climate;

import com.hypernova.contracts.climate.ClimateResult;

oneway interface IClimateCommandCallback {
    void onResult(in ClimateResult result);
}
