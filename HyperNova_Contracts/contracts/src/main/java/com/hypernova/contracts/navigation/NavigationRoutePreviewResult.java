package com.hypernova.contracts.navigation;

import android.os.Parcel;
import android.os.Parcelable;

/** One read-only route-preview response for a correlated request. */
public final class NavigationRoutePreviewResult implements Parcelable {
    private final String requestId;
    private final int status;
    private final String message;
    private final String errorCode;
    private final int navigationState;
    private final NavigationRoutePreview routePreview;

    public NavigationRoutePreviewResult(
            String requestId,
            int status,
            String message,
            String errorCode,
            int navigationState,
            NavigationRoutePreview routePreview
    ) {
        this.requestId = requestId;
        this.status = status;
        this.message = message;
        this.errorCode = errorCode;
        this.navigationState = navigationState;
        this.routePreview = routePreview == null
                ? NavigationRoutePreview.empty()
                : routePreview;
    }

    private NavigationRoutePreviewResult(Parcel in) {
        requestId = in.readString();
        status = in.readInt();
        message = in.readString();
        errorCode = in.readString();
        navigationState = in.readInt();
        NavigationRoutePreview preview =
                in.readTypedObject(NavigationRoutePreview.CREATOR);
        routePreview = preview == null
                ? NavigationRoutePreview.empty()
                : preview;
    }

    public String getRequestId() {
        return requestId;
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

    public int getNavigationState() {
        return navigationState;
    }

    public NavigationRoutePreview getRoutePreview() {
        return routePreview;
    }

    @Override
    public void writeToParcel(Parcel dest, int flags) {
        dest.writeString(requestId);
        dest.writeInt(status);
        dest.writeString(message);
        dest.writeString(errorCode);
        dest.writeInt(navigationState);
        dest.writeTypedObject(routePreview, flags);
    }

    @Override
    public int describeContents() {
        return 0;
    }

    public static final Creator<NavigationRoutePreviewResult> CREATOR =
            new Creator<NavigationRoutePreviewResult>() {
                @Override
                public NavigationRoutePreviewResult createFromParcel(Parcel in) {
                    return new NavigationRoutePreviewResult(in);
                }

                @Override
                public NavigationRoutePreviewResult[] newArray(int size) {
                    return new NavigationRoutePreviewResult[size];
                }
            };
}
