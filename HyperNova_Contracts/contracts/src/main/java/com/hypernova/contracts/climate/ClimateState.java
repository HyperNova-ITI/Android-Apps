package com.hypernova.contracts.climate;

import android.os.Parcel;
import android.os.Parcelable;

/** Last authoritative climate state; Float.NaN and -1 represent unavailable values. */
public final class ClimateState implements Parcelable {
    private final int availability;
    private final boolean powerEnabled;
    private final float driverTargetTemperatureC;
    private final float passengerTargetTemperatureC;
    private final int fanLevel;
    private final boolean acEnabled;
    private final boolean autoModeEnabled;
    private final boolean recirculationEnabled;
    private final long updatedAtEpochMillis;

    public ClimateState(
            int availability,
            boolean powerEnabled,
            float driverTargetTemperatureC,
            float passengerTargetTemperatureC,
            int fanLevel,
            boolean acEnabled,
            boolean autoModeEnabled,
            boolean recirculationEnabled,
            long updatedAtEpochMillis
    ) {
        this.availability = availability;
        this.powerEnabled = powerEnabled;
        this.driverTargetTemperatureC = driverTargetTemperatureC;
        this.passengerTargetTemperatureC = passengerTargetTemperatureC;
        this.fanLevel = fanLevel;
        this.acEnabled = acEnabled;
        this.autoModeEnabled = autoModeEnabled;
        this.recirculationEnabled = recirculationEnabled;
        this.updatedAtEpochMillis = updatedAtEpochMillis;
    }

    private ClimateState(Parcel in) {
        availability = in.readInt();
        powerEnabled = in.readBoolean();
        driverTargetTemperatureC = in.readFloat();
        passengerTargetTemperatureC = in.readFloat();
        fanLevel = in.readInt();
        acEnabled = in.readBoolean();
        autoModeEnabled = in.readBoolean();
        recirculationEnabled = in.readBoolean();
        updatedAtEpochMillis = in.readLong();
    }

    public int getAvailability() {
        return availability;
    }

    public boolean isPowerEnabled() {
        return powerEnabled;
    }

    public float getDriverTargetTemperatureC() {
        return driverTargetTemperatureC;
    }

    public float getPassengerTargetTemperatureC() {
        return passengerTargetTemperatureC;
    }

    public int getFanLevel() {
        return fanLevel;
    }

    public boolean isAcEnabled() {
        return acEnabled;
    }

    public boolean isAutoModeEnabled() {
        return autoModeEnabled;
    }

    public boolean isRecirculationEnabled() {
        return recirculationEnabled;
    }

    public long getUpdatedAtEpochMillis() {
        return updatedAtEpochMillis;
    }

    @Override
    public void writeToParcel(Parcel dest, int flags) {
        dest.writeInt(availability);
        dest.writeBoolean(powerEnabled);
        dest.writeFloat(driverTargetTemperatureC);
        dest.writeFloat(passengerTargetTemperatureC);
        dest.writeInt(fanLevel);
        dest.writeBoolean(acEnabled);
        dest.writeBoolean(autoModeEnabled);
        dest.writeBoolean(recirculationEnabled);
        dest.writeLong(updatedAtEpochMillis);
    }

    @Override
    public int describeContents() {
        return 0;
    }

    public static final Creator<ClimateState> CREATOR = new Creator<ClimateState>() {
        @Override
        public ClimateState createFromParcel(Parcel in) {
            return new ClimateState(in);
        }

        @Override
        public ClimateState[] newArray(int size) {
            return new ClimateState[size];
        }
    };
}
