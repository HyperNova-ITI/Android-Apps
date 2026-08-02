package com.hypernova.contracts.navigation;

import android.os.Parcel;
import android.os.Parcelable;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/** Bounded route geometry and an optional authoritative current position. */
public final class NavigationRoutePreview implements Parcelable {
    private static final NavigationRoutePreview EMPTY =
            new NavigationRoutePreview(Collections.emptyList(), null);

    private final List<NavigationRoutePoint> routePoints;
    private final NavigationRoutePoint currentPosition;

    public NavigationRoutePreview(
            List<NavigationRoutePoint> routePoints,
            NavigationRoutePoint currentPosition
    ) {
        this.routePoints = routePoints == null
                ? Collections.emptyList()
                : Collections.unmodifiableList(new ArrayList<>(routePoints));
        this.currentPosition = currentPosition;
    }

    private NavigationRoutePreview(Parcel in) {
        ArrayList<NavigationRoutePoint> values =
                in.createTypedArrayList(NavigationRoutePoint.CREATOR);
        routePoints = values == null
                ? Collections.emptyList()
                : Collections.unmodifiableList(values);
        currentPosition = in.readTypedObject(NavigationRoutePoint.CREATOR);
    }

    public static NavigationRoutePreview empty() {
        return EMPTY;
    }

    public List<NavigationRoutePoint> getRoutePoints() {
        return routePoints;
    }

    /** Null when Navigation does not have an authoritative current position. */
    public NavigationRoutePoint getCurrentPosition() {
        return currentPosition;
    }

    @Override
    public void writeToParcel(Parcel dest, int flags) {
        dest.writeTypedList(routePoints);
        dest.writeTypedObject(currentPosition, flags);
    }

    @Override
    public int describeContents() {
        return 0;
    }

    public static final Creator<NavigationRoutePreview> CREATOR =
            new Creator<NavigationRoutePreview>() {
                @Override
                public NavigationRoutePreview createFromParcel(Parcel in) {
                    return new NavigationRoutePreview(in);
                }

                @Override
                public NavigationRoutePreview[] newArray(int size) {
                    return new NavigationRoutePreview[size];
                }
            };
}
