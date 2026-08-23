package com.hypernova.media.cluster;

import android.app.Service;
import android.content.Intent;
import android.os.IBinder;
import android.os.RemoteCallbackList;
import android.os.RemoteException;
import android.util.Log;

import androidx.annotation.Nullable;

import com.hypernova.contracts.media.IMediaStatusCallback;
import com.hypernova.contracts.media.IMediaStatusService;
import com.hypernova.contracts.media.MediaContract;
import com.hypernova.contracts.media.MediaPlaybackSnapshot;
import com.hypernova.media.HyperNovaMediaApplication;
import com.hypernova.media.model.PlaybackUiState;
import com.hypernova.media.playback.PlaybackController;

public final class MediaStatusService extends Service implements PlaybackController.Listener {
    private static final String TAG = "MediaStatusService";

    private final RemoteCallbackList<IMediaStatusCallback> callbacks =
            new RemoteCallbackList<>();

    private volatile MediaPlaybackSnapshot latest =
            MediaSnapshotTranslator.translate(PlaybackUiState.DISCONNECTED);

    private final IMediaStatusService.Stub binder = new IMediaStatusService.Stub() {
        @Override public int getApiVersion() { return MediaContract.API_VERSION; }

        @Override public MediaPlaybackSnapshot getCurrentSnapshot() { return latest; }

        @Override public void registerMediaStatusCallback(IMediaStatusCallback callback) {
            callbacks.register(callback);
            try {
                callback.onMediaPlaybackSnapshot(latest);
            } catch (RemoteException error) {
                callbacks.unregister(callback);
                Log.w(TAG, "Cluster callback died during registration", error);
            }
        }

        @Override public void unregisterMediaStatusCallback(IMediaStatusCallback callback) {
            callbacks.unregister(callback);
        }
    };

    @Override public void onCreate() {
        super.onCreate();
        HyperNovaMediaApplication application = (HyperNovaMediaApplication) getApplication();
        latest = MediaSnapshotTranslator.translate(application.playback().getState());
        application.playback().addListener(this);
    }

    @Nullable
    @Override public IBinder onBind(Intent intent) {
        if (intent == null || !MediaContract.BIND_STATUS_ACTION.equals(intent.getAction())) {
            Log.w(TAG, "Rejected bind action="
                    + (intent == null ? null : intent.getAction()));
            return null;
        }
        return binder;
    }

    @Override public void onDestroy() {
        ((HyperNovaMediaApplication) getApplication()).playback().removeListener(this);
        callbacks.kill();
        super.onDestroy();
    }

    @Override public void onPlaybackStateChanged(PlaybackUiState state) {
        publish(MediaSnapshotTranslator.translate(state));
    }

    private void publish(MediaPlaybackSnapshot snapshot) {
        latest = snapshot;
        int count = callbacks.beginBroadcast();
        try {
            for (int i = 0; i < count; i++) {
                try {
                    callbacks.getBroadcastItem(i).onMediaPlaybackSnapshot(snapshot);
                } catch (RemoteException error) {
                    Log.w(TAG, "Cluster callback is no longer available", error);
                }
            }
        } finally {
            callbacks.finishBroadcast();
        }
    }
}
