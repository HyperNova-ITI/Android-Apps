package com.hypernova.ai.status;

import com.hypernova.ai.status.INovaStatusCallback;

/** Versioned, signature-protected launcher contract owned by NOVA AI. */
interface INovaStatusService {
    int getApiVersion();
    String getState();
    String getSnapshotJson();
    void registerCallback(INovaStatusCallback callback);
    void unregisterCallback(INovaStatusCallback callback);
    void cancelCurrentTurn();
    void setMuted(boolean muted);
    void setDeafened(boolean deafened);
}
