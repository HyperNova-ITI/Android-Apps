package com.hypernova.contracts.navigation;

import android.os.Parcel;
import android.os.Parcelable;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/** One accepted or final Navigation callback for a correlated request. */
public final class NavigationResult implements Parcelable {
    private final String requestId;
    private final String operation;
    private final int status;
    private final String message;
    private final String errorCode;
    private final List<NavigationDestination> destinations;
    private final NavigationDestination selectedDestination;
    private final int navigationState;
    private final long etaSeconds;
    private final long distanceMeters;

    public NavigationResult(
            String requestId,
            String operation,
            int status,
            String message,
            String errorCode,
            List<NavigationDestination> destinations,
            NavigationDestination selectedDestination,
            int navigationState,
            long etaSeconds,
            long distanceMeters
    ) {
        this.requestId = requestId;
        this.operation = operation;
        this.status = status;
        this.message = message;
        this.errorCode = errorCode;
        this.destinations = destinations == null
                ? Collections.emptyList()
                : Collections.unmodifiableList(new ArrayList<>(destinations));
        this.selectedDestination = selectedDestination;
        this.navigationState = navigationState;
        this.etaSeconds = etaSeconds;
        this.distanceMeters = distanceMeters;
    }

    private NavigationResult(Parcel in) {
        requestId = in.readString();
        operation = in.readString();
        status = in.readInt();
        message = in.readString();
        errorCode = in.readString();
        ArrayList<NavigationDestination> values =
                in.createTypedArrayList(NavigationDestination.CREATOR);
        destinations = values == null
                ? Collections.emptyList()
                : Collections.unmodifiableList(values);
        selectedDestination = in.readTypedObject(NavigationDestination.CREATOR);
        navigationState = in.readInt();
        etaSeconds = in.readLong();
        distanceMeters = in.readLong();
    }

    public String getRequestId() {
        return requestId;
    }

    public String getOperation() {
        return operation;
    }

    public int getStatus() {
        return status;
    }

    public String getMessage() {
        return message;
    }

    public String getErrorCode() {
        return errorCode;
    }

    public List<NavigationDestination> getDestinations() {
        return destinations;
    }

    public NavigationDestination getSelectedDestination() {
        return selectedDestination;
    }

    public int getNavigationState() {
        return navigationState;
    }

    /** Returns -1 when ETA is not available. */
    public long getEtaSeconds() {
        return etaSeconds;
    }

    /** Returns -1 when distance is not available. */
    public long getDistanceMeters() {
        return distanceMeters;
    }

    @Override
    public void writeToParcel(Parcel dest, int flags) {
        dest.writeString(requestId);
        dest.writeString(operation);
        dest.writeInt(status);
        dest.writeString(message);
        dest.writeString(errorCode);
        dest.writeTypedList(destinations);
        dest.writeTypedObject(selectedDestination, flags);
        dest.writeInt(navigationState);
        dest.writeLong(etaSeconds);
        dest.writeLong(distanceMeters);
    }

    @Override
    public int describeContents() {
        return 0;
    }

    public static final Creator<NavigationResult> CREATOR =
            new Creator<NavigationResult>() {
                @Override
                public NavigationResult createFromParcel(Parcel in) {
                    return new NavigationResult(in);
                }

                @Override
                public NavigationResult[] newArray(int size) {
                    return new NavigationResult[size];
                }
            };
}
