package com.hypernova.contracts.navigation;

import android.os.Parcel;
import android.os.Parcelable;

/** Final result from the optional, read-only Google Place contact boundary. */
public final class NavigationPlaceContactResult implements Parcelable {
    private final String requestId;
    private final int status;
    private final String message;
    private final String errorCode;
    private final String destinationId;
    private final String displayName;
    private final String phoneNumber;

    public NavigationPlaceContactResult(
            String requestId,
            int status,
            String message,
            String errorCode,
            String destinationId,
            String displayName,
            String phoneNumber
    ) {
        this.requestId = requestId;
        this.status = status;
        this.message = message;
        this.errorCode = errorCode;
        this.destinationId = destinationId;
        this.displayName = displayName;
        this.phoneNumber = phoneNumber;
    }

    private NavigationPlaceContactResult(Parcel in) {
        requestId = in.readString();
        status = in.readInt();
        message = in.readString();
        errorCode = in.readString();
        destinationId = in.readString();
        displayName = in.readString();
        phoneNumber = in.readString();
    }

    public String getRequestId() { return requestId; }
    public int getStatus() { return status; }
    public String getMessage() { return message; }
    public String getErrorCode() { return errorCode; }
    public String getDestinationId() { return destinationId; }
    public String getDisplayName() { return displayName; }
    public String getPhoneNumber() { return phoneNumber; }

    @Override
    public void writeToParcel(Parcel dest, int flags) {
        dest.writeString(requestId);
        dest.writeInt(status);
        dest.writeString(message);
        dest.writeString(errorCode);
        dest.writeString(destinationId);
        dest.writeString(displayName);
        dest.writeString(phoneNumber);
    }

    @Override public int describeContents() { return 0; }

    public static final Creator<NavigationPlaceContactResult> CREATOR =
            new Creator<NavigationPlaceContactResult>() {
                @Override public NavigationPlaceContactResult createFromParcel(Parcel in) {
                    return new NavigationPlaceContactResult(in);
                }

                @Override public NavigationPlaceContactResult[] newArray(int size) {
                    return new NavigationPlaceContactResult[size];
                }
            };
}
