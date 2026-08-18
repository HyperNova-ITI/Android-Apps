package com.hypernova.contracts.phone;

import com.hypernova.contracts.phone.IPhoneCommandCallback;
import com.hypernova.contracts.phone.IPhoneStatusCallback;

interface IPhoneCommandService {
    int getApiVersion();

    void getCurrentState(
        String requestId,
        IPhoneCommandCallback callback
    );

    void searchContacts(
        String requestId,
        String query,
        int limit,
        IPhoneCommandCallback callback
    );

    void getContact(
        String requestId,
        String contactId,
        IPhoneCommandCallback callback
    );

    void getCallHistory(
        String requestId,
        int filter,
        int limit,
        IPhoneCommandCallback callback
    );

    void getCallHistoryForContact(
        String requestId,
        String contactId,
        int filter,
        int limit,
        IPhoneCommandCallback callback
    );

    void callContact(
        String requestId,
        String contactId,
        String numberId,
        IPhoneCommandCallback callback
    );

    void callNumber(
        String requestId,
        String phoneNumber,
        IPhoneCommandCallback callback
    );

    void callHistoryEntry(
        String requestId,
        String callId,
        IPhoneCommandCallback callback
    );

    void answerCall(
        String requestId,
        IPhoneCommandCallback callback
    );

    void declineCall(
        String requestId,
        IPhoneCommandCallback callback
    );

    void endCall(
        String requestId,
        IPhoneCommandCallback callback
    );

    void setMuted(
        String requestId,
        boolean muted,
        IPhoneCommandCallback callback
    );

    void setHeld(
        String requestId,
        boolean held,
        IPhoneCommandCallback callback
    );

    void setAudioRoute(
        String requestId,
        int route,
        IPhoneCommandCallback callback
    );

    void sendDtmf(
        String requestId,
        String digit,
        IPhoneCommandCallback callback
    );

    void registerPhoneStatusCallback(
        IPhoneStatusCallback callback
    );

    void unregisterPhoneStatusCallback(
        IPhoneStatusCallback callback
    );
}
