package com.hypernova.contracts.media;

import com.hypernova.contracts.media.IMediaStatusCallback;
import com.hypernova.contracts.media.MediaPlaybackSnapshot;

interface IMediaStatusService {
    int getApiVersion();

    MediaPlaybackSnapshot getCurrentSnapshot();

    void registerMediaStatusCallback(
        IMediaStatusCallback callback
    );

    void unregisterMediaStatusCallback(
        IMediaStatusCallback callback
    );
}
