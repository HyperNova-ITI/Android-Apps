package com.hypernova.contracts.phone;

import android.os.Parcel;
import android.os.Parcelable;

public final class PhoneCallHistoryEntry implements Parcelable {
    private final String callId;
    private final String contactId;
    private final String displayName;
    private final String phoneNumber;
    private final int numberPresentation;
    private final int callType;
    private final long timestampEpochMillis;
    private final long durationSeconds;

    public PhoneCallHistoryEntry(
            String callId,
            String contactId,
            String displayName,
            String phoneNumber,
            int numberPresentation,
            int callType,
            long timestampEpochMillis,
            long durationSeconds
    ) {
        this.callId = callId;
        this.contactId = contactId;
        this.displayName = displayName;
        this.phoneNumber = phoneNumber;
        this.numberPresentation = numberPresentation;
        this.callType = callType;
        this.timestampEpochMillis = timestampEpochMillis;
        this.durationSeconds = durationSeconds;
    }

    private PhoneCallHistoryEntry(Parcel in) {
        callId = in.readString();
        contactId = in.readString();
        displayName = in.readString();
        phoneNumber = in.readString();
        numberPresentation = in.readInt();
        callType = in.readInt();
        timestampEpochMillis = in.readLong();
        durationSeconds = in.readLong();
    }

    public String getCallId() { return callId; }
    public String getContactId() { return contactId; }
    public String getDisplayName() { return displayName; }
    public String getPhoneNumber() { return phoneNumber; }
    public int getNumberPresentation() { return numberPresentation; }
    public int getCallType() { return callType; }
    public long getTimestampEpochMillis() { return timestampEpochMillis; }
    public long getDurationSeconds() { return durationSeconds; }

    @Override
    public void writeToParcel(Parcel dest, int flags) {
        dest.writeString(callId);
        dest.writeString(contactId);
        dest.writeString(displayName);
        dest.writeString(phoneNumber);
        dest.writeInt(numberPresentation);
        dest.writeInt(callType);
        dest.writeLong(timestampEpochMillis);
        dest.writeLong(durationSeconds);
    }

    @Override
    public int describeContents() { return 0; }

    public static final Creator<PhoneCallHistoryEntry> CREATOR =
            new Creator<PhoneCallHistoryEntry>() {
                @Override
                public PhoneCallHistoryEntry createFromParcel(Parcel in) {
                    return new PhoneCallHistoryEntry(in);
                }

                @Override
                public PhoneCallHistoryEntry[] newArray(int size) {
                    return new PhoneCallHistoryEntry[size];
                }
            };
}
