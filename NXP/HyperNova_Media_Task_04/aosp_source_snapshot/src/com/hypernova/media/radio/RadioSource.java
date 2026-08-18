package com.hypernova.media.radio;

import android.content.ComponentName;
import android.content.Context;

import com.hypernova.media.model.MediaSnapshot;
import com.hypernova.media.model.MediaSourceType;
import com.hypernova.media.source.PlatformBrowserSource;

/** Adapter for the AAOS radio app's exported MediaBrowserService. */
public final class RadioSource extends PlatformBrowserSource {
    public static final ComponentName COMPONENT = new ComponentName(
            "com.android.car.radio", "com.android.car.radio.service.RadioAppService");

    public RadioSource(Context context) {
        super(context, MediaSourceType.RADIO, COMPONENT);
    }

    @Override
    protected MediaSnapshot.State errorPlaybackState() {
        return MediaSnapshot.State.UNAVAILABLE;
    }

    @Override
    protected MediaSnapshot.State disconnectedState() {
        return MediaSnapshot.State.UNAVAILABLE;
    }
}
