package com.hypernova.contracts.phone;

import android.os.Parcel;
import android.os.Parcelable;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

public final class PhoneContact implements Parcelable {
    private final String contactId;
    private final String displayName;
    private final List<PhoneContactNumber> numbers;

    public PhoneContact(
            String contactId,
            String displayName,
            List<PhoneContactNumber> numbers
    ) {
        this.contactId = contactId;
        this.displayName = displayName;
        this.numbers = numbers == null
                ? Collections.emptyList()
                : Collections.unmodifiableList(new ArrayList<>(numbers));
    }

    private PhoneContact(Parcel in) {
        contactId = in.readString();
        displayName = in.readString();
        List<PhoneContactNumber> values =
                in.createTypedArrayList(PhoneContactNumber.CREATOR);
        numbers = values == null
                ? Collections.emptyList()
                : Collections.unmodifiableList(values);
    }

    public String getContactId() { return contactId; }
    public String getDisplayName() { return displayName; }
    public List<PhoneContactNumber> getNumbers() { return numbers; }

    @Override
    public void writeToParcel(Parcel dest, int flags) {
        dest.writeString(contactId);
        dest.writeString(displayName);
        dest.writeTypedList(numbers);
    }

    @Override
    public int describeContents() { return 0; }

    public static final Creator<PhoneContact> CREATOR =
            new Creator<PhoneContact>() {
                @Override
                public PhoneContact createFromParcel(Parcel in) {
                    return new PhoneContact(in);
                }

                @Override
                public PhoneContact[] newArray(int size) {
                    return new PhoneContact[size];
                }
            };
}
