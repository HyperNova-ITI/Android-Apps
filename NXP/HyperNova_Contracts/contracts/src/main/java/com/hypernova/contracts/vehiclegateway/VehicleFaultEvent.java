package com.hypernova.contracts.vehiclegateway;

import android.os.Parcel;
import android.os.Parcelable;

/** One explicit SAE DTC active/cleared transition received from TC397. */
public final class VehicleFaultEvent implements Parcelable {
    private final int dtc;
    private final boolean active;
    private final int tcEventSequence;
    private final long receivedAtEpochMillis;

    public VehicleFaultEvent(int dtc, boolean active, int tcEventSequence, long receivedAtEpochMillis) {
        this.dtc = dtc;
        this.active = active;
        this.tcEventSequence = tcEventSequence;
        this.receivedAtEpochMillis = receivedAtEpochMillis;
    }

    private VehicleFaultEvent(Parcel in) {
        dtc = in.readInt();
        active = in.readInt() != 0;
        tcEventSequence = in.readInt();
        receivedAtEpochMillis = in.readLong();
    }

    public int getDtc() { return dtc; }
    public boolean isActive() { return active; }
    public int getTcEventSequence() { return tcEventSequence; }
    public long getReceivedAtEpochMillis() { return receivedAtEpochMillis; }

    @Override
    public void writeToParcel(Parcel dest, int flags) {
        dest.writeInt(dtc);
        dest.writeInt(active ? 1 : 0);
        dest.writeInt(tcEventSequence);
        dest.writeLong(receivedAtEpochMillis);
    }

    @Override public int describeContents() { return 0; }

    public static final Creator<VehicleFaultEvent> CREATOR = new Creator<VehicleFaultEvent>() {
        @Override public VehicleFaultEvent createFromParcel(Parcel in) {
            return new VehicleFaultEvent(in);
        }
        @Override public VehicleFaultEvent[] newArray(int size) {
            return new VehicleFaultEvent[size];
        }
    };
}
