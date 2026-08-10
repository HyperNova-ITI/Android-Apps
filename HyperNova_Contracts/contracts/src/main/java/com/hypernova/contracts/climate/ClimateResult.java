package com.hypernova.contracts.climate;

import android.os.Parcel;
import android.os.Parcelable;

/** One accepted or final Climate callback for a correlated request. */
public final class ClimateResult implements Parcelable {
    private final String requestId;
    private final String operation;
    private final int status;
    private final String message;
    private final String errorCode;
    private final ClimateCapabilities capabilities;
    private final ClimateState confirmedState;

    public ClimateResult(
            String requestId,
            String operation,
            int status,
            String message,
            String errorCode,
            ClimateCapabilities capabilities,
            ClimateState confirmedState
    ) {
        this.requestId = requestId;
        this.operation = operation;
        this.status = status;
        this.message = message;
        this.errorCode = errorCode;
        this.capabilities = capabilities;
        this.confirmedState = confirmedState;
    }

    private ClimateResult(Parcel in) {
        requestId = in.readString();
        operation = in.readString();
        status = in.readInt();
        message = in.readString();
        errorCode = in.readString();
        capabilities = in.readTypedObject(ClimateCapabilities.CREATOR);
        confirmedState = in.readTypedObject(ClimateState.CREATOR);
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

    public ClimateCapabilities getCapabilities() {
        return capabilities;
    }

    public ClimateState getConfirmedState() {
        return confirmedState;
    }

    @Override
    public void writeToParcel(Parcel dest, int flags) {
        dest.writeString(requestId);
        dest.writeString(operation);
        dest.writeInt(status);
        dest.writeString(message);
        dest.writeString(errorCode);
        dest.writeTypedObject(capabilities, flags);
        dest.writeTypedObject(confirmedState, flags);
    }

    @Override
    public int describeContents() {
        return 0;
    }

    public static final Creator<ClimateResult> CREATOR = new Creator<ClimateResult>() {
        @Override
        public ClimateResult createFromParcel(Parcel in) {
            return new ClimateResult(in);
        }

        @Override
        public ClimateResult[] newArray(int size) {
            return new ClimateResult[size];
        }
    };
}
