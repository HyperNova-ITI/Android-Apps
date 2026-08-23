package com.hypernova.contracts.media;

/** Frozen cross-APK constants for the HyperNova media status contract, API version 1. */
public final class MediaContract {
    public static final int API_VERSION = 1;

    public static final String PACKAGE_NAME = "com.hypernova.media";
    public static final String STATUS_SERVICE =
            "com.hypernova.media.cluster.MediaStatusService";
    public static final String BIND_STATUS_ACTION =
            "com.hypernova.media.action.BIND_STATUS";

    public static final int PLAYBACK_STATE_IDLE = 1;
    public static final int PLAYBACK_STATE_BUFFERING = 2;
    public static final int PLAYBACK_STATE_READY = 3;
    public static final int PLAYBACK_STATE_ENDED = 4;

    private MediaContract() {
    }
}
