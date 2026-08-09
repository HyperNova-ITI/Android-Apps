package com.hypernova.media.model;

public enum MediaSourceType {
    RADIO("radio"),
    BLUETOOTH("bluetooth"),
    USB("usb");

    private final String mId;

    MediaSourceType(String id) {
        mId = id;
    }

    public String getId() {
        return mId;
    }
}
