package com.hypernova.media;

import android.Manifest;
import android.app.Activity;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.res.ColorStateList;
import android.graphics.Bitmap;
import android.graphics.Matrix;
import android.graphics.SurfaceTexture;
import android.net.Uri;
import android.os.Bundle;
import android.provider.Settings;
import android.text.TextUtils;
import android.util.Size;
import android.view.LayoutInflater;
import android.view.Surface;
import android.view.TextureView;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowInsets;
import android.view.WindowInsetsController;
import android.widget.BaseAdapter;
import android.widget.Button;
import android.widget.HorizontalScrollView;
import android.widget.ImageButton;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ListView;
import android.widget.SeekBar;
import android.widget.TextView;

import com.hypernova.media.model.MediaItemModel;
import com.hypernova.media.model.MediaSnapshot;
import com.hypernova.media.model.MediaSourceType;
import com.hypernova.media.playback.PlaybackCoordinator;
import com.hypernova.media.safety.VehicleUxRestrictionClient;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TreeMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** Portrait, state-driven automotive media surface. All runtime content comes from real sources. */
public final class MainActivity extends Activity implements PlaybackCoordinator.Listener,
        VehicleUxRestrictionClient.Listener {
    private static final int REQUEST_MEDIA_PERMISSION = 41;
    private static final int REQUEST_BLUETOOTH_PERMISSION = 42;

    private enum LibraryFilter {
        FOLDERS, TRACKS, ARTISTS, ALBUMS, GENRES, VIDEOS, QUEUE
    }

    private LinearLayout mRoot;
    private View mHeader;
    private View mSourceSelector;
    private View mHeaderDivider;
    private View mContentContainer;
    private View mFullscreenPanel;
    private View mHomePanel;
    private View mStatePanel;
    private View mNowPlayingPanel;
    private View mLibraryPanel;

    private View mRadioCard;
    private View mBluetoothCard;
    private View mUsbCard;
    private TextView mRadioTitle;
    private TextView mBluetoothTitle;
    private TextView mUsbTitle;
    private TextView mRadioSubtitle;
    private TextView mBluetoothSubtitle;
    private TextView mUsbSubtitle;
    private ImageView mRadioIcon;
    private ImageView mBluetoothIcon;
    private ImageView mUsbIcon;
    private TextView mHeaderSource;

    private ImageView mStateIcon;
    private TextView mStateStatus;
    private TextView mStateTitle;
    private TextView mStateMessage;
    private Button mStateAction;

    private ImageView mArtwork;
    private TextureView mVideoSurface;
    private TextureView mFullscreenVideoSurface;
    private ImageButton mFullscreenButton;
    private TextView mSourceBadge;
    private TextView mMediaTitle;
    private TextView mMediaSubtitle;
    private TextView mMediaTertiary;
    private SeekBar mSeekBar;
    private TextView mElapsed;
    private TextView mDuration;
    private ImageButton mPlayPause;
    private ImageButton mPrevious;
    private ImageButton mNext;
    private ImageButton mRewind;
    private ImageButton mForward;
    private Button mFavoriteButton;
    private Button mSecondaryButton;

    private TextView mLibraryCount;
    private HorizontalScrollView mLibraryFilters;
    private ListView mMediaList;
    private final Map<LibraryFilter, Button> mFilterButtons =
            new java.util.EnumMap<>(LibraryFilter.class);
    private final LibraryAdapter mLibraryAdapter = new LibraryAdapter();

    private PlaybackCoordinator mCoordinator;
    private VehicleUxRestrictionClient mUxRestrictionClient;
    private MediaSourceType mSelectedSource;
    private MediaSnapshot mSnapshot;
    private LibraryFilter mLibraryFilter = LibraryFilter.TRACKS;
    private String mGroupValue;
    private boolean mShowingLibrary;
    // Do not reopen a prior source's browse hierarchy on a fresh Activity. A deliberate USB
    // card tap or Browse media action opens it, preserving one-step system Back navigation.
    private boolean mLibraryDismissed = true;
    private boolean mFullscreen;
    private boolean mUserSeeking;
    private Surface mAttachedSurface;
    private TextureView mAttachedTexture;
    private final ExecutorService mArtworkExecutor = Executors.newSingleThreadExecutor();
    private int mArtworkGeneration;
    private String mArtworkKey;
    private int mRootPaddingLeft;
    private int mRootPaddingTop;
    private int mRootPaddingRight;
    private int mRootPaddingBottom;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);
        bindViews();
        bindActions();
        configureVideoSurfaces();
        configureSystemBars();
        mCoordinator = ((HyperNovaMediaApplication) getApplication()).getPlaybackCoordinator();
        mUxRestrictionClient = new VehicleUxRestrictionClient(this, this);
        render(null, null);
    }

    @Override
    protected void onStart() {
        super.onStart();
        mCoordinator.addListener(this);
        startPositionUpdates();
    }

    @Override
    protected void onStop() {
        stopPositionUpdates();
        mCoordinator.removeListener(this);
        if (mSnapshot != null && mSnapshot.isVideo()
                && mSnapshot.getState() == MediaSnapshot.State.PLAYING) {
            mCoordinator.pause();
        }
        super.onStop();
    }

    @Override
    protected void onDestroy() {
        detachVideoSurface();
        if (mUxRestrictionClient != null) {
            mUxRestrictionClient.close();
        }
        mArtworkExecutor.shutdownNow();
        super.onDestroy();
    }

    @Override
    protected void onResume() {
        super.onResume();
        configureSystemBars();
        if (mCoordinator != null && mSnapshot != null
                && mSnapshot.getState() == MediaSnapshot.State.PERMISSION_REQUIRED) {
            mCoordinator.onPermissionsChanged();
        }
    }

    @Override
    public void onWindowFocusChanged(boolean hasFocus) {
        super.onWindowFocusChanged(hasFocus);
        if (hasFocus) {
            configureSystemBars();
        }
    }

    private void bindViews() {
        mRoot = findViewById(R.id.root);
        mRootPaddingLeft = mRoot.getPaddingLeft();
        mRootPaddingTop = mRoot.getPaddingTop();
        mRootPaddingRight = mRoot.getPaddingRight();
        mRootPaddingBottom = mRoot.getPaddingBottom();
        mHeader = findViewById(R.id.header);
        mSourceSelector = findViewById(R.id.source_selector);
        mHeaderDivider = findViewById(R.id.header_divider);
        mContentContainer = findViewById(R.id.content_container);
        mFullscreenPanel = findViewById(R.id.fullscreen_video_panel);
        mHomePanel = findViewById(R.id.home_panel);
        mStatePanel = findViewById(R.id.state_panel);
        mNowPlayingPanel = findViewById(R.id.now_playing_panel);
        mLibraryPanel = findViewById(R.id.library_panel);

        mRadioCard = findViewById(R.id.card_radio);
        mBluetoothCard = findViewById(R.id.card_bluetooth);
        mUsbCard = findViewById(R.id.card_usb);
        mRadioTitle = findViewById(R.id.title_radio);
        mBluetoothTitle = findViewById(R.id.title_bluetooth);
        mUsbTitle = findViewById(R.id.title_usb);
        mRadioSubtitle = findViewById(R.id.subtitle_radio);
        mBluetoothSubtitle = findViewById(R.id.subtitle_bluetooth);
        mUsbSubtitle = findViewById(R.id.subtitle_usb);
        mRadioIcon = findViewById(R.id.icon_radio);
        mBluetoothIcon = findViewById(R.id.icon_bluetooth);
        mUsbIcon = findViewById(R.id.icon_usb);
        mHeaderSource = findViewById(R.id.header_source);

        mStateIcon = findViewById(R.id.state_icon);
        mStateStatus = findViewById(R.id.state_status);
        mStateTitle = findViewById(R.id.state_title);
        mStateMessage = findViewById(R.id.state_message);
        mStateAction = findViewById(R.id.state_action);

        mArtwork = findViewById(R.id.artwork);
        mVideoSurface = findViewById(R.id.video_surface);
        mFullscreenVideoSurface = findViewById(R.id.fullscreen_video_surface);
        mFullscreenButton = findViewById(R.id.button_fullscreen);
        mSourceBadge = findViewById(R.id.source_badge);
        mMediaTitle = findViewById(R.id.media_title_primary);
        mMediaSubtitle = findViewById(R.id.media_subtitle);
        mMediaTertiary = findViewById(R.id.media_tertiary);
        mSeekBar = findViewById(R.id.media_seekbar);
        mElapsed = findViewById(R.id.elapsed_time);
        mDuration = findViewById(R.id.duration_time);
        mPlayPause = findViewById(R.id.button_play_pause);
        mPrevious = findViewById(R.id.button_previous);
        mNext = findViewById(R.id.button_next);
        mRewind = findViewById(R.id.button_rewind);
        mForward = findViewById(R.id.button_forward);
        mFavoriteButton = findViewById(R.id.button_favorite);
        mSecondaryButton = findViewById(R.id.button_secondary);

        mLibraryCount = findViewById(R.id.library_count);
        mLibraryFilters = findViewById(R.id.library_filters);
        mMediaList = findViewById(R.id.media_list);
        mMediaList.setAdapter(mLibraryAdapter);
        mFilterButtons.put(LibraryFilter.FOLDERS, findViewById(R.id.chip_folders));
        mFilterButtons.put(LibraryFilter.TRACKS, findViewById(R.id.chip_tracks));
        mFilterButtons.put(LibraryFilter.ARTISTS, findViewById(R.id.chip_artists));
        mFilterButtons.put(LibraryFilter.ALBUMS, findViewById(R.id.chip_albums));
        mFilterButtons.put(LibraryFilter.GENRES, findViewById(R.id.chip_genres));
        mFilterButtons.put(LibraryFilter.VIDEOS, findViewById(R.id.chip_videos));
        mFilterButtons.put(LibraryFilter.QUEUE, findViewById(R.id.chip_queue));
    }

    private void bindActions() {
        findViewById(R.id.button_back).setOnClickListener(view -> finish());
        mRadioCard.setOnClickListener(view -> selectSource(MediaSourceType.RADIO));
        mBluetoothCard.setOnClickListener(view -> selectSource(MediaSourceType.BLUETOOTH));
        mUsbCard.setOnClickListener(view -> selectSource(MediaSourceType.USB));
        mPlayPause.setOnClickListener(view -> {
            if (mSnapshot != null && mSnapshot.getState() == MediaSnapshot.State.PLAYING) {
                mCoordinator.pause();
            } else {
                mCoordinator.play();
            }
        });
        mPrevious.setOnClickListener(view -> mCoordinator.previous());
        mNext.setOnClickListener(view -> mCoordinator.next());
        mRewind.setOnClickListener(view -> seekRelative(-10_000));
        mForward.setOnClickListener(view -> seekRelative(10_000));
        mFavoriteButton.setOnClickListener(view -> {
            if (mSnapshot != null && mSnapshot.supportsFavorite()) {
                mCoordinator.setFavorite(!mSnapshot.isFavorite());
            }
        });
        mFullscreenButton.setOnClickListener(view -> enterFullscreen());
        findViewById(R.id.button_exit_fullscreen).setOnClickListener(view -> exitFullscreen());
        mSecondaryButton.setOnClickListener(view -> openLibrary());
        findViewById(R.id.button_library_back).setOnClickListener(view -> closeLibraryLevel());
        mMediaList.setOnItemClickListener((parent, view, position, id) ->
                onLibraryEntryClicked(mLibraryAdapter.getEntry(position)));
        for (Map.Entry<LibraryFilter, Button> entry : mFilterButtons.entrySet()) {
            LibraryFilter filter = entry.getKey();
            entry.getValue().setOnClickListener(view -> setLibraryFilter(filter));
        }
        mSeekBar.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override
            public void onProgressChanged(SeekBar seekBar, int progress, boolean fromUser) {
                if (fromUser && mSnapshot != null && mSnapshot.getDurationMs() > 0) {
                    mElapsed.setText(formatTime(
                            mSnapshot.getDurationMs() * progress / seekBar.getMax()));
                }
            }

            @Override
            public void onStartTrackingTouch(SeekBar seekBar) {
                mUserSeeking = true;
            }

            @Override
            public void onStopTrackingTouch(SeekBar seekBar) {
                if (mSnapshot != null && mSnapshot.getDurationMs() > 0) {
                    mCoordinator.seekTo(mSnapshot.getDurationMs()
                            * seekBar.getProgress() / seekBar.getMax());
                }
                mUserSeeking = false;
            }
        });
    }

    private void selectSource(MediaSourceType source) {
        mShowingLibrary = false;
        mLibraryDismissed = false;
        mGroupValue = null;
        mLibraryFilter = source == MediaSourceType.USB
                ? LibraryFilter.TRACKS : LibraryFilter.QUEUE;
        mCoordinator.selectSource(source);
    }

    @Override
    public void onPlaybackChanged(MediaSourceType selectedSource, MediaSnapshot snapshot) {
        render(selectedSource, snapshot);
    }

    private void render(MediaSourceType selectedSource, MediaSnapshot snapshot) {
        mSelectedSource = selectedSource;
        mSnapshot = snapshot;
        updateSourceCards();
        if (selectedSource == null || snapshot == null) {
            mHeaderSource.setText(R.string.select_source);
            showOnly(mHomePanel);
            return;
        }
        mHeaderSource.setText(sourceLabel(selectedSource).toUpperCase(Locale.getDefault()));
        if (snapshot.isVideo() && isVideoRestricted()) {
            showState(sourceIcon(selectedSource), R.string.status_unavailable,
                    R.string.video_restricted_title, R.string.video_restricted_message,
                    0, null);
            return;
        }
        if (mShowingLibrary || shouldOpenLibrary(snapshot)) {
            mShowingLibrary = true;
            renderLibrary();
        } else if (requiresStatePanel(snapshot)) {
            renderSourceState(selectedSource, snapshot);
        } else if (snapshot.hasActiveItem()) {
            renderNowPlaying(snapshot);
        } else {
            renderSourceState(selectedSource, snapshot);
        }
    }

    private static boolean requiresStatePanel(MediaSnapshot snapshot) {
        switch (snapshot.getState()) {
            case CONNECTING:
            case EMPTY:
            case DISCONNECTED:
            case UNAVAILABLE:
            case PERMISSION_REQUIRED:
            case ERROR:
                return true;
            default:
                return false;
        }
    }

    private boolean shouldOpenLibrary(MediaSnapshot snapshot) {
        return !mLibraryDismissed
                && mSelectedSource == MediaSourceType.USB
                && snapshot.getState() == MediaSnapshot.State.READY
                && !snapshot.getItems().isEmpty();
    }

    private void updateSourceCards() {
        updateCard(MediaSourceType.RADIO, mRadioCard, mRadioTitle, mRadioIcon);
        updateCard(MediaSourceType.BLUETOOTH, mBluetoothCard, mBluetoothTitle, mBluetoothIcon);
        updateCard(MediaSourceType.USB, mUsbCard, mUsbTitle, mUsbIcon);
        MediaSnapshot radio = mCoordinator == null ? null
                : mCoordinator.getSourceSnapshot(MediaSourceType.RADIO);
        MediaSnapshot bluetooth = mCoordinator == null ? null
                : mCoordinator.getSourceSnapshot(MediaSourceType.BLUETOOTH);
        MediaSnapshot usb = mCoordinator == null ? null
                : mCoordinator.getSourceSnapshot(MediaSourceType.USB);
        mRadioSubtitle.setText(radioSubtitle(radio));
        mBluetoothSubtitle.setText(bluetoothSubtitle(bluetooth));
        mUsbSubtitle.setText(usbSubtitle(usb));
    }

    private void updateCard(MediaSourceType source, View card, TextView title, ImageView icon) {
        boolean selected = source == mSelectedSource;
        card.setBackgroundResource(selected
                ? R.drawable.bg_source_card_selected : R.drawable.bg_source_card);
        int color = getColor(selected
                ? R.color.hypernova_cyan : R.color.hypernova_icon_primary);
        title.setTextColor(color);
        icon.setImageTintList(ColorStateList.valueOf(color));
        card.setContentDescription(getString(selected
                ? R.string.content_description_source_selected
                : sourceLabelRes(source), sourceLabel(source)));
        card.setSelected(selected);
    }

    private CharSequence radioSubtitle(MediaSnapshot snapshot) {
        if (snapshot == null || snapshot.getState() == MediaSnapshot.State.IDLE) {
            return getText(R.string.fm_am);
        }
        if (snapshot.getState() == MediaSnapshot.State.UNAVAILABLE
                || snapshot.getState() == MediaSnapshot.State.ERROR) {
            return getText(R.string.status_unavailable);
        }
        return getText(R.string.fm_am);
    }

    private CharSequence bluetoothSubtitle(MediaSnapshot snapshot) {
        if (snapshot == null) return getText(R.string.bluetooth_no_device);
        if (snapshot.getState() == MediaSnapshot.State.PERMISSION_REQUIRED) {
            return getText(R.string.permission_required);
        }
        if (snapshot.getState() == MediaSnapshot.State.UNAVAILABLE) {
            return getText(R.string.bluetooth_off);
        }
        if (snapshot.getState() == MediaSnapshot.State.CONNECTING) {
            return getText(R.string.bluetooth_connecting);
        }
        if (!TextUtils.isEmpty(snapshot.getDeviceName())) {
            return snapshot.getDeviceName();
        }
        return getText(R.string.bluetooth_no_device);
    }

    private CharSequence usbSubtitle(MediaSnapshot snapshot) {
        if (snapshot == null || snapshot.getState() == MediaSnapshot.State.DISCONNECTED) {
            return getText(R.string.usb_not_connected);
        }
        if (snapshot.getState() == MediaSnapshot.State.SCANNING) {
            return getText(R.string.usb_scanning);
        }
        if (snapshot.getState() == MediaSnapshot.State.PERMISSION_REQUIRED) {
            return getText(R.string.permission_required);
        }
        if (snapshot.getState() == MediaSnapshot.State.EMPTY) {
            return getText(R.string.usb_empty);
        }
        return TextUtils.isEmpty(snapshot.getDeviceName())
                ? getText(R.string.usb_detected) : snapshot.getDeviceName();
    }

    private void renderSourceState(MediaSourceType source, MediaSnapshot snapshot) {
        if (snapshot.getState() == MediaSnapshot.State.LOADING
                || snapshot.getState() == MediaSnapshot.State.BUFFERING) {
            showState(sourceIcon(source), R.string.status_loading, R.string.loading_media,
                    R.string.choose_source_description, 0, null);
            return;
        }
        if (snapshot.getState() == MediaSnapshot.State.CONNECTING) {
            showState(sourceIcon(source), R.string.status_connecting,
                    source == MediaSourceType.BLUETOOTH
                            ? R.string.bluetooth_connecting : R.string.loading_media,
                    source == MediaSourceType.BLUETOOTH
                            ? R.string.bluetooth_no_media_message : R.string.choose_source_description,
                    0, null);
            return;
        }
        if (source == MediaSourceType.RADIO) {
            renderRadioState(snapshot);
        } else if (source == MediaSourceType.BLUETOOTH) {
            renderBluetoothState(snapshot);
        } else {
            renderUsbState(snapshot);
        }
    }

    private void renderRadioState(MediaSnapshot snapshot) {
        if (snapshot.getState() == MediaSnapshot.State.UNAVAILABLE
                || snapshot.getState() == MediaSnapshot.State.ERROR) {
            showState(R.drawable.ic_radio, R.string.status_unavailable,
                    R.string.radio_unavailable, R.string.radio_no_signal,
                    R.string.retry, mCoordinator::retry);
        } else {
            boolean playing = snapshot.getState() == MediaSnapshot.State.PLAYING;
            boolean paused = snapshot.getState() == MediaSnapshot.State.PAUSED;
            showState(R.drawable.ic_radio,
                    playing ? R.string.status_playing
                            : paused ? R.string.status_paused : R.string.status_ready,
                    R.string.radio_ready, R.string.radio_no_station,
                    playing && snapshot.canPause() ? R.string.pause
                            : snapshot.canPlay() ? R.string.play : 0,
                    playing && snapshot.canPause() ? mCoordinator::pause
                            : snapshot.canPlay() ? mCoordinator::play : null);
        }
    }

    private void renderBluetoothState(MediaSnapshot snapshot) {
        switch (snapshot.getState()) {
            case PERMISSION_REQUIRED:
                showState(R.drawable.ic_bluetooth, R.string.status_unavailable,
                        R.string.bluetooth_permission_required,
                        R.string.bluetooth_no_media_message,
                        R.string.grant_permission, this::requestBluetoothPermission);
                break;
            case UNAVAILABLE:
                showState(R.drawable.ic_bluetooth, R.string.status_unavailable,
                        R.string.bluetooth_off, R.string.bluetooth_no_device,
                        R.string.open_bluetooth_settings, this::openBluetoothSettings);
                break;
            case DISCONNECTED:
                showState(R.drawable.ic_bluetooth, R.string.status_unavailable,
                        R.string.bluetooth_no_device, R.string.bluetooth_no_media_message,
                        R.string.open_bluetooth_settings, this::openBluetoothSettings);
                break;
            case ERROR:
                showState(R.drawable.ic_bluetooth, R.string.status_error,
                        R.string.playback_error, R.string.bluetooth_metadata_unavailable,
                        R.string.retry, mCoordinator::retry);
                break;
            default:
                boolean playing = snapshot.getState() == MediaSnapshot.State.PLAYING;
                boolean paused = snapshot.getState() == MediaSnapshot.State.PAUSED;
                showState(R.drawable.ic_bluetooth,
                        playing ? R.string.status_playing
                                : paused ? R.string.status_paused : R.string.status_connected,
                        playing ? R.string.bluetooth_media_active_title
                                : paused ? R.string.bluetooth_media_paused_title
                                        : R.string.bluetooth_no_media_title,
                        playing || paused ? R.string.bluetooth_metadata_unavailable
                                : R.string.bluetooth_no_media_message,
                        playing && snapshot.canPause() ? R.string.pause
                                : snapshot.canPlay() ? R.string.play : 0,
                        playing && snapshot.canPause() ? mCoordinator::pause
                                : snapshot.canPlay() ? mCoordinator::play : null);
                break;
        }
    }

    private void renderUsbState(MediaSnapshot snapshot) {
        switch (snapshot.getState()) {
            case PERMISSION_REQUIRED:
                showState(R.drawable.ic_usb, R.string.status_unavailable,
                        R.string.usb_permission_required, R.string.usb_permission_message,
                        R.string.grant_permission, this::requestMediaPermission);
                break;
            case SCANNING:
                showState(R.drawable.ic_usb, R.string.status_scanning,
                        R.string.usb_scanning_title, R.string.usb_scanning_message, 0, null);
                break;
            case EMPTY:
                showState(R.drawable.ic_usb, R.string.status_ready,
                        R.string.usb_empty_title, R.string.usb_empty_message,
                        R.string.retry, mCoordinator::retry);
                break;
            case DISCONNECTED:
                showState(R.drawable.ic_usb, R.string.status_unavailable,
                        R.string.usb_not_connected_title, R.string.usb_not_connected_message,
                        0, null);
                break;
            case ERROR:
                showState(R.drawable.ic_usb, R.string.status_error,
                        R.string.playback_error, R.string.unsupported_media_message,
                        R.string.retry, mCoordinator::retry);
                break;
            default:
                showState(R.drawable.ic_usb, R.string.status_ready,
                        R.string.usb_ready_title, R.string.usb_ready_message,
                        snapshot.getItems().isEmpty() ? 0 : R.string.browse_media,
                        snapshot.getItems().isEmpty() ? null : this::openLibrary);
                break;
        }
    }

    private void showState(int icon, int status, int title, int message, int actionText,
            Runnable action) {
        showOnly(mStatePanel);
        mStateIcon.setImageResource(icon);
        mStateStatus.setText(status);
        mStateTitle.setText(title);
        mStateMessage.setText(message);
        mStateAction.setVisibility(actionText == 0 ? View.GONE : View.VISIBLE);
        if (actionText != 0) {
            mStateAction.setText(actionText);
            mStateAction.setOnClickListener(view -> {
                if (action != null) action.run();
            });
        } else {
            mStateAction.setOnClickListener(null);
        }
    }

    private void renderNowPlaying(MediaSnapshot snapshot) {
        showOnly(mNowPlayingPanel);
        mSourceBadge.setText(sourceLabel(mSelectedSource).toUpperCase(Locale.getDefault()));
        mMediaTitle.setText(snapshot.getTitle());
        mMediaSubtitle.setText(TextUtils.isEmpty(snapshot.getArtist())
                ? getText(R.string.unknown_artist) : snapshot.getArtist());
        mMediaTertiary.setText(TextUtils.isEmpty(snapshot.getAlbum())
                ? stateLabel(snapshot.getState()) : snapshot.getAlbum());
        boolean playing = snapshot.getState() == MediaSnapshot.State.PLAYING;
        mPlayPause.setImageResource(playing ? R.drawable.ic_pause : R.drawable.ic_play);
        mPlayPause.setContentDescription(getText(playing ? R.string.pause : R.string.play));
        setEnabled(mPlayPause, playing ? snapshot.canPause() : snapshot.canPlay());
        setEnabled(mPrevious, snapshot.canPrevious());
        setEnabled(mNext, snapshot.canNext());
        setEnabled(mRewind, snapshot.canSeek());
        setEnabled(mForward, snapshot.canSeek());
        mSeekBar.setEnabled(snapshot.canSeek() && snapshot.getDurationMs() > 0);
        mSecondaryButton.setVisibility(snapshot.getItems().isEmpty() ? View.GONE : View.VISIBLE);
        mSecondaryButton.setText(mSelectedSource == MediaSourceType.RADIO
                ? R.string.stations : R.string.browse_media);
        mFavoriteButton.setVisibility(snapshot.supportsFavorite() ? View.VISIBLE : View.GONE);
        if (snapshot.supportsFavorite()) {
            mFavoriteButton.setText(snapshot.isFavorite()
                    ? R.string.remove_favorite : R.string.add_favorite);
        }
        boolean showVideo = snapshot.isVideo() && !isVideoRestricted();
        mVideoSurface.setVisibility(showVideo ? View.VISIBLE : View.GONE);
        mFullscreenButton.setVisibility(showVideo ? View.VISIBLE : View.GONE);
        mArtwork.setVisibility(showVideo ? View.GONE : View.VISIBLE);
        if (showVideo) {
            if (mVideoSurface.isAvailable()) attachVideoSurface(mVideoSurface);
            configureVideoTransform(mVideoSurface, snapshot);
        } else {
            detachVideoSurface();
            updateArtwork(snapshot);
        }
        updatePosition();
    }

    private void updateArtwork(MediaSnapshot snapshot) {
        String key = snapshot.getArtworkUri() + ":" + snapshot.getTitle();
        if (key.equals(mArtworkKey)) {
            return;
        }
        mArtworkKey = key;
        int generation = ++mArtworkGeneration;
        Bitmap embedded = snapshot.getArtwork();
        if (embedded != null) {
            showArtworkBitmap(embedded);
            return;
        }
        mArtwork.setImageResource(sourceIcon(mSelectedSource));
        mArtwork.setImageTintList(ColorStateList.valueOf(getColor(R.color.hypernova_cyan)));
        if (TextUtils.isEmpty(snapshot.getArtworkUri())) {
            return;
        }
        Uri uri;
        try {
            uri = Uri.parse(snapshot.getArtworkUri());
        } catch (RuntimeException ignored) {
            return;
        }
        if (!ContentResolverScheme.isLocalContent(uri)) {
            return;
        }
        mArtworkExecutor.execute(() -> {
            Bitmap bitmap = null;
            try {
                bitmap = getContentResolver().loadThumbnail(uri, new Size(720, 720), null);
            } catch (Exception ignored) {
            }
            Bitmap result = bitmap;
            runOnUiThread(() -> {
                if (generation == mArtworkGeneration && result != null && !isDestroyed()) {
                    showArtworkBitmap(result);
                }
            });
        });
    }

    private void showArtworkBitmap(Bitmap bitmap) {
        mArtwork.setImageTintList(null);
        mArtwork.setImageBitmap(bitmap);
    }

    private void openLibrary() {
        if (mSnapshot == null || mSnapshot.getItems().isEmpty()) {
            return;
        }
        mShowingLibrary = true;
        mLibraryDismissed = false;
        mGroupValue = null;
        renderLibrary();
    }

    private void closeLibraryLevel() {
        if (mGroupValue != null) {
            mGroupValue = null;
            renderLibrary();
            return;
        }
        mShowingLibrary = false;
        mLibraryDismissed = true;
        render(mSelectedSource, mSnapshot);
    }

    private void setLibraryFilter(LibraryFilter filter) {
        if (filter == LibraryFilter.VIDEOS && isVideoRestricted()) {
            showState(R.drawable.ic_video_file, R.string.status_unavailable,
                    R.string.video_restricted_title, R.string.video_restricted_message,
                    0, null);
            return;
        }
        mLibraryFilter = filter;
        mGroupValue = null;
        renderLibrary();
    }

    private void renderLibrary() {
        showOnly(mLibraryPanel);
        Button videos = mFilterButtons.get(LibraryFilter.VIDEOS);
        if (videos != null) {
            videos.setVisibility(isVideoRestricted() ? View.GONE : View.VISIBLE);
        }
        for (Map.Entry<LibraryFilter, Button> button : mFilterButtons.entrySet()) {
            boolean selected = button.getKey() == mLibraryFilter;
            button.getValue().setTextColor(getColor(selected
                    ? R.color.hypernova_cyan : R.color.hypernova_text_primary));
            button.getValue().setSelected(selected);
        }
        List<LibraryEntry> entries = buildLibraryEntries();
        mLibraryAdapter.setEntries(entries);
        mLibraryCount.setText(getString(R.string.items_found, entries.size()));
        revealSelectedLibraryFilter();
    }

    private void revealSelectedLibraryFilter() {
        Button selected = mFilterButtons.get(mLibraryFilter);
        if (selected == null) {
            return;
        }
        mLibraryFilters.post(() -> {
            int viewportLeft = mLibraryFilters.getScrollX();
            int viewportRight = viewportLeft + mLibraryFilters.getWidth();
            int selectedLeft = selected.getLeft();
            int selectedRight = selected.getRight();
            if (selectedLeft < viewportLeft) {
                mLibraryFilters.scrollTo(selectedLeft, 0);
            } else if (selectedRight > viewportRight) {
                mLibraryFilters.scrollTo(selectedRight - mLibraryFilters.getWidth(), 0);
            }
        });
    }

    private List<LibraryEntry> buildLibraryEntries() {
        if (mSnapshot == null) return Collections.emptyList();
        List<MediaItemModel> available = new ArrayList<>();
        for (MediaItemModel item : mSnapshot.getItems()) {
            if (!item.isVideo() || !isVideoRestricted()) {
                available.add(item);
            }
        }
        if (mGroupValue != null) {
            List<LibraryEntry> result = new ArrayList<>();
            for (MediaItemModel item : available) {
                if (mGroupValue.equals(groupValue(item, mLibraryFilter))) {
                    result.add(LibraryEntry.item(item));
                }
            }
            return result;
        }
        if (mLibraryFilter == LibraryFilter.TRACKS
                || mLibraryFilter == LibraryFilter.VIDEOS
                || mLibraryFilter == LibraryFilter.QUEUE) {
            List<LibraryEntry> result = new ArrayList<>();
            for (MediaItemModel item : available) {
                if (mLibraryFilter == LibraryFilter.TRACKS && item.isVideo()) continue;
                if (mLibraryFilter == LibraryFilter.VIDEOS && !item.isVideo()) continue;
                result.add(LibraryEntry.item(item));
            }
            return result;
        }
        TreeMap<String, Integer> groups = new TreeMap<>(String.CASE_INSENSITIVE_ORDER);
        for (MediaItemModel item : available) {
            String value = groupValue(item, mLibraryFilter);
            if (!TextUtils.isEmpty(value)) {
                groups.put(value, groups.getOrDefault(value, 0) + 1);
            }
        }
        List<LibraryEntry> result = new ArrayList<>();
        for (Map.Entry<String, Integer> group : groups.entrySet()) {
            result.add(LibraryEntry.group(group.getKey(), group.getValue()));
        }
        return result;
    }

    private static String groupValue(MediaItemModel item, LibraryFilter filter) {
        switch (filter) {
            case FOLDERS: return item.getFolder();
            case ARTISTS: return item.getArtist();
            case ALBUMS: return item.getAlbum();
            case GENRES: return item.getGenre();
            default: return null;
        }
    }

    private void onLibraryEntryClicked(LibraryEntry entry) {
        if (entry == null) return;
        if (entry.mGroupName != null) {
            mGroupValue = entry.mGroupName;
            renderLibrary();
            return;
        }
        if (entry.mItem != null) {
            if (entry.mItem.isVideo() && isVideoRestricted()) {
                showState(R.drawable.ic_video_file, R.string.status_unavailable,
                        R.string.video_restricted_title, R.string.video_restricted_message,
                        0, null);
                return;
            }
            mShowingLibrary = false;
            mCoordinator.playMediaId(entry.mItem.getId());
        }
    }

    private void showOnly(View panel) {
        mHomePanel.setVisibility(panel == mHomePanel ? View.VISIBLE : View.GONE);
        mStatePanel.setVisibility(panel == mStatePanel ? View.VISIBLE : View.GONE);
        mNowPlayingPanel.setVisibility(panel == mNowPlayingPanel ? View.VISIBLE : View.GONE);
        mLibraryPanel.setVisibility(panel == mLibraryPanel ? View.VISIBLE : View.GONE);
    }

    private void seekRelative(long deltaMs) {
        if (mSnapshot != null && mSnapshot.canSeek()) {
            mCoordinator.seekTo(mSnapshot.getCurrentPositionMs() + deltaMs);
        }
    }

    private void updatePosition() {
        if (mSnapshot == null || !mSnapshot.hasActiveItem()) return;
        MediaSnapshot latest = mCoordinator.getSnapshot();
        if (latest != null) mSnapshot = latest;
        long position = mSnapshot.getCurrentPositionMs();
        long duration = mSnapshot.getDurationMs();
        if (!mUserSeeking) {
            mElapsed.setText(duration > 0 ? formatTime(position) : getText(R.string.time_unknown));
            mDuration.setText(duration > 0 ? formatTime(duration) : getText(R.string.time_unknown));
            mSeekBar.setProgress(duration > 0
                    ? (int) Math.min(mSeekBar.getMax(), position * mSeekBar.getMax() / duration) : 0);
        }
    }

    private final Runnable mPositionUpdater = new Runnable() {
        @Override
        public void run() {
            if (mNowPlayingPanel.getVisibility() == View.VISIBLE) {
                updatePosition();
            }
            mRoot.postDelayed(this, 1_000);
        }
    };

    private void startPositionUpdates() {
        mRoot.removeCallbacks(mPositionUpdater);
        mRoot.post(mPositionUpdater);
    }

    private void stopPositionUpdates() {
        mRoot.removeCallbacks(mPositionUpdater);
    }

    private CharSequence formatTime(long timeMs) {
        long seconds = Math.max(0, timeMs / 1000);
        long minutes = seconds / 60;
        long remaining = seconds % 60;
        return String.format(Locale.getDefault(), "%d:%02d", minutes, remaining);
    }

    private CharSequence stateLabel(MediaSnapshot.State state) {
        switch (state) {
            case PLAYING: return getText(R.string.status_playing);
            case PAUSED: return getText(R.string.status_paused);
            case BUFFERING: return getText(R.string.buffering);
            case SEEKING: return getText(R.string.seeking);
            case FOCUS_INTERRUPTED: return getText(R.string.audio_focus);
            case LOADING: return getText(R.string.status_loading);
            case CONNECTING: return getText(R.string.status_connecting);
            case ERROR: return getText(R.string.status_error);
            default: return getText(R.string.status_ready);
        }
    }

    private void configureVideoSurfaces() {
        mVideoSurface.setSurfaceTextureListener(new VideoSurfaceListener(mVideoSurface));
        mFullscreenVideoSurface.setSurfaceTextureListener(
                new VideoSurfaceListener(mFullscreenVideoSurface));
    }

    private void attachVideoSurface(TextureView texture) {
        if (!texture.isAvailable() || mAttachedTexture == texture) return;
        detachVideoSurface();
        mAttachedSurface = new Surface(texture.getSurfaceTexture());
        mAttachedTexture = texture;
        mCoordinator.setVideoSurface(mAttachedSurface);
    }

    private void detachVideoSurface() {
        if (mAttachedSurface != null) {
            if (mCoordinator != null) mCoordinator.setVideoSurface(null);
            mAttachedSurface.release();
            mAttachedSurface = null;
            mAttachedTexture = null;
        }
    }

    private void configureVideoTransform(TextureView texture, MediaSnapshot snapshot) {
        if (texture.getWidth() == 0 || texture.getHeight() == 0
                || snapshot.getVideoWidth() <= 0 || snapshot.getVideoHeight() <= 0) {
            texture.setTransform(null);
            return;
        }
        float viewAspect = (float) texture.getWidth() / texture.getHeight();
        float videoAspect = (float) snapshot.getVideoWidth() / snapshot.getVideoHeight();
        Matrix transform = new Matrix();
        if (videoAspect > viewAspect) {
            float scaleY = viewAspect / videoAspect;
            transform.setScale(1f, scaleY, texture.getWidth() / 2f, texture.getHeight() / 2f);
        } else {
            float scaleX = videoAspect / viewAspect;
            transform.setScale(scaleX, 1f, texture.getWidth() / 2f, texture.getHeight() / 2f);
        }
        texture.setTransform(transform);
    }

    private void enterFullscreen() {
        if (mSnapshot == null || !mSnapshot.isVideo() || isVideoRestricted()) return;
        mFullscreen = true;
        detachVideoSurface();
        mHeader.setVisibility(View.GONE);
        mSourceSelector.setVisibility(View.GONE);
        mHeaderDivider.setVisibility(View.GONE);
        mContentContainer.setVisibility(View.GONE);
        mRoot.setPadding(0, 0, 0, 0);
        mFullscreenPanel.setVisibility(View.VISIBLE);
        if (mFullscreenVideoSurface.isAvailable()) {
            attachVideoSurface(mFullscreenVideoSurface);
            configureVideoTransform(mFullscreenVideoSurface, mSnapshot);
        }
    }

    private void exitFullscreen() {
        if (!mFullscreen) return;
        mFullscreen = false;
        detachVideoSurface();
        mFullscreenPanel.setVisibility(View.GONE);
        mRoot.setPadding(mRootPaddingLeft, mRootPaddingTop, mRootPaddingRight, mRootPaddingBottom);
        mHeader.setVisibility(View.VISIBLE);
        mSourceSelector.setVisibility(View.VISIBLE);
        mHeaderDivider.setVisibility(View.VISIBLE);
        mContentContainer.setVisibility(View.VISIBLE);
        if (mVideoSurface.isAvailable()) {
            attachVideoSurface(mVideoSurface);
            configureVideoTransform(mVideoSurface, mSnapshot);
        }
        configureSystemBars();
    }

    private final class VideoSurfaceListener implements TextureView.SurfaceTextureListener {
        private final TextureView mTexture;

        VideoSurfaceListener(TextureView texture) {
            mTexture = texture;
        }

        @Override
        public void onSurfaceTextureAvailable(SurfaceTexture surface, int width, int height) {
            if ((mFullscreen && mTexture == mFullscreenVideoSurface)
                    || (!mFullscreen && mTexture == mVideoSurface
                    && mTexture.getVisibility() == View.VISIBLE)) {
                attachVideoSurface(mTexture);
                if (mSnapshot != null) configureVideoTransform(mTexture, mSnapshot);
            }
        }

        @Override
        public void onSurfaceTextureSizeChanged(SurfaceTexture surface, int width, int height) {
            if (mSnapshot != null) configureVideoTransform(mTexture, mSnapshot);
        }

        @Override
        public boolean onSurfaceTextureDestroyed(SurfaceTexture surface) {
            if (mAttachedTexture == mTexture) detachVideoSurface();
            return true;
        }

        @Override
        public void onSurfaceTextureUpdated(SurfaceTexture surface) {}
    }

    @Override
    public void onVideoRestrictionChanged(boolean videoRestricted) {
        runOnUiThread(() -> {
            if (videoRestricted && mSnapshot != null && mSnapshot.isVideo()) {
                mCoordinator.pause();
                exitFullscreen();
            }
            render(mSelectedSource, mSnapshot);
        });
    }

    private boolean isVideoRestricted() {
        return mUxRestrictionClient != null && mUxRestrictionClient.isVideoRestricted();
    }

    private void requestMediaPermission() {
        List<String> permissions = new ArrayList<>();
        if (checkSelfPermission(Manifest.permission.READ_MEDIA_AUDIO)
                != PackageManager.PERMISSION_GRANTED) {
            permissions.add(Manifest.permission.READ_MEDIA_AUDIO);
        }
        if (checkSelfPermission(Manifest.permission.READ_MEDIA_VIDEO)
                != PackageManager.PERMISSION_GRANTED) {
            permissions.add(Manifest.permission.READ_MEDIA_VIDEO);
        }
        if (permissions.isEmpty()) {
            mCoordinator.onPermissionsChanged();
        } else {
            requestPermissions(permissions.toArray(new String[0]), REQUEST_MEDIA_PERMISSION);
        }
    }

    private void requestBluetoothPermission() {
        if (checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT)
                == PackageManager.PERMISSION_GRANTED) {
            mCoordinator.onPermissionsChanged();
        } else {
            requestPermissions(new String[] { Manifest.permission.BLUETOOTH_CONNECT },
                    REQUEST_BLUETOOTH_PERMISSION);
        }
    }

    private void openBluetoothSettings() {
        try {
            startActivity(new Intent(Settings.ACTION_BLUETOOTH_SETTINGS));
        } catch (RuntimeException ignored) {
        }
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions,
            int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode == REQUEST_MEDIA_PERMISSION
                || requestCode == REQUEST_BLUETOOTH_PERMISSION) {
            mCoordinator.onPermissionsChanged();
        }
    }

    private void configureSystemBars() {
        WindowInsetsController controller = getWindow().getInsetsController();
        if (controller != null) {
            controller.hide(WindowInsets.Type.systemBars());
            controller.setSystemBarsBehavior(
                    WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE);
        }
    }

    @Override
    public void onBackPressed() {
        if (mFullscreen) {
            exitFullscreen();
        } else if (mShowingLibrary) {
            closeLibraryLevel();
        } else {
            super.onBackPressed();
        }
    }

    private int sourceLabelRes(MediaSourceType source) {
        switch (source) {
            case RADIO: return R.string.radio;
            case BLUETOOTH: return R.string.bluetooth;
            case USB:
            default: return R.string.usb;
        }
    }

    private String sourceLabel(MediaSourceType source) {
        return getString(sourceLabelRes(source));
    }

    private int sourceIcon(MediaSourceType source) {
        if (source == null) return R.drawable.ic_music;
        switch (source) {
            case RADIO: return R.drawable.ic_radio;
            case BLUETOOTH: return R.drawable.ic_bluetooth;
            case USB:
            default: return R.drawable.ic_usb;
        }
    }

    private static void setEnabled(View view, boolean enabled) {
        view.setEnabled(enabled);
        view.setAlpha(enabled ? 1f : 0.35f);
    }

    private static final class ContentResolverScheme {
        static boolean isLocalContent(Uri uri) {
            return uri != null && ("content".equals(uri.getScheme())
                    || "file".equals(uri.getScheme()));
        }
    }

    private static final class LibraryEntry {
        final MediaItemModel mItem;
        final String mGroupName;
        final int mGroupCount;

        private LibraryEntry(MediaItemModel item, String groupName, int count) {
            mItem = item;
            mGroupName = groupName;
            mGroupCount = count;
        }

        static LibraryEntry item(MediaItemModel item) {
            return new LibraryEntry(item, null, 0);
        }

        static LibraryEntry group(String name, int count) {
            return new LibraryEntry(null, name, count);
        }
    }

    private final class LibraryAdapter extends BaseAdapter {
        private List<LibraryEntry> mEntries = Collections.emptyList();

        void setEntries(List<LibraryEntry> entries) {
            mEntries = entries;
            notifyDataSetChanged();
        }

        LibraryEntry getEntry(int position) {
            return position >= 0 && position < mEntries.size() ? mEntries.get(position) : null;
        }

        @Override
        public int getCount() {
            return mEntries.size();
        }

        @Override
        public LibraryEntry getItem(int position) {
            return mEntries.get(position);
        }

        @Override
        public long getItemId(int position) {
            return position;
        }

        @Override
        public View getView(int position, View convertView, ViewGroup parent) {
            View row = convertView;
            if (row == null) {
                row = LayoutInflater.from(MainActivity.this)
                        .inflate(R.layout.row_media_item, parent, false);
            }
            ImageView icon = row.findViewById(R.id.item_icon);
            TextView title = row.findViewById(R.id.item_title);
            TextView subtitle = row.findViewById(R.id.item_subtitle);
            TextView duration = row.findViewById(R.id.item_duration);
            LibraryEntry entry = getItem(position);
            if (entry.mGroupName != null) {
                icon.setImageResource(R.drawable.ic_folder);
                title.setText(entry.mGroupName);
                subtitle.setText(getString(R.string.items_found, entry.mGroupCount));
                duration.setText(null);
            } else {
                MediaItemModel item = entry.mItem;
                icon.setImageResource(item.isVideo()
                        ? R.drawable.ic_video_file : R.drawable.ic_audio_file);
                title.setText(TextUtils.isEmpty(item.getTitle())
                        ? getText(R.string.metadata_unavailable) : item.getTitle());
                CharSequence detail = !TextUtils.isEmpty(item.getArtist()) ? item.getArtist()
                        : !TextUtils.isEmpty(item.getAlbum()) ? item.getAlbum()
                        : getText(item.isVideo() ? R.string.video_item : R.string.audio_item);
                subtitle.setText(detail);
                duration.setText(item.getDurationMs() > 0
                        ? formatTime(item.getDurationMs()) : null);
            }
            return row;
        }
    }
}
