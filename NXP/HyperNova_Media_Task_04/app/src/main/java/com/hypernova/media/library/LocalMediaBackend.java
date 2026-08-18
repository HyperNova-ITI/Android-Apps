package com.hypernova.media.library;

import android.net.Uri;

import com.hypernova.media.model.LibraryUiState;

public interface LocalMediaBackend {
    interface Listener { void onLibraryStateChanged(LibraryUiState state); }
    LibraryUiState currentState();
    void setListener(Listener listener);
    void clearListener(Listener listener);
    void selectTree(Uri treeUri);
    void scanSelectedTree();
    void scanMediaStore();
    default void start() {}
    default void refreshVolumes() {}
    default void forgetSelectedTree() {}
}
