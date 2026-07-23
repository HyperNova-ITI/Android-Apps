package com.hypernova.ai.status;

/** Receives customer-visible NOVA state changes. */
oneway interface INovaStatusCallback {
    void onStateChanged(String state);
}
