package com.hypernova.media.local;

import android.Manifest;
import android.content.BroadcastReceiver;
import android.content.ContentResolver;
import android.content.ContentUris;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.PackageManager;
import android.database.Cursor;
import android.media.AudioAttributes;
import android.media.AudioFocusRequest;
import android.media.AudioManager;
import android.net.Uri;
import android.os.Environment;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.os.storage.StorageManager;
import android.os.storage.StorageVolume;
import android.provider.MediaStore;
import android.text.TextUtils;
import android.util.Log;
import android.view.Surface;

import androidx.media3.common.C;
import androidx.media3.common.MediaItem;
import androidx.media3.common.PlaybackException;
import androidx.media3.common.Player;
import androidx.media3.common.VideoSize;
import androidx.media3.exoplayer.ExoPlayer;

import com.hypernova.media.model.MediaItemModel;
import com.hypernova.media.model.MediaSnapshot;
import com.hypernova.media.model.MediaSourceType;
import com.hypernova.media.source.MediaSource;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Removable-storage source backed by StorageManager, MediaStore and Media3 ExoPlayer.
 * It never opens raw USB devices and is portable across AAOS hardware targets.
 */
public final class LocalMediaSource implements MediaSource {
    private static final String TAG = "HyperNovaMedia";
    private static final String UNKNOWN_SENTINEL = "<unknown>";

    private final Context mContext;
    private final ContentResolver mResolver;
    private final StorageManager mStorageManager;
    private final AudioManager mAudioManager;
    private final Handler mMainHandler = new Handler(Looper.getMainLooper());
    private final ExecutorService mScanExecutor = Executors.newSingleThreadExecutor();
    private final AudioAttributes mAudioAttributes = new AudioAttributes.Builder()
            .setUsage(AudioAttributes.USAGE_MEDIA)
            .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
            .build();
    private final AudioFocusRequest mFocusRequest = new AudioFocusRequest.Builder(
            AudioManager.AUDIOFOCUS_GAIN)
            .setAudioAttributes(mAudioAttributes)
            .setOnAudioFocusChangeListener(this::onAudioFocusChanged, mMainHandler)
            .build();

    private Listener mListener;
    private MediaSnapshot mSnapshot;
    private List<MediaItemModel> mItems = Collections.emptyList();
    private String mVolumeName;
    private String mDeviceName;
    private ExoPlayer mPlayer;
    private Surface mVideoSurface;
    private int mCurrentIndex = -1;
    private int mScanGeneration;
    private int mVideoWidth;
    private int mVideoHeight;
    private boolean mActive;
    private boolean mReceiverRegistered;
    private boolean mResumeAfterFocusGain;
    private boolean mStartWhenReady;
    private boolean mHasAudioFocus;

    public LocalMediaSource(Context context) {
        mContext = context.getApplicationContext();
        mResolver = mContext.getContentResolver();
        mStorageManager = mContext.getSystemService(StorageManager.class);
        mAudioManager = mContext.getSystemService(AudioManager.class);
        mSnapshot = MediaSnapshot.builder(MediaSourceType.USB, MediaSnapshot.State.IDLE).build();
        updateMountedVolume();
        if (mVolumeName == null) {
            mSnapshot = MediaSnapshot.builder(MediaSourceType.USB,
                    MediaSnapshot.State.DISCONNECTED).build();
        } else if (!hasMediaPermissions()) {
            mSnapshot = MediaSnapshot.builder(MediaSourceType.USB,
                    MediaSnapshot.State.PERMISSION_REQUIRED)
                    .deviceName(mDeviceName)
                    .build();
        } else {
            mSnapshot = MediaSnapshot.builder(MediaSourceType.USB, MediaSnapshot.State.READY)
                    .deviceName(mDeviceName)
                    .build();
        }
    }

    @Override
    public MediaSourceType getType() {
        return MediaSourceType.USB;
    }

    @Override
    public MediaSnapshot getSnapshot() {
        if (mPlayer == null || !mSnapshot.hasActiveItem()) {
            return mSnapshot;
        }
        try {
            long position = mPlayer.getCurrentPosition();
            return mSnapshot.buildUpon()
                    .position(position, SystemClock.elapsedRealtime())
                    .speed(mPlayer.isPlaying() ? 1f : 0f)
                    .build();
        } catch (IllegalStateException ignored) {
            return mSnapshot;
        }
    }

    @Override
    public void setListener(Listener listener) {
        mListener = listener;
    }

    @Override
    public void activate() {
        mActive = true;
        registerReceiver();
        scan();
    }

    @Override
    public void deactivate() {
        // Playback is intentionally preserved when navigating between sources. The AAOS
        // primary-source switch or an explicit new selection owns the transition.
        mActive = false;
    }

    @Override
    public void retry() {
        scan();
    }

    private void registerReceiver() {
        if (mReceiverRegistered) {
            return;
        }
        IntentFilter filter = new IntentFilter();
        filter.addAction(Intent.ACTION_MEDIA_MOUNTED);
        filter.addAction(Intent.ACTION_MEDIA_EJECT);
        filter.addAction(Intent.ACTION_MEDIA_UNMOUNTED);
        filter.addAction(Intent.ACTION_MEDIA_REMOVED);
        filter.addAction(Intent.ACTION_MEDIA_SCANNER_FINISHED);
        filter.addDataScheme(ContentResolver.SCHEME_FILE);
        mContext.registerReceiver(mStorageReceiver, filter, Context.RECEIVER_EXPORTED);
        mReceiverRegistered = true;
    }

    private void scan() {
        int generation = ++mScanGeneration;
        updateMountedVolume();
        if (mVolumeName == null) {
            stopForStorageRemoval();
            publish(MediaSnapshot.builder(MediaSourceType.USB,
                    MediaSnapshot.State.DISCONNECTED).build());
            return;
        }
        if (!hasMediaPermissions()) {
            publish(MediaSnapshot.builder(MediaSourceType.USB,
                    MediaSnapshot.State.PERMISSION_REQUIRED)
                    .deviceName(mDeviceName)
                    .build());
            return;
        }
        String volume = mVolumeName;
        String deviceName = mDeviceName;
        MediaItemModel activeItem = currentItem();
        String activeMediaId = mPlayer == null || activeItem == null
                ? null : activeItem.getId();
        if (activeMediaId == null) {
            publish(MediaSnapshot.builder(MediaSourceType.USB, MediaSnapshot.State.SCANNING)
                    .deviceName(deviceName)
                    .build());
        }
        mScanExecutor.execute(() -> {
            List<MediaItemModel> result = new ArrayList<>();
            Throwable error = null;
            try {
                queryAudio(volume, result);
                queryVideo(volume, result);
            } catch (RuntimeException e) {
                error = e;
                Log.e(TAG, "MediaStore scan failed for " + volume, e);
            }
            Throwable finalError = error;
            mMainHandler.post(() -> {
                if (generation != mScanGeneration || !volume.equals(mVolumeName)) {
                    return;
                }
                if (finalError != null) {
                    if (activeMediaId != null && mPlayer != null) {
                        return;
                    }
                    publish(MediaSnapshot.builder(MediaSourceType.USB,
                            MediaSnapshot.State.ERROR)
                            .deviceName(deviceName)
                            .errorDetail(finalError.getMessage())
                            .build());
                    return;
                }
                mItems = result;
                if (activeMediaId != null && mPlayer != null) {
                    int refreshedIndex = findItemIndex(activeMediaId);
                    if (refreshedIndex >= 0) {
                        mCurrentIndex = refreshedIndex;
                        MediaSnapshot.State playbackState =
                                mPlayer.getPlaybackState() == Player.STATE_BUFFERING
                                        ? MediaSnapshot.State.LOADING
                                        : mPlayer.isPlaying()
                                                ? MediaSnapshot.State.PLAYING
                                                : MediaSnapshot.State.PAUSED;
                        publish(snapshotForItem(mItems.get(mCurrentIndex), playbackState,
                                mPlayer.getCurrentPosition(), mPlayer.isPlaying()));
                        return;
                    }
                    mCurrentIndex = -1;
                    releasePlayer();
                }
                publish(MediaSnapshot.builder(MediaSourceType.USB,
                        result.isEmpty() ? MediaSnapshot.State.EMPTY : MediaSnapshot.State.READY)
                        .deviceName(deviceName)
                        .items(result)
                        .canBrowse(!result.isEmpty())
                        .build());
            });
        });
    }

    private void queryAudio(String volume, List<MediaItemModel> result) {
        Uri collection = MediaStore.Audio.Media.getContentUri(volume);
        String[] projection = {
                MediaStore.Audio.Media._ID,
                MediaStore.Audio.Media.TITLE,
                MediaStore.Audio.Media.DISPLAY_NAME,
                MediaStore.Audio.Media.ARTIST,
                MediaStore.Audio.Media.ALBUM,
                MediaStore.Audio.Media.GENRE,
                MediaStore.Audio.Media.RELATIVE_PATH,
                MediaStore.Audio.Media.MIME_TYPE,
                MediaStore.Audio.Media.DURATION
        };
        try (Cursor cursor = mResolver.query(collection, projection,
                MediaStore.Audio.Media.IS_MUSIC + " != 0", null,
                MediaStore.Audio.Media.TITLE + " COLLATE NOCASE ASC")) {
            if (cursor == null) {
                return;
            }
            int idColumn = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media._ID);
            while (cursor.moveToNext()) {
                long id = cursor.getLong(idColumn);
                Uri uri = ContentUris.withAppendedId(collection, id);
                String title = value(cursor, MediaStore.Audio.Media.TITLE);
                if (TextUtils.isEmpty(title)) {
                    title = value(cursor, MediaStore.Audio.Media.DISPLAY_NAME);
                }
                result.add(new MediaItemModel(
                        "usb:audio:" + volume + ":" + id,
                        uri,
                        title,
                        normalizeUnknown(value(cursor, MediaStore.Audio.Media.ARTIST)),
                        normalizeUnknown(value(cursor, MediaStore.Audio.Media.ALBUM)),
                        normalizeUnknown(value(cursor, MediaStore.Audio.Media.GENRE)),
                        value(cursor, MediaStore.Audio.Media.RELATIVE_PATH),
                        value(cursor, MediaStore.Audio.Media.MIME_TYPE),
                        uri,
                        longValue(cursor, MediaStore.Audio.Media.DURATION),
                        false,
                        0,
                        0));
            }
        }
    }

    private void queryVideo(String volume, List<MediaItemModel> result) {
        Uri collection = MediaStore.Video.Media.getContentUri(volume);
        String[] projection = {
                MediaStore.Video.Media._ID,
                MediaStore.Video.Media.TITLE,
                MediaStore.Video.Media.DISPLAY_NAME,
                MediaStore.Video.Media.ARTIST,
                MediaStore.Video.Media.ALBUM,
                MediaStore.Video.Media.RELATIVE_PATH,
                MediaStore.Video.Media.MIME_TYPE,
                MediaStore.Video.Media.DURATION,
                MediaStore.Video.Media.WIDTH,
                MediaStore.Video.Media.HEIGHT
        };
        try (Cursor cursor = mResolver.query(collection, projection, null, null,
                MediaStore.Video.Media.TITLE + " COLLATE NOCASE ASC")) {
            if (cursor == null) {
                return;
            }
            int idColumn = cursor.getColumnIndexOrThrow(MediaStore.Video.Media._ID);
            while (cursor.moveToNext()) {
                long id = cursor.getLong(idColumn);
                Uri uri = ContentUris.withAppendedId(collection, id);
                String title = value(cursor, MediaStore.Video.Media.TITLE);
                if (TextUtils.isEmpty(title)) {
                    title = value(cursor, MediaStore.Video.Media.DISPLAY_NAME);
                }
                result.add(new MediaItemModel(
                        "usb:video:" + volume + ":" + id,
                        uri,
                        title,
                        normalizeUnknown(value(cursor, MediaStore.Video.Media.ARTIST)),
                        normalizeUnknown(value(cursor, MediaStore.Video.Media.ALBUM)),
                        null,
                        value(cursor, MediaStore.Video.Media.RELATIVE_PATH),
                        value(cursor, MediaStore.Video.Media.MIME_TYPE),
                        uri,
                        longValue(cursor, MediaStore.Video.Media.DURATION),
                        true,
                        (int) longValue(cursor, MediaStore.Video.Media.WIDTH),
                        (int) longValue(cursor, MediaStore.Video.Media.HEIGHT)));
            }
        }
    }

    private static String value(Cursor cursor, String column) {
        int index = cursor.getColumnIndex(column);
        return index < 0 || cursor.isNull(index) ? null : cursor.getString(index);
    }

    private static long longValue(Cursor cursor, String column) {
        int index = cursor.getColumnIndex(column);
        return index < 0 || cursor.isNull(index) ? 0 : cursor.getLong(index);
    }

    private static String normalizeUnknown(String value) {
        return UNKNOWN_SENTINEL.equals(value) ? null : value;
    }

    private void updateMountedVolume() {
        mVolumeName = null;
        mDeviceName = null;
        if (mStorageManager == null) {
            return;
        }
        for (StorageVolume volume : mStorageManager.getStorageVolumes()) {
            String state = volume.getState();
            if (volume.isRemovable()
                    && (Environment.MEDIA_MOUNTED.equals(state)
                    || Environment.MEDIA_MOUNTED_READ_ONLY.equals(state))) {
                String mediaStoreName = volume.getMediaStoreVolumeName();
                if (mediaStoreName != null) {
                    mVolumeName = mediaStoreName;
                    mDeviceName = volume.getDescription(mContext);
                    return;
                }
            }
        }
    }

    private boolean hasMediaPermissions() {
        return mContext.checkSelfPermission(Manifest.permission.READ_MEDIA_AUDIO)
                == PackageManager.PERMISSION_GRANTED
                && mContext.checkSelfPermission(Manifest.permission.READ_MEDIA_VIDEO)
                == PackageManager.PERMISSION_GRANTED;
    }

    @Override
    public void playMediaId(String mediaId) {
        if (mediaId == null) {
            return;
        }
        int index = findItemIndex(mediaId);
        if (index >= 0) {
            playIndex(index);
        }
    }

    private int findItemIndex(String mediaId) {
        for (int i = 0; i < mItems.size(); i++) {
            if (mediaId.equals(mItems.get(i).getId())) {
                return i;
            }
        }
        return -1;
    }

    private void playIndex(int index) {
        if (index < 0 || index >= mItems.size()) {
            return;
        }
        releasePlayer();
        mCurrentIndex = index;
        MediaItemModel item = mItems.get(index);
        mVideoWidth = item.getWidth();
        mVideoHeight = item.getHeight();
        publish(snapshotForItem(item, MediaSnapshot.State.LOADING, 0, false));
        try {
            androidx.media3.common.AudioAttributes playerAudioAttributes =
                    new androidx.media3.common.AudioAttributes.Builder()
                            .setUsage(C.USAGE_MEDIA)
                            .setContentType(item.isVideo()
                                    ? C.AUDIO_CONTENT_TYPE_MOVIE
                                    : C.AUDIO_CONTENT_TYPE_MUSIC)
                            .build();
            ExoPlayer player = new ExoPlayer.Builder(mContext)
                    .setAudioAttributes(playerAudioAttributes, false)
                    .setHandleAudioBecomingNoisy(true)
                    .build();
            mPlayer = player;
            mStartWhenReady = true;
            player.addListener(mPlayerListener);
            if (item.isVideo() && mVideoSurface != null) {
                player.setVideoSurface(mVideoSurface);
            }
            player.setMediaItem(MediaItem.fromUri(item.getUri()));
            player.prepare();
        } catch (RuntimeException e) {
            Log.e(TAG, "Unable to prepare " + item.getUri(), e);
            publish(snapshotForItem(item, MediaSnapshot.State.ERROR, 0, false)
                    .buildUpon().errorDetail(e.getMessage()).build());
            releasePlayer();
        }
    }

    private final Player.Listener mPlayerListener = new Player.Listener() {
        @Override
        public void onPlaybackStateChanged(int playbackState) {
            if (mPlayer == null || currentItem() == null) {
                return;
            }
            if (playbackState == Player.STATE_BUFFERING) {
                publish(snapshotForItem(currentItem(), MediaSnapshot.State.LOADING,
                        mPlayer.getCurrentPosition(), false));
            } else if (playbackState == Player.STATE_READY) {
                if (mStartWhenReady) {
                    mStartWhenReady = false;
                    if (requestAudioFocus()) {
                        mPlayer.play();
                    } else {
                        publish(snapshotForItem(currentItem(),
                                MediaSnapshot.State.FOCUS_INTERRUPTED,
                                mPlayer.getCurrentPosition(), false));
                    }
                } else {
                    publish(snapshotForItem(currentItem(),
                            mPlayer.isPlaying()
                                    ? MediaSnapshot.State.PLAYING
                                    : MediaSnapshot.State.PAUSED,
                            mPlayer.getCurrentPosition(), mPlayer.isPlaying()));
                }
            } else if (playbackState == Player.STATE_ENDED) {
                if (hasNext()) {
                    playIndex(mCurrentIndex + 1);
                } else {
                    publish(snapshotForItem(currentItem(), MediaSnapshot.State.PAUSED,
                            currentItem().getDurationMs(), false));
                    abandonAudioFocus();
                }
            }
        }

        @Override
        public void onIsPlayingChanged(boolean isPlaying) {
            if (mPlayer == null || currentItem() == null
                    || mPlayer.getPlaybackState() != Player.STATE_READY) {
                return;
            }
            publish(snapshotForItem(currentItem(),
                    isPlaying ? MediaSnapshot.State.PLAYING : MediaSnapshot.State.PAUSED,
                    mPlayer.getCurrentPosition(), isPlaying));
        }

        @Override
        public void onPlayerError(PlaybackException error) {
            MediaItemModel item = currentItem();
            if (item != null) {
                Log.e(TAG, "Playback failed for " + item.getUri(), error);
                publish(snapshotForItem(item, MediaSnapshot.State.ERROR,
                        mPlayer == null ? 0 : mPlayer.getCurrentPosition(), false)
                        .buildUpon()
                        .errorDetail(error.getErrorCodeName())
                        .build());
            }
            releasePlayer();
        }

        @Override
        public void onVideoSizeChanged(VideoSize videoSize) {
            mVideoWidth = videoSize.width;
            mVideoHeight = videoSize.height;
            if (mSnapshot.hasActiveItem() && mSnapshot.isVideo()) {
                publish(mSnapshot.buildUpon()
                        .videoSize(mVideoWidth, mVideoHeight)
                        .build());
            }
        }
    };

    @Override
    public void play() {
        if (mPlayer == null) {
            if (!mItems.isEmpty()) {
                playIndex(mCurrentIndex >= 0 ? mCurrentIndex : 0);
            }
            return;
        }
        if (!requestAudioFocus()) {
            publish(snapshotForItem(currentItem(), MediaSnapshot.State.FOCUS_INTERRUPTED,
                    mPlayer.getCurrentPosition(), false));
            return;
        }
        if (mPlayer.getPlaybackState() == Player.STATE_ENDED) {
            mPlayer.seekTo(0);
        }
        mPlayer.play();
        publish(snapshotForItem(currentItem(), MediaSnapshot.State.PLAYING,
                mPlayer.getCurrentPosition(), true));
    }

    @Override
    public void pause() {
        mResumeAfterFocusGain = false;
        pauseInternal(MediaSnapshot.State.PAUSED);
        abandonAudioFocus();
    }

    private void pauseInternal(MediaSnapshot.State state) {
        if (mPlayer == null) {
            return;
        }
        if (mPlayer.isPlaying()) {
            mPlayer.pause();
        }
        publish(snapshotForItem(currentItem(), state, mPlayer.getCurrentPosition(), false));
    }

    @Override
    public void previous() {
        if (mCurrentIndex > 0) {
            playIndex(mCurrentIndex - 1);
        } else {
            seekTo(0);
        }
    }

    @Override
    public void next() {
        if (hasNext()) {
            playIndex(mCurrentIndex + 1);
        }
    }

    private boolean hasNext() {
        return mCurrentIndex >= 0 && mCurrentIndex + 1 < mItems.size();
    }

    @Override
    public void seekTo(long positionMs) {
        if (mPlayer == null) {
            return;
        }
        long duration = mPlayer.getDuration();
        if (duration == C.TIME_UNSET || duration < 0) {
            duration = currentItem() == null ? 0 : currentItem().getDurationMs();
        }
        long target = Math.max(0, Math.min(positionMs, duration));
        mPlayer.seekTo(target);
        publish(snapshotForItem(currentItem(),
                mPlayer.isPlaying() ? MediaSnapshot.State.PLAYING : MediaSnapshot.State.PAUSED,
                target, mPlayer.isPlaying()));
    }

    @Override
    public void setVideoSurface(Surface surface) {
        mVideoSurface = surface;
        if (mPlayer != null && currentItem() != null && currentItem().isVideo()) {
            if (surface == null) {
                mPlayer.clearVideoSurface();
            } else {
                mPlayer.setVideoSurface(surface);
            }
        }
    }

    private MediaSnapshot snapshotForItem(MediaItemModel item, MediaSnapshot.State state,
            long positionMs, boolean playing) {
        if (item == null) {
            return MediaSnapshot.builder(MediaSourceType.USB, MediaSnapshot.State.ERROR)
                    .deviceName(mDeviceName)
                    .build();
        }
        return MediaSnapshot.builder(MediaSourceType.USB, state)
                .deviceName(mDeviceName)
                .title(item.getTitle())
                .artist(item.getArtist())
                .album(item.getAlbum())
                .artworkUri(item.getArtworkUri() == null ? null : item.getArtworkUri().toString())
                .position(positionMs, SystemClock.elapsedRealtime())
                .duration(item.getDurationMs())
                .speed(playing ? 1f : 0f)
                .canPlay(!playing)
                .canPause(playing)
                .canPrevious(mCurrentIndex > 0 || positionMs > 0)
                .canNext(hasNext())
                .canSeek(true)
                .canBrowse(true)
                .video(item.isVideo())
                .videoSize(mVideoWidth > 0 ? mVideoWidth : item.getWidth(),
                        mVideoHeight > 0 ? mVideoHeight : item.getHeight())
                .items(mItems)
                .build();
    }

    private MediaItemModel currentItem() {
        return mCurrentIndex >= 0 && mCurrentIndex < mItems.size()
                ? mItems.get(mCurrentIndex) : null;
    }

    private boolean requestAudioFocus() {
        if (mAudioManager == null || mHasAudioFocus) {
            return true;
        }
        mHasAudioFocus = mAudioManager.requestAudioFocus(mFocusRequest)
                == AudioManager.AUDIOFOCUS_REQUEST_GRANTED;
        return mHasAudioFocus;
    }

    private void abandonAudioFocus() {
        if (mAudioManager != null && mHasAudioFocus) {
            mHasAudioFocus = false;
            mAudioManager.abandonAudioFocusRequest(mFocusRequest);
        }
    }

    private void onAudioFocusChanged(int change) {
        switch (change) {
            case AudioManager.AUDIOFOCUS_LOSS_TRANSIENT:
            case AudioManager.AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK:
                mHasAudioFocus = false;
                if (mPlayer != null) {
                    mResumeAfterFocusGain = mPlayer.isPlaying();
                    if (mResumeAfterFocusGain) {
                        pauseInternal(MediaSnapshot.State.FOCUS_INTERRUPTED);
                    }
                }
                break;
            case AudioManager.AUDIOFOCUS_LOSS:
                mHasAudioFocus = false;
                mResumeAfterFocusGain = false;
                pauseInternal(MediaSnapshot.State.PAUSED);
                break;
            case AudioManager.AUDIOFOCUS_GAIN:
                mHasAudioFocus = true;
                if (mResumeAfterFocusGain) {
                    mResumeAfterFocusGain = false;
                    play();
                }
                break;
            default:
                break;
        }
    }

    private void stopForStorageRemoval() {
        ++mScanGeneration;
        mItems = Collections.emptyList();
        mCurrentIndex = -1;
        releasePlayer();
    }

    private void releasePlayer() {
        if (mPlayer != null) {
            mStartWhenReady = false;
            mPlayer.removeListener(mPlayerListener);
            mPlayer.release();
            mPlayer = null;
        }
        abandonAudioFocus();
    }

    private final BroadcastReceiver mStorageReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            String oldVolume = mVolumeName;
            updateMountedVolume();
            if (oldVolume != null && !oldVolume.equals(mVolumeName)) {
                stopForStorageRemoval();
                publish(MediaSnapshot.builder(MediaSourceType.USB,
                        MediaSnapshot.State.DISCONNECTED).build());
            }
            if (mActive) {
                scan();
            }
        }
    };

    private void publish(MediaSnapshot snapshot) {
        mSnapshot = snapshot;
        Listener listener = mListener;
        if (listener != null) {
            listener.onSourceChanged(this, snapshot);
        }
    }

    @Override
    public void release() {
        ++mScanGeneration;
        if (mReceiverRegistered) {
            mContext.unregisterReceiver(mStorageReceiver);
            mReceiverRegistered = false;
        }
        releasePlayer();
        mScanExecutor.shutdownNow();
        mListener = null;
    }
}
