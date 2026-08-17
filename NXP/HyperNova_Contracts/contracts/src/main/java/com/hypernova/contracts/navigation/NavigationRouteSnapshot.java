package com.hypernova.contracts.navigation;

import android.os.Parcel;
import android.os.Parcelable;

/** Versioned route metadata and geometry, published only when the route changes. */
public final class NavigationRouteSnapshot implements Parcelable {
    private final String routeId;
    private final long routeVersion;
    private final int navigationState;
    private final NavigationDestination selectedDestination;
    private final long etaSeconds;
    private final long distanceMeters;
    private final NavigationRoutePreview routePreview;

    public NavigationRouteSnapshot(
            String routeId,
            long routeVersion,
            int navigationState,
            NavigationDestination selectedDestination,
            long etaSeconds,
            long distanceMeters,
            NavigationRoutePreview routePreview
    ) {
        this.routeId = routeId == null ? "" : routeId;
        this.routeVersion = routeVersion;
        this.navigationState = navigationState;
        this.selectedDestination = selectedDestination;
        this.etaSeconds = etaSeconds;
        this.distanceMeters = distanceMeters;
        this.routePreview = routePreview == null
                ? NavigationRoutePreview.empty()
                : routePreview;
    }

    private NavigationRouteSnapshot(Parcel in) {
        String restoredRouteId = in.readString();
        routeId = restoredRouteId == null ? "" : restoredRouteId;
        routeVersion = in.readLong();
        navigationState = in.readInt();
        selectedDestination = in.readTypedObject(NavigationDestination.CREATOR);
        etaSeconds = in.readLong();
        distanceMeters = in.readLong();
        NavigationRoutePreview restoredPreview =
                in.readTypedObject(NavigationRoutePreview.CREATOR);
        routePreview = restoredPreview == null
                ? NavigationRoutePreview.empty()
                : restoredPreview;
    }

    public String getRouteId() {
        return routeId;
    }

    public long getRouteVersion() {
        return routeVersion;
    }

    public int getNavigationState() {
        return navigationState;
    }

    public NavigationDestination getSelectedDestination() {
        return selectedDestination;
    }

    public long getEtaSeconds() {
        return etaSeconds;
    }

    public long getDistanceMeters() {
        return distanceMeters;
    }

    public NavigationRoutePreview getRoutePreview() {
        return routePreview;
    }

    @Override
    public void writeToParcel(Parcel dest, int flags) {
        dest.writeString(routeId);
        dest.writeLong(routeVersion);
        dest.writeInt(navigationState);
        dest.writeTypedObject(selectedDestination, flags);
        dest.writeLong(etaSeconds);
        dest.writeLong(distanceMeters);
        dest.writeTypedObject(routePreview, flags);
    }

    @Override
    public int describeContents() {
        return 0;
    }

    public static final Creator<NavigationRouteSnapshot> CREATOR =
            new Creator<NavigationRouteSnapshot>() {
                @Override
                public NavigationRouteSnapshot createFromParcel(Parcel in) {
                    return new NavigationRouteSnapshot(in);
                }

                @Override
                public NavigationRouteSnapshot[] newArray(int size) {
                    return new NavigationRouteSnapshot[size];
                }
            };
}
