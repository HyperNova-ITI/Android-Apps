package com.hypernova.contracts.phone;

import android.os.Parcel;
import android.os.Parcelable;

public final class PhoneContactNumber implements Parcelable {
    private final String numberId;
    private final String label;
    private final String displayNumber;
    private final boolean primary;

    public PhoneContactNumber(
            String numberId,
            String label,
            String displayNumber,
            boolean primary
    ) {
        this.numberId = numberId;
        this.label = label;
        this.displayNumber = displayNumber;
        this.primary = primary;
    }

    private PhoneContactNumber(Parcel in) {
        numberId = in.readString();
        label = in.readString();
        displayNumber = in.readString();
        primary = in.readBoolean();
    }

    public String getNumberId() { return numberId; }
    public String getLabel() { return label; }
    public String getDisplayNumber() { return displayNumber; }
    public boolean isPrimary() { return primary; }

    @Override
    public void writeToParcel(Parcel dest, int flags) {
        dest.writeString(numberId);
        dest.writeString(label);
        dest.writeString(displayNumber);
        dest.writeBoolean(primary);
    }

    @Override
    public int describeContents() { return 0; }

    public static final Creator<PhoneContactNumber> CREATOR =
            new Creator<PhoneContactNumber>() {
                @Override
                public PhoneContactNumber createFromParcel(Parcel in) {
                    return new PhoneContactNumber(in);
                }

                @Override
                public PhoneContactNumber[] newArray(int size) {
                    return new PhoneContactNumber[size];
                }
            };
}
