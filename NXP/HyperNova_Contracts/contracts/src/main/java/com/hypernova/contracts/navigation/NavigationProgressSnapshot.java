package com.hypernova.contracts.navigation;

import android.os.Parcel;
import android.os.Parcelable;

/** Lightweight versioned progress update. Route geometry is intentionally absent. */
public final class NavigationProgressSnapshot implements Parcelable {
    private final String routeId;
    private final long routeVersion;
    private final int navigationState;
    private final long sequenceNumber;
    private final NavigationCurrentPosition currentPosition;
    private final long remainingDistanceMeters;

    public NavigationProgressSnapshot(
            String routeId,
            long routeVersion,
            int navigationState,
            long sequenceNumber,
            NavigationCurrentPosition currentPosition,
            long remainingDistanceMeters
    ) {
        this.routeId = routeId == null ? "" : routeId;
        this.routeVersion = routeVersion;
        this.navigationState = navigationState;
        this.sequenceNumber = sequenceNumber;
        this.currentPosition = currentPosition;
        this.remainingDistanceMeters = remainingDistanceMeters;
    }

    private NavigationProgressSnapshot(Parcel in) {
        String restoredRouteId = in.readString();
        routeId = restoredRouteId == null ? "" : restoredRouteId;
        routeVersion = in.readLong();
        navigationState = in.readInt();
        sequenceNumber = in.readLong();
        currentPosition = in.readTypedObject(NavigationCurrentPosition.CREATOR);
        remainingDistanceMeters = in.readLong();
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

    public long getSequenceNumber() {
        return sequenceNumber;
    }

    /** Null when Navigation has no authoritative current position. */
    public NavigationCurrentPosition getCurrentPosition() {
        return currentPosition;
    }

    /** Negative when remaining distance is unavailable. */
    public long getRemainingDistanceMeters() {
        return remainingDistanceMeters;
    }

    @Override
    public void writeToParcel(Parcel dest, int flags) {
        dest.writeString(routeId);
        dest.writeLong(routeVersion);
        dest.writeInt(navigationState);
        dest.writeLong(sequenceNumber);
        dest.writeTypedObject(currentPosition, flags);
        dest.writeLong(remainingDistanceMeters);
    }

    @Override
    public int describeContents() {
        return 0;
    }

    public static final Creator<NavigationProgressSnapshot> CREATOR =
            new Creator<NavigationProgressSnapshot>() {
                @Override
                public NavigationProgressSnapshot createFromParcel(Parcel in) {
                    return new NavigationProgressSnapshot(in);
                }

                @Override
                public NavigationProgressSnapshot[] newArray(int size) {
                    return new NavigationProgressSnapshot[size];
                }
            };
}
