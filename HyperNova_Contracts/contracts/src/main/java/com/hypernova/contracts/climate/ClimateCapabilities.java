package com.hypernova.contracts.climate;

import android.os.Parcel;
import android.os.Parcelable;

/** The climate operations and ranges that the current vehicle backend really supports. */
public final class ClimateCapabilities implements Parcelable {
    private final int zoneMode;
    private final float minimumTemperatureC;
    private final float maximumTemperatureC;
    private final float temperatureStepC;
    private final int maximumFanLevel;
    private final boolean supportsPower;
    private final boolean supportsTemperature;
    private final boolean supportsAc;
    private final boolean supportsAuto;
    private final boolean supportsRecirculation;

    public ClimateCapabilities(
            int zoneMode,
            float minimumTemperatureC,
            float maximumTemperatureC,
            float temperatureStepC,
            int maximumFanLevel,
            boolean supportsPower,
            boolean supportsTemperature,
            boolean supportsAc,
            boolean supportsAuto,
            boolean supportsRecirculation
    ) {
        this.zoneMode = zoneMode;
        this.minimumTemperatureC = minimumTemperatureC;
        this.maximumTemperatureC = maximumTemperatureC;
        this.temperatureStepC = temperatureStepC;
        this.maximumFanLevel = maximumFanLevel;
        this.supportsPower = supportsPower;
        this.supportsTemperature = supportsTemperature;
        this.supportsAc = supportsAc;
        this.supportsAuto = supportsAuto;
        this.supportsRecirculation = supportsRecirculation;
    }

    private ClimateCapabilities(Parcel in) {
        zoneMode = in.readInt();
        minimumTemperatureC = in.readFloat();
        maximumTemperatureC = in.readFloat();
        temperatureStepC = in.readFloat();
        maximumFanLevel = in.readInt();
        supportsPower = in.readBoolean();
        supportsTemperature = in.readBoolean();
        supportsAc = in.readBoolean();
        supportsAuto = in.readBoolean();
        supportsRecirculation = in.readBoolean();
    }

    public int getZoneMode() {
        return zoneMode;
    }

    public float getMinimumTemperatureC() {
        return minimumTemperatureC;
    }

    public float getMaximumTemperatureC() {
        return maximumTemperatureC;
    }

    public float getTemperatureStepC() {
        return temperatureStepC;
    }

    public int getMaximumFanLevel() {
        return maximumFanLevel;
    }

    public boolean supportsPower() {
        return supportsPower;
    }

    public boolean supportsTemperature() {
        return supportsTemperature;
    }

    public boolean supportsAc() {
        return supportsAc;
    }

    public boolean supportsAuto() {
        return supportsAuto;
    }

    public boolean supportsRecirculation() {
        return supportsRecirculation;
    }

    @Override
    public void writeToParcel(Parcel dest, int flags) {
        dest.writeInt(zoneMode);
        dest.writeFloat(minimumTemperatureC);
        dest.writeFloat(maximumTemperatureC);
        dest.writeFloat(temperatureStepC);
        dest.writeInt(maximumFanLevel);
        dest.writeBoolean(supportsPower);
        dest.writeBoolean(supportsTemperature);
        dest.writeBoolean(supportsAc);
        dest.writeBoolean(supportsAuto);
        dest.writeBoolean(supportsRecirculation);
    }

    @Override
    public int describeContents() {
        return 0;
    }

    public static final Creator<ClimateCapabilities> CREATOR =
            new Creator<ClimateCapabilities>() {
                @Override
                public ClimateCapabilities createFromParcel(Parcel in) {
                    return new ClimateCapabilities(in);
                }

                @Override
                public ClimateCapabilities[] newArray(int size) {
                    return new ClimateCapabilities[size];
                }
            };
}
