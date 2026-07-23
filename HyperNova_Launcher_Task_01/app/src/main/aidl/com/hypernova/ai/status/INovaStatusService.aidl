package com.hypernova.ai.status;

import com.hypernova.ai.status.INovaStatusCallback;

/** Read-only, versioned launcher contract owned by NOVA AI. */
interface INovaStatusService {
    int getApiVersion();
    String getState();
    void registerCallback(INovaStatusCallback callback);
    void unregisterCallback(INovaStatusCallback callback);
}
