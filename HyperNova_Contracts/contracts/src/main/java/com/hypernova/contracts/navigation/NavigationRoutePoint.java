package com.hypernova.contracts.navigation;

import android.os.Parcel;
import android.os.Parcelable;

/** One geographic point in Navigation's authoritative route geometry. */
public final class NavigationRoutePoint implements Parcelable {
    private final double latitude;
    private final double longitude;

    public NavigationRoutePoint(double latitude, double longitude) {
        this.latitude = latitude;
        this.longitude = longitude;
    }

    private NavigationRoutePoint(Parcel in) {
        latitude = in.readDouble();
        longitude = in.readDouble();
    }

    public double getLatitude() {
        return latitude;
    }

    public double getLongitude() {
        return longitude;
    }

    @Override
    public void writeToParcel(Parcel dest, int flags) {
        dest.writeDouble(latitude);
        dest.writeDouble(longitude);
    }

    @Override
    public int describeContents() {
        return 0;
    }

    public static final Creator<NavigationRoutePoint> CREATOR =
            new Creator<NavigationRoutePoint>() {
                @Override
                public NavigationRoutePoint createFromParcel(Parcel in) {
                    return new NavigationRoutePoint(in);
                }

                @Override
                public NavigationRoutePoint[] newArray(int size) {
                    return new NavigationRoutePoint[size];
                }
            };
}
