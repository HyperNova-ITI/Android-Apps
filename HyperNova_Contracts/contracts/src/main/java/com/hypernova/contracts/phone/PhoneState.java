package com.hypernova.contracts.phone;

import android.os.Parcel;
import android.os.Parcelable;

public final class PhoneState implements Parcelable {
    private final int availability;
    private final String connectedDeviceName;
    private final boolean hfpConnected;
    private final int callState;
    private final String activeContactId;
    private final String activeContactName;
    private final String activePhoneNumber;
    private final long callStartedAtEpochMillis;
    private final long callDurationSeconds;
    private final boolean muted;
    private final boolean held;
    private final int audioRoute;
    private final boolean canAnswer;
    private final boolean canDecline;
    private final boolean canEnd;
    private final boolean canHold;
    private final boolean canMute;
    private final boolean canSendDtmf;
    private final long updatedAtEpochMillis;

    public PhoneState(
            int availability,
            String connectedDeviceName,
            boolean hfpConnected,
            int callState,
            String activeContactId,
            String activeContactName,
            String activePhoneNumber,
            long callStartedAtEpochMillis,
            long callDurationSeconds,
            boolean muted,
            boolean held,
            int audioRoute,
            boolean canAnswer,
            boolean canDecline,
            boolean canEnd,
            boolean canHold,
            boolean canMute,
            boolean canSendDtmf,
            long updatedAtEpochMillis
    ) {
        this.availability = availability;
        this.connectedDeviceName = connectedDeviceName;
        this.hfpConnected = hfpConnected;
        this.callState = callState;
        this.activeContactId = activeContactId;
        this.activeContactName = activeContactName;
        this.activePhoneNumber = activePhoneNumber;
        this.callStartedAtEpochMillis = callStartedAtEpochMillis;
        this.callDurationSeconds = callDurationSeconds;
        this.muted = muted;
        this.held = held;
        this.audioRoute = audioRoute;
        this.canAnswer = canAnswer;
        this.canDecline = canDecline;
        this.canEnd = canEnd;
        this.canHold = canHold;
        this.canMute = canMute;
        this.canSendDtmf = canSendDtmf;
        this.updatedAtEpochMillis = updatedAtEpochMillis;
    }

    private PhoneState(Parcel in) {
        availability = in.readInt();
        connectedDeviceName = in.readString();
        hfpConnected = in.readBoolean();
        callState = in.readInt();
        activeContactId = in.readString();
        activeContactName = in.readString();
        activePhoneNumber = in.readString();
        callStartedAtEpochMillis = in.readLong();
        callDurationSeconds = in.readLong();
        muted = in.readBoolean();
        held = in.readBoolean();
        audioRoute = in.readInt();
        canAnswer = in.readBoolean();
        canDecline = in.readBoolean();
        canEnd = in.readBoolean();
        canHold = in.readBoolean();
        canMute = in.readBoolean();
        canSendDtmf = in.readBoolean();
        updatedAtEpochMillis = in.readLong();
    }

    public int getAvailability() { return availability; }
    public String getConnectedDeviceName() { return connectedDeviceName; }
    public boolean isHfpConnected() { return hfpConnected; }
    public int getCallState() { return callState; }
    public String getActiveContactId() { return activeContactId; }
    public String getActiveContactName() { return activeContactName; }
    public String getActivePhoneNumber() { return activePhoneNumber; }
    public long getCallStartedAtEpochMillis() { return callStartedAtEpochMillis; }
    public long getCallDurationSeconds() { return callDurationSeconds; }
    public boolean isMuted() { return muted; }
    public boolean isHeld() { return held; }
    public int getAudioRoute() { return audioRoute; }
    public boolean canAnswer() { return canAnswer; }
    public boolean canDecline() { return canDecline; }
    public boolean canEnd() { return canEnd; }
    public boolean canHold() { return canHold; }
    public boolean canMute() { return canMute; }
    public boolean canSendDtmf() { return canSendDtmf; }
    public long getUpdatedAtEpochMillis() { return updatedAtEpochMillis; }

    @Override
    public void writeToParcel(Parcel dest, int flags) {
        dest.writeInt(availability);
        dest.writeString(connectedDeviceName);
        dest.writeBoolean(hfpConnected);
        dest.writeInt(callState);
        dest.writeString(activeContactId);
        dest.writeString(activeContactName);
        dest.writeString(activePhoneNumber);
        dest.writeLong(callStartedAtEpochMillis);
        dest.writeLong(callDurationSeconds);
        dest.writeBoolean(muted);
        dest.writeBoolean(held);
        dest.writeInt(audioRoute);
        dest.writeBoolean(canAnswer);
        dest.writeBoolean(canDecline);
        dest.writeBoolean(canEnd);
        dest.writeBoolean(canHold);
        dest.writeBoolean(canMute);
        dest.writeBoolean(canSendDtmf);
        dest.writeLong(updatedAtEpochMillis);
    }

    @Override
    public int describeContents() { return 0; }

    public static final Creator<PhoneState> CREATOR =
            new Creator<PhoneState>() {
                @Override
                public PhoneState createFromParcel(Parcel in) {
                    return new PhoneState(in);
                }

                @Override
                public PhoneState[] newArray(int size) {
                    return new PhoneState[size];
                }
            };
}
