package com.hypernova.media.playback;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.Intent;
import android.media.MediaDescription;
import android.media.MediaMetadata;
import android.media.Rating;
import android.media.browse.MediaBrowser;
import android.media.session.MediaSession;
import android.media.session.PlaybackState;
import android.net.Uri;
import android.os.Bundle;
import android.service.media.MediaBrowserService;
import android.text.TextUtils;
import android.view.KeyEvent;

import com.hypernova.media.HyperNovaMediaApplication;
import com.hypernova.media.MainActivity;
import com.hypernova.media.R;
import com.hypernova.media.model.MediaItemModel;
import com.hypernova.media.model.MediaSnapshot;
import com.hypernova.media.model.MediaSourceType;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * The exact exported browser/session contract consumed by HyperNova Launcher.
 *
 * <p>The Launcher uses a Media3 SessionToken for this component. Media3 recognizes the framework
 * MediaBrowserService action and performs its legacy-controller conversion internally.</p>
 */
public final class HyperNovaMediaSessionService extends MediaBrowserService
        implements PlaybackCoordinator.Listener {
    private static final String ROOT_ID = "hypernova_media_root";
    private static final String CHANNEL_ID = "hypernova_media_playback";
    private static final int NOTIFICATION_ID = 2401;
    private static final String ACTION_PLAY = "com.hypernova.media.action.PLAY";
    private static final String ACTION_PAUSE = "com.hypernova.media.action.PAUSE";
    private static final String ACTION_PREVIOUS = "com.hypernova.media.action.PREVIOUS";
    private static final String ACTION_NEXT = "com.hypernova.media.action.NEXT";

    private PlaybackCoordinator mCoordinator;
    private MediaSession mSession;
    private List<MediaItemModel> mLastItems = Collections.emptyList();
    private boolean mForeground;

    @Override
    public void onCreate() {
        super.onCreate();
        createNotificationChannel();
        mCoordinator = ((HyperNovaMediaApplication) getApplication()).getPlaybackCoordinator();
        mSession = new MediaSession(this, "HyperNovaMedia");
        mSession.setFlags(MediaSession.FLAG_HANDLES_MEDIA_BUTTONS
                | MediaSession.FLAG_HANDLES_TRANSPORT_CONTROLS);
        mSession.setCallback(mSessionCallback);
        mSession.setSessionActivity(PendingIntent.getActivity(this, 0, openAppIntent(),
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE));
        mSession.setActive(true);
        setSessionToken(mSession.getSessionToken());
        mCoordinator.addListener(this);
    }

    @Override
    public BrowserRoot onGetRoot(String clientPackageName, int clientUid, Bundle rootHints) {
        return new BrowserRoot(ROOT_ID, null);
    }

    @Override
    public void onLoadChildren(String parentId, Result<List<MediaBrowser.MediaItem>> result) {
        if (!ROOT_ID.equals(parentId)) {
            result.sendResult(Collections.emptyList());
            return;
        }
        List<MediaBrowser.MediaItem> children = new ArrayList<>();
        for (MediaItemModel item : mLastItems) {
            MediaDescription.Builder description = new MediaDescription.Builder()
                    .setMediaId(item.getId())
                    .setTitle(item.getTitle())
                    .setSubtitle(item.getArtist())
                    .setDescription(item.getAlbum())
                    .setMediaUri(item.getUri())
                    .setIconUri(item.getArtworkUri());
            children.add(new MediaBrowser.MediaItem(description.build(),
                    MediaBrowser.MediaItem.FLAG_PLAYABLE));
        }
        result.sendResult(children);
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent != null) {
            String action = intent.getAction();
            if (ACTION_PLAY.equals(action)) {
                mCoordinator.play();
            } else if (ACTION_PAUSE.equals(action)) {
                mCoordinator.pause();
            } else if (ACTION_PREVIOUS.equals(action)) {
                mCoordinator.previous();
            } else if (ACTION_NEXT.equals(action)) {
                mCoordinator.next();
            } else if (Intent.ACTION_MEDIA_BUTTON.equals(action)) {
                KeyEvent event = intent.getParcelableExtra(Intent.EXTRA_KEY_EVENT, KeyEvent.class);
                if (event != null && event.getAction() == KeyEvent.ACTION_DOWN) {
                    handleMediaKey(event.getKeyCode());
                }
            }
        }
        return START_NOT_STICKY;
    }

    private void handleMediaKey(int keyCode) {
        switch (keyCode) {
            case KeyEvent.KEYCODE_MEDIA_PLAY:
                mCoordinator.play();
                break;
            case KeyEvent.KEYCODE_MEDIA_PAUSE:
                mCoordinator.pause();
                break;
            case KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE:
                MediaSnapshot snapshot = mCoordinator.getSnapshot();
                if (snapshot != null && snapshot.getState() == MediaSnapshot.State.PLAYING) {
                    mCoordinator.pause();
                } else {
                    mCoordinator.play();
                }
                break;
            case KeyEvent.KEYCODE_MEDIA_PREVIOUS:
                mCoordinator.previous();
                break;
            case KeyEvent.KEYCODE_MEDIA_NEXT:
                mCoordinator.next();
                break;
            default:
                break;
        }
    }

    @Override
    public void onPlaybackChanged(MediaSourceType selectedSource, MediaSnapshot snapshot) {
        publishSession(selectedSource, snapshot);
    }

    private void publishSession(MediaSourceType selectedSource, MediaSnapshot snapshot) {
        // An external Radio/AVRCP session remains the AAOS active session while HyperNova
        // mirrors it for Launcher. Keeping the mirror inactive prevents CarMediaService from
        // switching back to the wrapper and stopping the real upstream player. Direct Launcher
        // controllers remain connected to this browser token and continue receiving all state.
        boolean proxyingExternalSession = selectedSource == MediaSourceType.RADIO
                || selectedSource == MediaSourceType.BLUETOOTH;
        if (mSession.isActive() == proxyingExternalSession) {
            mSession.setActive(!proxyingExternalSession);
        }
        if (snapshot == null) {
            mLastItems = Collections.emptyList();
            mSession.setMetadata(null);
            mSession.setPlaybackState(new PlaybackState.Builder()
                    .setState(PlaybackState.STATE_NONE, 0, 0)
                    .setActions(0)
                    .build());
            updateSessionExtras(null);
            notifyChildrenChanged(ROOT_ID);
            updateForeground(null);
            return;
        }

        List<MediaItemModel> oldItems = mLastItems;
        mLastItems = snapshot.getItems();
        if (snapshot.hasActiveItem()) {
            MediaMetadata.Builder metadata = new MediaMetadata.Builder()
                    .putString(MediaMetadata.METADATA_KEY_MEDIA_ID,
                            selectedSource.getId() + ":active")
                    .putString(MediaMetadata.METADATA_KEY_TITLE, snapshot.getTitle())
                    .putLong(MediaMetadata.METADATA_KEY_DURATION, snapshot.getDurationMs());
            putString(metadata, MediaMetadata.METADATA_KEY_ARTIST, snapshot.getArtist());
            putString(metadata, MediaMetadata.METADATA_KEY_ALBUM, snapshot.getAlbum());
            putString(metadata, MediaMetadata.METADATA_KEY_ALBUM_ART_URI,
                    snapshot.getArtworkUri());
            if (snapshot.getArtwork() != null) {
                metadata.putBitmap(MediaMetadata.METADATA_KEY_ALBUM_ART, snapshot.getArtwork());
            }
            if (snapshot.supportsFavorite()) {
                metadata.putRating(MediaMetadata.METADATA_KEY_USER_RATING,
                        Rating.newHeartRating(snapshot.isFavorite()));
            }
            mSession.setMetadata(metadata.build());
        } else {
            // A valid active session with no metadata is the honest idle contract.
            mSession.setMetadata(null);
        }

        long actions = 0;
        if (snapshot.canPlay()) actions |= PlaybackState.ACTION_PLAY;
        if (snapshot.canPause()) actions |= PlaybackState.ACTION_PAUSE;
        if (snapshot.canPrevious()) actions |= PlaybackState.ACTION_SKIP_TO_PREVIOUS;
        if (snapshot.canNext()) actions |= PlaybackState.ACTION_SKIP_TO_NEXT;
        if (snapshot.canSeek()) actions |= PlaybackState.ACTION_SEEK_TO;
        if (snapshot.canBrowse()) actions |= PlaybackState.ACTION_PLAY_FROM_MEDIA_ID;
        if (snapshot.supportsFavorite()) actions |= PlaybackState.ACTION_SET_RATING;
        mSession.setRatingType(snapshot.supportsFavorite()
                ? Rating.RATING_HEART : Rating.RATING_NONE);

        PlaybackState.Builder playback = new PlaybackState.Builder()
                .setActions(actions)
                .setState(toPlatformState(snapshot.getState()),
                        snapshot.getCurrentPositionMs(),
                        snapshot.getState() == MediaSnapshot.State.PLAYING ? 1f : 0f,
                        android.os.SystemClock.elapsedRealtime());
        if (snapshot.getState() == MediaSnapshot.State.ERROR
                && !TextUtils.isEmpty(snapshot.getErrorDetail())) {
            playback.setErrorMessage(snapshot.getErrorDetail());
        }
        mSession.setPlaybackState(playback.build());
        updateSessionExtras(selectedSource);
        if (oldItems != mLastItems) {
            notifyChildrenChanged(ROOT_ID);
        }
        updateForeground(snapshot);
    }

    private static void putString(MediaMetadata.Builder builder, String key, String value) {
        if (!TextUtils.isEmpty(value)) {
            builder.putString(key, value);
        }
    }

    private void updateSessionExtras(MediaSourceType source) {
        Bundle extras = new Bundle();
        if (source != null) {
            extras.putString("com.hypernova.media.ACTIVE_SOURCE", source.getId());
        }
        mSession.setExtras(extras);
    }

    private static int toPlatformState(MediaSnapshot.State state) {
        switch (state) {
            case PLAYING:
                return PlaybackState.STATE_PLAYING;
            case PAUSED:
            case FOCUS_INTERRUPTED:
                return PlaybackState.STATE_PAUSED;
            case BUFFERING:
            case LOADING:
            case SCANNING:
                return PlaybackState.STATE_BUFFERING;
            case CONNECTING:
                return PlaybackState.STATE_CONNECTING;
            case SEEKING:
                return PlaybackState.STATE_SKIPPING_TO_NEXT;
            case ERROR:
                return PlaybackState.STATE_ERROR;
            case READY:
            case EMPTY:
            case DISCONNECTED:
            case UNAVAILABLE:
            case PERMISSION_REQUIRED:
            case IDLE:
            default:
                return PlaybackState.STATE_NONE;
        }
    }

    private void createNotificationChannel() {
        NotificationChannel channel = new NotificationChannel(CHANNEL_ID,
                getString(R.string.notification_channel_name), NotificationManager.IMPORTANCE_LOW);
        channel.setDescription(getString(R.string.notification_channel_description));
        getSystemService(NotificationManager.class).createNotificationChannel(channel);
    }

    private void updateForeground(MediaSnapshot snapshot) {
        boolean shouldBeForeground = snapshot != null
                && snapshot.getState() == MediaSnapshot.State.PLAYING
                && snapshot.hasActiveItem();
        if (shouldBeForeground) {
            startForeground(NOTIFICATION_ID, buildNotification(snapshot));
            mForeground = true;
        } else if (mForeground) {
            stopForeground(STOP_FOREGROUND_REMOVE);
            mForeground = false;
        }
    }

    private Notification buildNotification(MediaSnapshot snapshot) {
        boolean playing = snapshot.getState() == MediaSnapshot.State.PLAYING;
        Notification.Builder builder = new Notification.Builder(this, CHANNEL_ID)
                .setSmallIcon(R.drawable.ic_music)
                .setContentTitle(snapshot.getTitle())
                .setContentText(TextUtils.isEmpty(snapshot.getArtist())
                        ? getString(R.string.notification_playing) : snapshot.getArtist())
                .setContentIntent(PendingIntent.getActivity(this, 0, openAppIntent(),
                        PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE))
                .setOnlyAlertOnce(true)
                .setOngoing(playing)
                .setVisibility(Notification.VISIBILITY_PUBLIC)
                .addAction(new Notification.Action.Builder(
                        R.drawable.ic_previous, getString(R.string.previous),
                        serviceAction(ACTION_PREVIOUS, 1)).build())
                .addAction(new Notification.Action.Builder(
                        playing ? R.drawable.ic_pause : R.drawable.ic_play,
                        getString(playing ? R.string.pause : R.string.play),
                        serviceAction(playing ? ACTION_PAUSE : ACTION_PLAY, 2)).build())
                .addAction(new Notification.Action.Builder(
                        R.drawable.ic_next, getString(R.string.next),
                        serviceAction(ACTION_NEXT, 3)).build());
        builder.setStyle(new Notification.MediaStyle()
                .setMediaSession(mSession.getSessionToken())
                .setShowActionsInCompactView(0, 1, 2));
        return builder.build();
    }

    private PendingIntent serviceAction(String action, int requestCode) {
        Intent intent = new Intent(this, HyperNovaMediaSessionService.class).setAction(action);
        return PendingIntent.getService(this, requestCode, intent,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
    }

    private Intent openAppIntent() {
        return new Intent(this, MainActivity.class)
                .setAction("com.hypernova.media.action.OPEN")
                .addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP);
    }

    private final MediaSession.Callback mSessionCallback = new MediaSession.Callback() {
        @Override
        public void onPlay() {
            mCoordinator.play();
        }

        @Override
        public void onPause() {
            mCoordinator.pause();
        }

        @Override
        public void onSkipToPrevious() {
            mCoordinator.previous();
        }

        @Override
        public void onSkipToNext() {
            mCoordinator.next();
        }

        @Override
        public void onSeekTo(long pos) {
            mCoordinator.seekTo(pos);
        }

        @Override
        public void onPlayFromMediaId(String mediaId, Bundle extras) {
            mCoordinator.playMediaId(mediaId);
        }

        @Override
        public void onSetRating(Rating rating) {
            if (rating != null && rating.getRatingStyle() == Rating.RATING_HEART) {
                mCoordinator.setFavorite(rating.hasHeart());
            }
        }
    };

    @Override
    public void onDestroy() {
        if (mCoordinator != null) {
            mCoordinator.removeListener(this);
        }
        if (mSession != null) {
            mSession.setActive(false);
            mSession.release();
        }
        if (mForeground) {
            stopForeground(STOP_FOREGROUND_REMOVE);
        }
        super.onDestroy();
    }
}
