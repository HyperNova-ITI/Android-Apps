package com.hypernova.contracts.phone;

import android.os.Parcel;
import android.os.Parcelable;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

public final class PhoneResult implements Parcelable {
    private final String requestId;
    private final String operation;
    private final int status;
    private final String message;
    private final String errorCode;
    private final int totalMatches;
    private final List<PhoneContact> contacts;
    private final PhoneContact contact;
    private final List<PhoneCallHistoryEntry> callHistory;
    private final PhoneState confirmedState;

    public PhoneResult(
            String requestId,
            String operation,
            int status,
            String message,
            String errorCode,
            int totalMatches,
            List<PhoneContact> contacts,
            PhoneContact contact,
            List<PhoneCallHistoryEntry> callHistory,
            PhoneState confirmedState
    ) {
        this.requestId = requestId;
        this.operation = operation;
        this.status = status;
        this.message = message;
        this.errorCode = errorCode;
        this.totalMatches = totalMatches;
        this.contacts = contacts == null
                ? Collections.emptyList()
                : Collections.unmodifiableList(new ArrayList<>(contacts));
        this.contact = contact;
        this.callHistory = callHistory == null
                ? Collections.emptyList()
                : Collections.unmodifiableList(new ArrayList<>(callHistory));
        this.confirmedState = confirmedState;
    }

    private PhoneResult(Parcel in) {
        requestId = in.readString();
        operation = in.readString();
        status = in.readInt();
        message = in.readString();
        errorCode = in.readString();
        totalMatches = in.readInt();

        List<PhoneContact> contactValues =
                in.createTypedArrayList(PhoneContact.CREATOR);
        contacts = contactValues == null
                ? Collections.emptyList()
                : Collections.unmodifiableList(contactValues);

        contact = in.readTypedObject(PhoneContact.CREATOR);

        List<PhoneCallHistoryEntry> historyValues =
                in.createTypedArrayList(PhoneCallHistoryEntry.CREATOR);
        callHistory = historyValues == null
                ? Collections.emptyList()
                : Collections.unmodifiableList(historyValues);

        confirmedState = in.readTypedObject(PhoneState.CREATOR);
    }

    public String getRequestId() { return requestId; }
    public String getOperation() { return operation; }
    public int getStatus() { return status; }
    public String getMessage() { return message; }
    public String getErrorCode() { return errorCode; }
    public int getTotalMatches() { return totalMatches; }
    public List<PhoneContact> getContacts() { return contacts; }
    public PhoneContact getContact() { return contact; }
    public List<PhoneCallHistoryEntry> getCallHistory() { return callHistory; }
    public PhoneState getConfirmedState() { return confirmedState; }

    @Override
    public void writeToParcel(Parcel dest, int flags) {
        dest.writeString(requestId);
        dest.writeString(operation);
        dest.writeInt(status);
        dest.writeString(message);
        dest.writeString(errorCode);
        dest.writeInt(totalMatches);
        dest.writeTypedList(contacts);
        dest.writeTypedObject(contact, flags);
        dest.writeTypedList(callHistory);
        dest.writeTypedObject(confirmedState, flags);
    }

    @Override
    public int describeContents() { return 0; }

    public static final Creator<PhoneResult> CREATOR =
            new Creator<PhoneResult>() {
                @Override
                public PhoneResult createFromParcel(Parcel in) {
                    return new PhoneResult(in);
                }

                @Override
                public PhoneResult[] newArray(int size) {
                    return new PhoneResult[size];
                }
            };
}
