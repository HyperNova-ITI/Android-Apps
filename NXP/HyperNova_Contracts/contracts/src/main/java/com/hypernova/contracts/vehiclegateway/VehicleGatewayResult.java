package com.hypernova.contracts.vehiclegateway;

import android.os.Parcel;
import android.os.Parcelable;

/** An accepted or final result for a correlated vehicle command. */
public final class VehicleGatewayResult implements Parcelable {
    private final String requestId;
    private final int status;
    private final String errorCode;
    private final String message;
    private final VehicleState confirmedState;

    public VehicleGatewayResult(
            String requestId,
            int status,
            String errorCode,
            String message,
            VehicleState confirmedState
    ) {
        this.requestId = requestId;
        this.status = status;
        this.errorCode = errorCode;
        this.message = message;
        this.confirmedState = confirmedState;
    }

    private VehicleGatewayResult(Parcel in) {
        requestId = in.readString();
        status = in.readInt();
        errorCode = in.readString();
        message = in.readString();
        confirmedState = in.readTypedObject(VehicleState.CREATOR);
    }

    public String getRequestId() { return requestId; }
    public int getStatus() { return status; }
    public String getErrorCode() { return errorCode; }
    public String getMessage() { return message; }
    public VehicleState getConfirmedState() { return confirmedState; }

    @Override
    public void writeToParcel(Parcel dest, int flags) {
        dest.writeString(requestId);
        dest.writeInt(status);
        dest.writeString(errorCode);
        dest.writeString(message);
        dest.writeTypedObject(confirmedState, flags);
    }

    @Override public int describeContents() { return 0; }

    public static final Creator<VehicleGatewayResult> CREATOR =
            new Creator<VehicleGatewayResult>() {
                @Override public VehicleGatewayResult createFromParcel(Parcel in) {
                    return new VehicleGatewayResult(in);
                }
                @Override public VehicleGatewayResult[] newArray(int size) {
                    return new VehicleGatewayResult[size];
                }
            };
}
