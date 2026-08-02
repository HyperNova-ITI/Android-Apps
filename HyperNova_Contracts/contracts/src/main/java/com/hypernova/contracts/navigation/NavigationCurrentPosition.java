package com.hypernova.contracts.navigation;

import android.os.Parcel;
import android.os.Parcelable;

/** One authoritative position sample produced by HyperNova Navigation. */
public final class NavigationCurrentPosition implements Parcelable {
    private final double latitude;
    private final double longitude;
    private final float bearingDegrees;
    private final float speedMetersPerSecond;
    private final long timestampMillis;

    public NavigationCurrentPosition(
            double latitude,
            double longitude,
            float bearingDegrees,
            float speedMetersPerSecond,
            long timestampMillis
    ) {
        this.latitude = latitude;
        this.longitude = longitude;
        this.bearingDegrees = bearingDegrees;
        this.speedMetersPerSecond = speedMetersPerSecond;
        this.timestampMillis = timestampMillis;
    }

    private NavigationCurrentPosition(Parcel in) {
        latitude = in.readDouble();
        longitude = in.readDouble();
        bearingDegrees = in.readFloat();
        speedMetersPerSecond = in.readFloat();
        timestampMillis = in.readLong();
    }

    public double getLatitude() {
        return latitude;
    }

    public double getLongitude() {
        return longitude;
    }

    public float getBearingDegrees() {
        return bearingDegrees;
    }

    public float getSpeedMetersPerSecond() {
        return speedMetersPerSecond;
    }

    public long getTimestampMillis() {
        return timestampMillis;
    }

    @Override
    public void writeToParcel(Parcel dest, int flags) {
        dest.writeDouble(latitude);
        dest.writeDouble(longitude);
        dest.writeFloat(bearingDegrees);
        dest.writeFloat(speedMetersPerSecond);
        dest.writeLong(timestampMillis);
    }

    @Override
    public int describeContents() {
        return 0;
    }

    public static final Creator<NavigationCurrentPosition> CREATOR =
            new Creator<NavigationCurrentPosition>() {
                @Override
                public NavigationCurrentPosition createFromParcel(Parcel in) {
                    return new NavigationCurrentPosition(in);
                }

                @Override
                public NavigationCurrentPosition[] newArray(int size) {
                    return new NavigationCurrentPosition[size];
                }
            };
}
