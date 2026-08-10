package com.hypernova.media.library;

import android.content.Context;
import android.net.Uri;

import androidx.annotation.Nullable;

import com.hypernova.media.model.LibraryUiState;

public final class PhoneLocalMediaBackend implements LocalMediaBackend {
    private final SafUsbRepository saf;
    private final MediaStoreRepository mediaStore;
    private LibraryUiState state = LibraryUiState.noFolder();
    @Nullable private Listener listener;

    public PhoneLocalMediaBackend(Context context) {
        saf = new SafUsbRepository(context);
        mediaStore = new MediaStoreRepository(context);
        saf.setCallback(this::publish);
        if (saf.selectedTree() != null) saf.scan();
    }

    @Override public LibraryUiState currentState() { return state; }
    @Override public void setListener(Listener listener) { this.listener = listener; listener.onLibraryStateChanged(state); }
    @Override public void clearListener(Listener listener) { if (this.listener == listener) this.listener = null; }
    @Override public void selectTree(Uri treeUri) { saf.persistTree(treeUri); }
    @Override public void scanSelectedTree() { saf.scan(); }
    @Override public void scanMediaStore() { mediaStore.scan(this::publish); }
    private void publish(LibraryUiState value) {
        state = value;
        if (listener != null) listener.onLibraryStateChanged(value);
    }
}
