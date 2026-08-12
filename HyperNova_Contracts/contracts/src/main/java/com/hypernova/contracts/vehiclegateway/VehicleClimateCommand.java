package com.hypernova.contracts.vehiclegateway;

import android.os.Parcel;
import android.os.Parcelable;

/** One typed, bounded HVAC request. It cannot carry arbitrary TC397 frames. */
public final class VehicleClimateCommand implements Parcelable {
    private final String requestId;
    private final int targetTemperatureC;
    private final int fanLevel;
    private final int zone;
    private final int caller;

    public VehicleClimateCommand(
            String requestId,
            int targetTemperatureC,
            int fanLevel,
            int zone,
            int caller
    ) {
        this.requestId = requestId;
        this.targetTemperatureC = targetTemperatureC;
        this.fanLevel = fanLevel;
        this.zone = zone;
        this.caller = caller;
    }

    private VehicleClimateCommand(Parcel in) {
        requestId = in.readString();
        targetTemperatureC = in.readInt();
        fanLevel = in.readInt();
        zone = in.readInt();
        caller = in.readInt();
    }

    public String getRequestId() { return requestId; }
    public int getTargetTemperatureC() { return targetTemperatureC; }
    public int getFanLevel() { return fanLevel; }
    public int getZone() { return zone; }
    public int getCaller() { return caller; }

    @Override
    public void writeToParcel(Parcel dest, int flags) {
        dest.writeString(requestId);
        dest.writeInt(targetTemperatureC);
        dest.writeInt(fanLevel);
        dest.writeInt(zone);
        dest.writeInt(caller);
    }

    @Override public int describeContents() { return 0; }

    public static final Creator<VehicleClimateCommand> CREATOR =
            new Creator<VehicleClimateCommand>() {
                @Override public VehicleClimateCommand createFromParcel(Parcel in) {
                    return new VehicleClimateCommand(in);
                }

                @Override public VehicleClimateCommand[] newArray(int size) {
                    return new VehicleClimateCommand[size];
                }
            };
}
