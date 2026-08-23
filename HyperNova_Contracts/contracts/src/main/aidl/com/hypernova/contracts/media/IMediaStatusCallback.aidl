package com.hypernova.contracts.media;

import com.hypernova.contracts.media.MediaPlaybackSnapshot;

oneway interface IMediaStatusCallback {
    void onMediaPlaybackSnapshot(in MediaPlaybackSnapshot snapshot);
}
