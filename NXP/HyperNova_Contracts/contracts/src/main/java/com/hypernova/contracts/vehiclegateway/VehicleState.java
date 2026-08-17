package com.hypernova.contracts.vehiclegateway;

import android.os.Parcel;
import android.os.Parcelable;

/** Latest QNX-owned vehicle snapshot. Unknown scalar values use -1. */
public final class VehicleState implements Parcelable {
    private final int connectionState;
    private final int cabinTemperatureC;
    private final int humidityPercent;
    private final int fuelPercent;
    private final int zone1TargetTemperatureC;
    private final int zone2TargetTemperatureC;
    private final int zone1FanLevel;
    private final int zone2FanLevel;
    private final int[] activeDtcs;
    private final boolean telemetryFresh;
    private final long updatedAtEpochMillis;

    public VehicleState(
            int connectionState,
            int cabinTemperatureC,
            int humidityPercent,
            int fuelPercent,
            int zone1TargetTemperatureC,
            int zone2TargetTemperatureC,
            int zone1FanLevel,
            int zone2FanLevel,
            int[] activeDtcs,
            boolean telemetryFresh,
            long updatedAtEpochMillis
    ) {
        this.connectionState = connectionState;
        this.cabinTemperatureC = cabinTemperatureC;
        this.humidityPercent = humidityPercent;
        this.fuelPercent = fuelPercent;
        this.zone1TargetTemperatureC = zone1TargetTemperatureC;
        this.zone2TargetTemperatureC = zone2TargetTemperatureC;
        this.zone1FanLevel = zone1FanLevel;
        this.zone2FanLevel = zone2FanLevel;
        this.activeDtcs = activeDtcs == null ? new int[0] : activeDtcs.clone();
        this.telemetryFresh = telemetryFresh;
        this.updatedAtEpochMillis = updatedAtEpochMillis;
    }

    private VehicleState(Parcel in) {
        connectionState = in.readInt();
        cabinTemperatureC = in.readInt();
        humidityPercent = in.readInt();
        fuelPercent = in.readInt();
        zone1TargetTemperatureC = in.readInt();
        zone2TargetTemperatureC = in.readInt();
        zone1FanLevel = in.readInt();
        zone2FanLevel = in.readInt();
        int[] values = in.createIntArray();
        activeDtcs = values == null ? new int[0] : values;
        telemetryFresh = in.readInt() != 0;
        updatedAtEpochMillis = in.readLong();
    }

    public int getConnectionState() { return connectionState; }
    public int getCabinTemperatureC() { return cabinTemperatureC; }
    public int getHumidityPercent() { return humidityPercent; }
    public int getFuelPercent() { return fuelPercent; }
    public int getZone1TargetTemperatureC() { return zone1TargetTemperatureC; }
    public int getZone2TargetTemperatureC() { return zone2TargetTemperatureC; }
    public int getZone1FanLevel() { return zone1FanLevel; }
    public int getZone2FanLevel() { return zone2FanLevel; }
    public int[] getActiveDtcs() { return activeDtcs.clone(); }
    public boolean isTelemetryFresh() { return telemetryFresh; }
    public long getUpdatedAtEpochMillis() { return updatedAtEpochMillis; }

    @Override
    public void writeToParcel(Parcel dest, int flags) {
        dest.writeInt(connectionState);
        dest.writeInt(cabinTemperatureC);
        dest.writeInt(humidityPercent);
        dest.writeInt(fuelPercent);
        dest.writeInt(zone1TargetTemperatureC);
        dest.writeInt(zone2TargetTemperatureC);
        dest.writeInt(zone1FanLevel);
        dest.writeInt(zone2FanLevel);
        dest.writeIntArray(activeDtcs);
        dest.writeInt(telemetryFresh ? 1 : 0);
        dest.writeLong(updatedAtEpochMillis);
    }

    @Override public int describeContents() { return 0; }

    public static final Creator<VehicleState> CREATOR = new Creator<VehicleState>() {
        @Override public VehicleState createFromParcel(Parcel in) { return new VehicleState(in); }
        @Override public VehicleState[] newArray(int size) { return new VehicleState[size]; }
    };
}
