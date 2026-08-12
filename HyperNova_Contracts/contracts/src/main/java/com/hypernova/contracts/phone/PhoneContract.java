package com.hypernova.contracts.phone;

public final class PhoneContract {
    public static final String PACKAGE_NAME = "com.hypernova.phone";
    public static final String OPEN_ACTION = "com.hypernova.phone.action.OPEN";
    public static final String COMMAND_SERVICE =
            "com.hypernova.phone.service.PhoneCommandService";
    public static final String BIND_COMMAND_ACTION =
            "com.hypernova.phone.action.BIND_COMMAND";

    public static final String OP_GET_CURRENT_STATE = "get_current_state";
    public static final String OP_SEARCH_CONTACTS = "search_contacts";
    public static final String OP_GET_CONTACT = "get_contact";
    public static final String OP_GET_CALL_HISTORY = "get_call_history";
    public static final String OP_GET_CONTACT_CALL_HISTORY = "get_contact_call_history";
    public static final String OP_CALL_CONTACT = "call_contact";
    public static final String OP_CALL_NUMBER = "call_number";
    public static final String OP_CALL_HISTORY_ENTRY = "call_history_entry";
    public static final String OP_ANSWER_CALL = "answer_call";
    public static final String OP_DECLINE_CALL = "decline_call";
    public static final String OP_END_CALL = "end_call";
    public static final String OP_SET_MUTED = "set_muted";
    public static final String OP_SET_HELD = "set_held";
    public static final String OP_SET_AUDIO_ROUTE = "set_audio_route";
    public static final String OP_SEND_DTMF = "send_dtmf";

    public static final int DEFAULT_CONTACT_RESULT_LIMIT = 5;
    public static final int MAX_CONTACT_RESULT_LIMIT = 10;
    public static final int DEFAULT_CALL_HISTORY_LIMIT = 5;
    public static final int MAX_CALL_HISTORY_LIMIT = 50;

    public static final int AVAILABILITY_UNAVAILABLE = 1;
    public static final int AVAILABILITY_DISCONNECTED = 2;
    public static final int AVAILABILITY_CONNECTING = 3;
    public static final int AVAILABILITY_READY = 4;

    public static final int CALL_STATE_IDLE = 1;
    public static final int CALL_STATE_INCOMING = 2;
    public static final int CALL_STATE_DIALING = 3;
    public static final int CALL_STATE_ACTIVE = 4;
    public static final int CALL_STATE_HELD = 5;
    public static final int CALL_STATE_DISCONNECTING = 6;
    public static final int CALL_STATE_ENDED = 7;
    public static final int CALL_STATE_FAILED = 8;

    public static final int HISTORY_FILTER_ALL = 0;
    public static final int HISTORY_FILTER_INCOMING = 1;
    public static final int HISTORY_FILTER_OUTGOING = 2;
    public static final int HISTORY_FILTER_MISSED = 3;
    public static final int HISTORY_FILTER_REJECTED = 4;

    public static final int CALL_TYPE_INCOMING = 1;
    public static final int CALL_TYPE_OUTGOING = 2;
    public static final int CALL_TYPE_MISSED = 3;
    public static final int CALL_TYPE_REJECTED = 4;

    public static final int NUMBER_PRESENTATION_ALLOWED = 1;
    public static final int NUMBER_PRESENTATION_RESTRICTED = 2;
    public static final int NUMBER_PRESENTATION_UNKNOWN = 3;
    public static final int NUMBER_PRESENTATION_PAYPHONE = 4;
    public static final int NUMBER_PRESENTATION_UNAVAILABLE = 5;

    public static final int AUDIO_ROUTE_UNKNOWN = 0;
    public static final int AUDIO_ROUTE_VEHICLE = 1;
    public static final int AUDIO_ROUTE_PHONE = 2;
    public static final int AUDIO_ROUTE_BLUETOOTH = 3;

    public static final String ERROR_NO_PHONE_CONNECTED = "NO_PHONE_CONNECTED";
    public static final String ERROR_HFP_NOT_CONNECTED = "HFP_NOT_CONNECTED";
    public static final String ERROR_NO_ACTIVE_CALL = "NO_ACTIVE_CALL";
    public static final String ERROR_NO_INCOMING_CALL = "NO_INCOMING_CALL";
    public static final String ERROR_CONTACT_NOT_FOUND = "CONTACT_NOT_FOUND";
    public static final String ERROR_MULTIPLE_CONTACTS = "MULTIPLE_CONTACTS";
    public static final String ERROR_NUMBER_NOT_FOUND = "NUMBER_NOT_FOUND";
    public static final String ERROR_MULTIPLE_NUMBERS = "MULTIPLE_NUMBERS";
    public static final String ERROR_INVALID_PHONE_NUMBER = "INVALID_PHONE_NUMBER";
    public static final String ERROR_CALL_NOT_ALLOWED = "CALL_NOT_ALLOWED";
    public static final String ERROR_CALL_FAILED = "CALL_FAILED";
    public static final String ERROR_ANSWER_FAILED = "ANSWER_FAILED";
    public static final String ERROR_DECLINE_FAILED = "DECLINE_FAILED";
    public static final String ERROR_END_FAILED = "END_FAILED";
    public static final String ERROR_MUTE_UNAVAILABLE = "MUTE_UNAVAILABLE";
    public static final String ERROR_HOLD_UNAVAILABLE = "HOLD_UNAVAILABLE";
    public static final String ERROR_AUDIO_ROUTE_UNAVAILABLE = "AUDIO_ROUTE_UNAVAILABLE";
    public static final String ERROR_DTMF_UNAVAILABLE = "DTMF_UNAVAILABLE";
    public static final String ERROR_STALE_CONTACT_REFERENCE = "STALE_CONTACT_REFERENCE";
    public static final String ERROR_STALE_CALL_REFERENCE = "STALE_CALL_REFERENCE";

    private PhoneContract() {
    }
}
