package com.hypernova.media.ui;

import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;

import android.content.Context;
import android.content.res.ColorStateList;
import android.graphics.Color;
import android.net.Uri;
import android.view.View;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.ImageButton;
import android.widget.ImageView;
import android.widget.SeekBar;
import android.widget.TextView;

import androidx.core.content.ContextCompat;
import androidx.media3.common.Player;
import androidx.media3.ui.PlayerView;

import com.google.android.material.button.MaterialButton;
import com.hypernova.media.R;
import com.hypernova.media.model.BluetoothUiState;
import com.hypernova.media.model.LibraryUiState;
import com.hypernova.media.model.MediaItemModel;
import com.hypernova.media.model.MediaSourceType;
import com.hypernova.media.model.PlaybackUiState;
import com.hypernova.media.model.RadioUiState;
import com.hypernova.media.radio.InternetRadioBackend;
import com.hypernova.media.radio.RadioStation;
import com.hypernova.media.visualizer.HyperNovaImmersiveVisualizerView;
import com.hypernova.media.visualizer.VisualizerMode;
import com.hypernova.media.visualizer.VisualizerState;

import java.util.Collections;
import java.util.Locale;

/** Pure view renderer: it does not query Android services or issue playback commands. */
public final class MainUiRenderer {
    public enum StateAction {
        NONE, ADD_STATION, BLUETOOTH_PERMISSION, BLUETOOTH_SETTINGS,
        SELECT_FOLDER, USB_VOLUME_PICKER, MEDIA_PERMISSION, RETRY
    }

    private final Context context;
    private final View radioCard;
    private final View bluetoothCard;
    private final View libraryCard;
    private final TextView radioTitle;
    private final TextView bluetoothTitle;
    private final TextView libraryTitle;
    private final TextView radioSubtitle;
    private final TextView bluetoothSubtitle;
    private final TextView librarySubtitle;
    private final ImageView radioIcon;
    private final ImageView bluetoothIcon;
    private final ImageView libraryIcon;
    private final TextView headerState;
    private final View statusDot;
    private final HyperNovaImmersiveVisualizerView visualizer;
    private final PlayerView playerView;
    private final ImageButton fullscreen;
    private final TextView demoBadge;
    private final View heroCopy;
    private final TextView heroEyebrow;
    private final TextView heroTitle;
    private final TextView heroSubtitle;
    private final View statePanel;
    private final ImageView stateIcon;
    private final TextView stateEyebrow;
    private final TextView stateTitle;
    private final TextView stateMessage;
    private final MaterialButton stateAction;
    private final View radioPanel;
    private final View nowPlayingPanel;
    private final View libraryPanel;
    private final View videoPanel;
    private final TextView mediaBadge;
    private final TextView mediaTitle;
    private final TextView mediaSubtitle;
    private final ImageButton favorite;
    private final SeekBar seekBar;
    private final TextView elapsed;
    private final TextView duration;
    private final ImageButton playPause;
    private final ImageButton rewind;
    private final ImageButton forward;
    private final MaterialButton shuffle;
    private final MaterialButton repeat;
    private final MaterialButton more;
    private final TextView libraryCount;
    private boolean userSeeking;
    private boolean renderedPlaying;
    private boolean hasRenderedPlayback;
    private static final long BLUETOOTH_PROGRESS_FRAME_MS = 200L;

    private final Handler bluetoothProgressHandler =
            new Handler(Looper.getMainLooper());

    private boolean bluetoothProgressTickerRunning;
    private boolean youtubePlaybackKnown;
    private boolean youtubePlaying;
    private boolean bluetoothProgressPlaying;
    private boolean bluetoothLastReportedPlaying;

    private long bluetoothProgressBasePositionMs;
    private long bluetoothProgressBaseRealtimeMs;
    private long bluetoothProgressDurationMs;

    private long bluetoothLastReportedPositionMs = Long.MIN_VALUE;
    private long bluetoothLastReportedDurationMs = Long.MIN_VALUE;

    private String bluetoothLastTrackKey = "";

    private final Runnable bluetoothProgressTicker =
            new Runnable() {
                @Override
                public void run() {
                    if (!bluetoothProgressTickerRunning) {
                        return;
                    }

                    renderBluetoothProgressFrame();

                    bluetoothProgressHandler.postDelayed(
                            this,
                            BLUETOOTH_PROGRESS_FRAME_MS);
                }
            };

    private VisualizerMode currentVisualizerMode = VisualizerMode.IDLE;

    public MainUiRenderer(View root) {
        context = root.getContext();
        radioCard = root.findViewById(R.id.card_radio);
        bluetoothCard = root.findViewById(R.id.card_bluetooth);
        libraryCard = root.findViewById(R.id.card_library);
        radioTitle = root.findViewById(R.id.source_title_radio);
        bluetoothTitle = root.findViewById(R.id.source_title_bluetooth);
        libraryTitle = root.findViewById(R.id.source_title_library);
        radioSubtitle = root.findViewById(R.id.source_subtitle_radio);
        bluetoothSubtitle = root.findViewById(R.id.source_subtitle_bluetooth);
        librarySubtitle = root.findViewById(R.id.source_subtitle_library);
        radioIcon = root.findViewById(R.id.source_icon_radio);
        bluetoothIcon = root.findViewById(R.id.source_icon_bluetooth);
        libraryIcon = root.findViewById(R.id.source_icon_library);
        headerState = root.findViewById(R.id.header_state);
        statusDot = root.findViewById(R.id.status_dot);
        visualizer = root.findViewById(R.id.visualizer);
        playerView = root.findViewById(R.id.player_view);
        fullscreen = root.findViewById(R.id.button_fullscreen);
        demoBadge = root.findViewById(R.id.demo_badge);
        heroCopy = root.findViewById(R.id.hero_copy);
        heroEyebrow = root.findViewById(R.id.hero_eyebrow);
        heroTitle = root.findViewById(R.id.hero_title);
        heroSubtitle = root.findViewById(R.id.hero_subtitle);
        statePanel = root.findViewById(R.id.state_panel);
        stateIcon = root.findViewById(R.id.state_icon);
        stateEyebrow = root.findViewById(R.id.state_eyebrow);
        stateTitle = root.findViewById(R.id.state_title);
        stateMessage = root.findViewById(R.id.state_message);
        stateAction = root.findViewById(R.id.state_action);
        radioPanel = root.findViewById(R.id.radio_panel);
        nowPlayingPanel = root.findViewById(R.id.now_playing_panel);
        libraryPanel = root.findViewById(R.id.library_panel);
        videoPanel = root.findViewById(R.id.video_panel);
        mediaBadge = root.findViewById(R.id.media_badge);
        mediaTitle = root.findViewById(R.id.media_title);
        mediaSubtitle = root.findViewById(R.id.media_subtitle);
        favorite = root.findViewById(R.id.button_favorite);
        seekBar = root.findViewById(R.id.seek_bar);
        elapsed = root.findViewById(R.id.time_elapsed);
        duration = root.findViewById(R.id.time_duration);
        playPause = root.findViewById(R.id.button_play_pause);
        rewind = root.findViewById(R.id.button_rewind);
        forward = root.findViewById(R.id.button_forward);
        shuffle = root.findViewById(R.id.button_shuffle);
        repeat = root.findViewById(R.id.button_repeat);
        more = root.findViewById(R.id.button_more);
        libraryCount = root.findViewById(R.id.library_count);
        root.addOnAttachStateChangeListener(
                new View.OnAttachStateChangeListener() {
                    @Override
                    public void onViewAttachedToWindow(View view) {
                        // The next Bluetooth render restarts the ticker.
                    }

                    @Override
                    public void onViewDetachedFromWindow(View view) {
                        stopBluetoothProgressTicker();
                    }
                });
    }

    public void setUserSeeking(boolean value) { userSeeking = value; }
    /** Real browser media state, only when the WebView reports a Media Session/video element. */
    public void setYoutubePlaybackState(boolean known, boolean playing) {
        youtubePlaybackKnown = known;
        youtubePlaying = playing;
    }
    public PlayerView playerView() { return playerView; }
    public HyperNovaImmersiveVisualizerView visualizer() { return visualizer; }
    public StateAction stateAction() {
        Object tag = stateAction.getTag();
        return tag instanceof StateAction ? (StateAction) tag : StateAction.NONE;
    }

    public void render(MediaSourceType source, PlaybackUiState playback,
            BluetoothUiState bluetooth, LibraryUiState library,
            RadioUiState radio, InternetRadioBackend radioBackend, boolean demo) {
        demoBadge.setVisibility(demo ? View.VISIBLE : View.GONE);
        if (source != MediaSourceType.BLUETOOTH
                || bluetooth == null
                || !bluetooth.isConnected()
                || !bluetooth.hasRemoteMedia()) {
            stopBluetoothProgressTicker();
        }
        selectCard(source);
        updateSourceSubtitles(bluetooth, library);
        radioPanel.setVisibility(source == MediaSourceType.RADIO ? View.VISIBLE : View.GONE);
        libraryPanel.setVisibility(source == MediaSourceType.LIBRARY ? View.VISIBLE : View.GONE);
        videoPanel.setVisibility(source == MediaSourceType.VIDEO ? View.VISIBLE : View.GONE);
        boolean radioControls =
                source == MediaSourceType.RADIO;

        boolean bluetoothControls =
                source == MediaSourceType.BLUETOOTH;

        favorite.setVisibility(
                radioControls ? View.VISIBLE : View.GONE);

        /*
         * Android's BluetoothMediaBrowserService exposes AVRCP transport
         * controls, but the current phone session does not advertise
         * ACTION_SEEK_TO, ACTION_REWIND or ACTION_FAST_FORWARD.
         *
         * Keep Bluetooth progress visible, but do not present controls that
         * the remote session cannot execute.
         */
        rewind.setVisibility(
                radioControls || bluetoothControls
                        ? View.GONE
                        : View.VISIBLE);

        forward.setVisibility(
                radioControls || bluetoothControls
                        ? View.GONE
                        : View.VISIBLE);

        shuffle.setVisibility(
                bluetoothControls ? View.GONE : View.VISIBLE);

        repeat.setVisibility(
                bluetoothControls ? View.GONE : View.VISIBLE);

        more.setVisibility(
                bluetoothControls ? View.GONE : View.VISIBLE);

        shuffle.setText(
                radioControls
                        ? "Stop"
                        : context.getString(R.string.shuffle));

        repeat.setText(
                radioControls
                        ? "Retry"
                        : context.getString(R.string.repeat));

        more.setText(
                radioControls
                        ? "Manage"
                        : context.getString(R.string.queue));

        boolean relevantMedia =
                playback.hasMedia()
                        && isPlaybackRelevant(source, playback.item)
                        && source != MediaSourceType.BLUETOOTH;

        boolean bluetoothRemoteMedia =
                source == MediaSourceType.BLUETOOTH
                        && bluetooth.isConnected()
                        && bluetooth.hasRemoteMedia();

        int dotColor =
                playback.error != null
                        ? R.color.hn_warning
                        : playback.playing
                                || bluetooth.remotePlaying
                                || bluetooth.isConnected()
                        ? R.color.hn_success
                        : R.color.hn_cyan;

        statusDot.setBackgroundTintList(
                ColorStateList.valueOf(
                        ContextCompat.getColor(context, dotColor)));

        nowPlayingPanel.setVisibility(
                relevantMedia || bluetoothRemoteMedia
                        ? View.VISIBLE
                        : View.GONE);

        if (bluetoothRemoteMedia) {
            renderBluetoothPlayback(bluetooth);
        } else {
            renderPlayback(playback, source, relevantMedia);
        }

        if (source == MediaSourceType.HOME) renderHome(bluetooth, library);
        else if (source == MediaSourceType.RADIO) renderRadio(playback, radio, radioBackend);
        else if (source == MediaSourceType.BLUETOOTH) renderBluetooth(playback, bluetooth);
        else if (source == MediaSourceType.VIDEO) renderVideo();
        else renderLibrary(playback, library);
        float progress;

        if (source == MediaSourceType.BLUETOOTH
                && bluetooth.remoteDurationMs > 0L) {
            progress =
                    Math.min(
                            1f,
                            bluetooth.remotePositionMs
                                    / (float) bluetooth.remoteDurationMs);
        } else {
            progress =
                    playback.durationMs > 0L
                            ? Math.min(
                                    1f,
                                    playback.positionMs
                                            / (float) playback.durationMs)
                            : 0f;
        }

        visualizer.setVisualizerState(
                new VisualizerState(
                        currentVisualizerMode,
                        progress,
                        true));
    }

    private boolean isPlaybackRelevant(MediaSourceType source, MediaItemModel item) {
        if (source == MediaSourceType.RADIO) return item.getId().startsWith("radio:");
        if (source == MediaSourceType.BLUETOOTH) return true;
        return source == MediaSourceType.LIBRARY && !item.getId().startsWith("radio:");
    }

    private void renderHome(BluetoothUiState bluetooth, LibraryUiState library) {
        headerState.setText("SELECT SOURCE");
        setHero("IMMERSIVE MEDIA", "Your media. One cockpit.",
                "Internet Radio, Bluetooth output, and video in one focused experience.",
                VisualizerMode.IDLE, false);
        showState(R.drawable.ic_music, "SYSTEM READY", "Choose a source",
                homeSummary(bluetooth), null, StateAction.NONE);
    }

    private String homeSummary(BluetoothUiState bluetooth) {
        String bluetoothText = bluetooth.isConnected() ? bluetooth.activeDeviceName : "No active Bluetooth output";
        return bluetoothText + " · YouTube ready";
    }

    private void renderVideo() {
        headerState.setText("YOUTUBE · " + (youtubePlaybackKnown
                ? youtubePlaying ? "PLAYING" : "PAUSED" : "READY"));
        setHero("YOUTUBE", "YouTube", "", VisualizerMode.IDLE, true);
        statePanel.setVisibility(View.GONE);
        nowPlayingPanel.setVisibility(View.GONE);
        playerView.setVisibility(View.GONE);
        fullscreen.setVisibility(View.GONE);
    }

    private void renderRadio(PlaybackUiState playback, RadioUiState catalog,
            InternetRadioBackend backend) {
        RadioStation selected = backend.getSelected();
        boolean radioMedia = playback.item != null && playback.item.getId().startsWith("radio:");
        if (catalog.status == RadioUiState.Status.LOADING && catalog.stations.isEmpty()) {
            headerState.setText("RADIO · DISCOVERING");
            setHero("INTERNET RADIO", "Discovering live stations",
                    "Searching healthy Radio Browser streams across available mirrors.",
                    VisualizerMode.RADIO_BUFFERING, false);
            showState(R.drawable.ic_radio, "CATALOG", "Loading Internet Radio",
                    catalog.message, null, StateAction.NONE);
        } else if ((catalog.status == RadioUiState.Status.ERROR
                || catalog.status == RadioUiState.Status.EMPTY) && catalog.stations.isEmpty()) {
            boolean error = catalog.status == RadioUiState.Status.ERROR;
            headerState.setText("RADIO · " + (error ? "UNAVAILABLE" : "EMPTY"));
            setHero("INTERNET RADIO", error ? "Catalog unavailable" : "No matching stations",
                    catalog.message, error ? VisualizerMode.ERROR : VisualizerMode.IDLE, false);
            showState(R.drawable.ic_radio, error ? "API UNAVAILABLE" : "NO RESULTS",
                    error ? "Radio Browser could not be reached" : "Try another search or filter",
                    catalog.message, error ? "Retry" : "Add station",
                    error ? StateAction.RETRY : StateAction.ADD_STATION);
        } else if (selected != null && (backend.getError() != null || playback.error != null)) {
            String streamError = backend.getError() != null ? backend.getError() : playback.error;
            headerState.setText("RADIO · ERROR");
            setHero("STREAM ERROR", selected.name, streamError,
                    VisualizerMode.ERROR, false);
            showState(R.drawable.ic_radio, "STREAM UNAVAILABLE", "Unable to play this station",
                    streamError + " Retry, choose Next, or long-press the row to hide it.",
                    "Retry", StateAction.RETRY);
        } else if (selected == null || !radioMedia) {
            String state = catalog.status == RadioUiState.Status.OFFLINE
                    || catalog.status == RadioUiState.Status.CACHED ? "OFFLINE" : "READY";
            headerState.setText("RADIO · " + state);
            setHero("INTERNET RADIO", "Choose a live station", catalog.stations.size()
                    + " catalog station" + (catalog.stations.size() == 1 ? "" : "s"),
                    VisualizerMode.IDLE, false);
            showState(R.drawable.ic_radio, state, "Internet Radio discovery",
                    catalog.message,
                    null, StateAction.NONE);
        } else {
            String status = playback.isBuffering() ? "BUFFERING" : playback.playing ? "PLAYING" : "PAUSED";
            headerState.setText("RADIO · " + status);
            setHero("INTERNET RADIO · " + status, selected.name,
                    selected.locationLine() + " · " + selected.technicalLine(),
                    playback.isBuffering() ? VisualizerMode.RADIO_BUFFERING
                            : playback.playing ? VisualizerMode.RADIO_PLAYING : VisualizerMode.PAUSED,
                    false);
            statePanel.setVisibility(View.GONE);
        }
        if (selected != null) {
            favorite.setActivated(selected.favorite);
            favorite.setImageTintList(ColorStateList.valueOf(ContextCompat.getColor(context,
                    selected.favorite ? R.color.hn_cyan : R.color.hn_text_primary)));
        }
    }

    private void renderBluetooth(PlaybackUiState playback, BluetoothUiState bluetooth) {
        switch (bluetooth.status) {
            case PERMISSION_REQUIRED:
                headerState.setText("BLUETOOTH · ACCESS");
                setHero("BLUETOOTH OUTPUT", "Nearby-device access required",
                        "HyperNova only reads paired and connected audio-output state.",
                        VisualizerMode.IDLE, false);
                showState(R.drawable.ic_bluetooth, "PERMISSION REQUIRED", "Show real audio devices",
                        bluetooth.detail, "Allow", StateAction.BLUETOOTH_PERMISSION);
                break;
            case OFF:
                headerState.setText("BLUETOOTH · OFF");
                setHero("BLUETOOTH OUTPUT", "Bluetooth is off", "Turn it on in Android settings.",
                        VisualizerMode.PAUSED, false);
                showState(R.drawable.ic_bluetooth, "BLUETOOTH OFF", "No wireless output",
                        bluetooth.detail, "Open settings", StateAction.BLUETOOTH_SETTINGS);
                break;
            case CONNECTED:
                boolean hasRemoteMetadata =
                        bluetooth.hasRemoteMedia();

                headerState.setText(
                        "BLUETOOTH · "
                                + (bluetooth.remotePlaying
                                ? "PLAYING"
                                : hasRemoteMetadata
                                ? "PAUSED"
                                : "CONNECTED"));

                setHero(
                        bluetooth.remotePlaying
                                ? "BLUETOOTH MEDIA · PLAYING"
                                : hasRemoteMetadata
                                ? "BLUETOOTH MEDIA · PAUSED"
                                : "BLUETOOTH MEDIA · CONNECTED",
                        hasRemoteMetadata
                                ? bluetooth.trackTitle
                                : bluetooth.activeDeviceName,
                        hasRemoteMetadata
                                ? bluetooth.remoteSecondaryText()
                                : bluetooth.detail,
                        bluetooth.remotePlaying
                                ? VisualizerMode.BLUETOOTH_PLAYING
                                : VisualizerMode.BLUETOOTH_CONNECTED,
                        false);

                if (hasRemoteMetadata) {
                    statePanel.setVisibility(View.GONE);
                } else {
                    showState(
                            R.drawable.ic_bluetooth,
                            "CONNECTED PHONE",
                            bluetooth.activeDeviceName,
                            "Ready to receive media from your phone.",
                            "Bluetooth settings",
                            StateAction.BLUETOOTH_SETTINGS);
                }
                break;
            case UNSUPPORTED:
                headerState.setText("BLUETOOTH · UNSUPPORTED");
                setHero("BLUETOOTH OUTPUT", "Bluetooth unavailable", bluetooth.detail,
                        VisualizerMode.ERROR, false);
                showState(R.drawable.ic_bluetooth, "UNAVAILABLE", "No Bluetooth hardware",
                        bluetooth.detail, null, StateAction.NONE);
                break;
            default:
                headerState.setText("BLUETOOTH · READY");
                String title = bluetooth.status == BluetoothUiState.Status.ON_NO_PAIRED_AUDIO
                        ? "No paired audio devices" : "No active Bluetooth output";
                setHero("BLUETOOTH OUTPUT", title, bluetooth.detail,
                        VisualizerMode.BLUETOOTH_CONNECTING, false);
                showState(R.drawable.ic_bluetooth, "REAL DEVICE STATE", title,
                        bluetooth.detail, "Open settings", StateAction.BLUETOOTH_SETTINGS);
                break;
        }
    }

    private void renderLibrary(PlaybackUiState playback, LibraryUiState library) {
        libraryCount.setText((library.audioCount + " TRACKS · " + library.videoCount
                + " VIDEOS · " + library.sourceLabel).toUpperCase(Locale.ROOT));
        boolean video = playback.item != null && playback.item.isVideo();
        if (playback.hasMedia() && !playback.item.getId().startsWith("radio:")) {
            if (playback.error != null) {
                headerState.setText("USB · PLAYBACK ERROR");
                setHero("USB MEDIA ERROR", playback.item.getTitle(),
                        "This item could not be decoded or its storage is unavailable.",
                        VisualizerMode.ERROR, false);
                showState(R.drawable.ic_library, "PLAYBACK ERROR", playback.item.getTitle(),
                        playback.error + " · Reconnect storage or choose another item.",
                        "Retry", StateAction.RETRY);
                return;
            }
            headerState.setText((video ? "USB VIDEO" : "USB") + " · "
                    + (playback.playing ? "PLAYING" : playback.isBuffering() ? "BUFFERING" : "PAUSED"));
            setHero(video ? "USB VIDEO PLAYBACK" : "USB AUDIO", playback.item.getTitle(),
                    playback.item.secondaryText(), video ? VisualizerMode.LIBRARY_VIDEO_PLAYING
                            : playback.playing ? VisualizerMode.LIBRARY_AUDIO_PLAYING : VisualizerMode.PAUSED,
                    video);
            statePanel.setVisibility(View.GONE);
            return;
        }
        switch (library.status) {
            case SCANNING:
                headerState.setText("USB · SCANNING");
                setHero("USB MEDIA", "Scanning storage", library.message,
                        VisualizerMode.USB_SCANNING, false);
                showState(R.drawable.ic_library, "SCANNING", library.sourceLabel, library.message,
                        null, StateAction.NONE);
                break;
            case READY:
                headerState.setText((library.removable ? "USB" : "LOCAL FOLDER") + " · READY");
                setHero(library.removable ? "USB MEDIA" : "LOCAL FOLDER", library.sourceLabel,
                        library.audioCount + " tracks · " + library.videoCount + " videos",
                        VisualizerMode.USB_INSERTED, false);
                showState(R.drawable.ic_library, library.removable ? "USB READY" : "LOCAL FOLDER",
                        library.sourceLabel, library.message,
                        null, StateAction.NONE);
                break;
            case PERMISSION_REQUIRED:
                headerState.setText("USB · ACCESS");
                setHero("USB MEDIA", "Permission required", library.message,
                        VisualizerMode.USB_INSERTED, false);
                showState(R.drawable.ic_library, "PERMISSION REQUIRED", library.sourceLabel,
                        library.message, library.removable ? "Allow" : "Reconnect",
                        library.removable ? StateAction.MEDIA_PERMISSION : StateAction.SELECT_FOLDER);
                break;
            case MULTIPLE_VOLUMES:
                headerState.setText("USB · CHOOSE VOLUME");
                setHero("USB MEDIA", "Multiple drives mounted", library.message,
                        VisualizerMode.USB_INSERTED, false);
                showState(R.drawable.ic_library, "MULTIPLE USB", "Choose a volume",
                        library.message, "Choose", StateAction.USB_VOLUME_PICKER);
                break;
            case REMOVED:
                headerState.setText("USB · REMOVED");
                setHero("USB MEDIA", "USB removed", library.message,
                        VisualizerMode.USB_REMOVED, false);
                showState(R.drawable.ic_library, "DISCONNECTED", library.sourceLabel,
                        library.message, null, StateAction.NONE);
                break;
            case UNSUPPORTED:
                headerState.setText("USB · UNSUPPORTED");
                setHero("USB MEDIA", library.sourceLabel, library.message,
                        VisualizerMode.USB_INSERTED, false);
                showState(R.drawable.ic_library, "UNSUPPORTED FILES", library.sourceLabel,
                        library.message, "Select folder", StateAction.SELECT_FOLDER);
                break;
            case ERROR:
                headerState.setText("USB · ERROR");
                setHero("USB MEDIA", "Storage unavailable", library.message,
                        VisualizerMode.ERROR, false);
                showState(R.drawable.ic_library, "STORAGE ERROR", library.sourceLabel,
                        library.message, "Select folder", StateAction.SELECT_FOLDER);
                break;
            case EMPTY:
                headerState.setText("USB · EMPTY");
                setHero("USB MEDIA", library.sourceLabel, library.message,
                        VisualizerMode.USB_INSERTED, false);
                showState(R.drawable.ic_library, "EMPTY", "No supported media found",
                        library.message, "Select folder", StateAction.SELECT_FOLDER);
                break;
            case DETECTED:
                headerState.setText("USB · DETECTED");
                setHero("USB MEDIA", library.sourceLabel, library.message,
                        VisualizerMode.USB_INSERTED, false);
                showState(R.drawable.ic_library, "USB DETECTED", library.sourceLabel,
                        library.message, null, StateAction.NONE);
                break;
            default:
                headerState.setText("USB · NO DEVICE");
                setHero("USB MEDIA", "No USB detected",
                        "Connect a mounted USB/OTG drive or select its folder through Android.",
                        VisualizerMode.USB_NO_DEVICE, false);
                showState(R.drawable.ic_library, "NO USB", "Connect USB OTG drive",
                        library.message, "Select USB folder", StateAction.SELECT_FOLDER);
                break;
        }
    }

    private void renderBluetoothPlayback(
            BluetoothUiState bluetooth) {
        mediaBadge.setText("BLUETOOTH");
        mediaTitle.setText(bluetooth.trackTitle);
        mediaSubtitle.setText(bluetooth.remoteSecondaryText());

        if (!hasRenderedPlayback
                || renderedPlaying != bluetooth.remotePlaying) {
            renderedPlaying = bluetooth.remotePlaying;
            hasRenderedPlayback = true;

            playPause.animate().cancel();

            playPause.animate()
                    .alpha(0f)
                    .setDuration(70L)
                    .withEndAction(() -> {
                        playPause.setImageResource(
                                renderedPlaying
                                        ? R.drawable.ic_pause
                                        : R.drawable.ic_play);

                        playPause.animate()
                                .alpha(1f)
                                .setDuration(110L)
                                .start();
                    })
                    .start();
        }

        syncBluetoothProgress(bluetooth);

        /*
         * The AVRCP session exposes position and duration, but the connected
         * phone does not expose remote seek support. The bar remains a smooth
         * read-only playback indicator.
         */
        seekBar.setEnabled(false);

        playerView.setVisibility(View.GONE);
        fullscreen.setVisibility(View.GONE);
        heroCopy.setVisibility(View.VISIBLE);
    }

    private void syncBluetoothProgress(
            BluetoothUiState bluetooth) {
        long now = SystemClock.elapsedRealtime();

        String trackKey =
                bluetooth.trackTitle
                        + "\u0000"
                        + bluetooth.artist
                        + "\u0000"
                        + bluetooth.album;

        boolean snapshotChanged =
                bluetooth.remotePositionMs
                                != bluetoothLastReportedPositionMs
                        || bluetooth.remoteDurationMs
                                != bluetoothLastReportedDurationMs
                        || bluetooth.remotePlaying
                                != bluetoothLastReportedPlaying
                        || !trackKey.equals(bluetoothLastTrackKey);

        /*
         * Do not reset the local clock during unrelated UI renders.
         * Reset only when a new real AVRCP snapshot arrives.
         */
        if (snapshotChanged) {
            bluetoothProgressBasePositionMs =
                    Math.max(0L, bluetooth.remotePositionMs);

            bluetoothProgressBaseRealtimeMs = now;

            bluetoothProgressDurationMs =
                    Math.max(0L, bluetooth.remoteDurationMs);

            bluetoothProgressPlaying =
                    bluetooth.remotePlaying;

            bluetoothLastReportedPositionMs =
                    bluetooth.remotePositionMs;

            bluetoothLastReportedDurationMs =
                    bluetooth.remoteDurationMs;

            bluetoothLastReportedPlaying =
                    bluetooth.remotePlaying;

            bluetoothLastTrackKey = trackKey;
        }

        renderBluetoothProgressFrame();

        if (bluetoothProgressPlaying
                && seekBar.isAttachedToWindow()) {
            startBluetoothProgressTicker();
        } else {
            stopBluetoothProgressTicker();
        }
    }

    private long currentBluetoothPositionMs() {
        long position =
                bluetoothProgressBasePositionMs;

        if (bluetoothProgressPlaying) {
            long elapsedRealtime =
                    Math.max(
                            0L,
                            SystemClock.elapsedRealtime()
                                    - bluetoothProgressBaseRealtimeMs);

            position += elapsedRealtime;
        }

        if (bluetoothProgressDurationMs > 0L) {
            position =
                    Math.min(
                            position,
                            bluetoothProgressDurationMs);
        }

        return Math.max(0L, position);
    }

    private void renderBluetoothProgressFrame() {
        long position =
                currentBluetoothPositionMs();

        if (!userSeeking) {
            int progress =
                    bluetoothProgressDurationMs > 0L
                            ? (int) Math.min(
                                    1000L,
                                    position
                                            * 1000L
                                            / bluetoothProgressDurationMs)
                            : 0;

            seekBar.setProgress(progress);
        }

        elapsed.setText(
                formatTime(position));

        duration.setText(
                bluetoothProgressDurationMs > 0L
                        ? formatTime(bluetoothProgressDurationMs)
                        : "LIVE");
    }

    private void startBluetoothProgressTicker() {
        if (bluetoothProgressTickerRunning) {
            return;
        }

        bluetoothProgressTickerRunning = true;

        bluetoothProgressHandler.removeCallbacks(
                bluetoothProgressTicker);

        bluetoothProgressHandler.postDelayed(
                bluetoothProgressTicker,
                BLUETOOTH_PROGRESS_FRAME_MS);
    }

    private void stopBluetoothProgressTicker() {
        bluetoothProgressTickerRunning = false;

        bluetoothProgressHandler.removeCallbacks(
                bluetoothProgressTicker);
    }

    private void renderPlayback(PlaybackUiState playback, MediaSourceType source, boolean relevant) {
        if (!relevant || playback.item == null) {
            playerView.setVisibility(View.GONE);
            fullscreen.setVisibility(View.GONE);
            return;
        }
        MediaItemModel item = playback.item;
        mediaBadge.setText(source == MediaSourceType.RADIO ? "RADIO"
                : source == MediaSourceType.BLUETOOTH ? "BLUETOOTH" : item.isVideo() ? "VIDEO" : "AUDIO");
        mediaTitle.setText(item.getTitle());
        mediaSubtitle.setText(item.secondaryText());
        if (!hasRenderedPlayback || renderedPlaying != playback.playing) {
            renderedPlaying = playback.playing;
            hasRenderedPlayback = true;
            playPause.animate().cancel();
            playPause.animate().alpha(0f).setDuration(70L).withEndAction(() -> {
                playPause.setImageResource(renderedPlaying ? R.drawable.ic_pause : R.drawable.ic_play);
                playPause.animate().alpha(1f).setDuration(110L).start();
            }).start();
        }
        if (!userSeeking) {
            int progress = playback.durationMs > 0
                    ? (int) Math.min(1000L, playback.positionMs * 1000L / playback.durationMs) : 0;
            seekBar.setProgress(progress);
        }
        seekBar.setEnabled(playback.durationMs > 0L);
        elapsed.setText(formatTime(playback.positionMs));
        duration.setText(playback.durationMs > 0L ? formatTime(playback.durationMs) : "LIVE");
        playerView.setVisibility(item.isVideo() ? View.VISIBLE : View.GONE);
        fullscreen.setVisibility(item.isVideo() ? View.VISIBLE : View.GONE);
        heroCopy.setVisibility(item.isVideo() ? View.GONE : View.VISIBLE);
    }

    private void setHero(String eyebrow, String title, String subtitle, VisualizerMode mode,
            boolean video) {
        heroEyebrow.setText(eyebrow);
        heroTitle.setText(title);
        heroSubtitle.setText(subtitle);
        currentVisualizerMode = mode;
        heroCopy.setVisibility(video ? View.GONE : View.VISIBLE);
        visualizer.setVisibility(video ? View.INVISIBLE : View.VISIBLE);
        visualizer.setVisualizerState(new VisualizerState(mode, 0f, true));
        if (!video) {
            playerView.setVisibility(View.GONE);
            fullscreen.setVisibility(View.GONE);
        }
    }

    private void showState(int icon, String eyebrow, String title, String message,
            String actionLabel, StateAction action) {
        statePanel.setVisibility(View.VISIBLE);
        stateIcon.setImageResource(icon);
        stateEyebrow.setText(eyebrow);
        stateTitle.setText(title);
        stateMessage.setText(message);
        stateAction.setTag(action);
        if (actionLabel == null) stateAction.setVisibility(View.GONE);
        else { stateAction.setText(actionLabel); stateAction.setVisibility(View.VISIBLE); }
    }

    private void updateSourceSubtitles(BluetoothUiState bluetooth, LibraryUiState library) {
        radioSubtitle.setText("Internet streams");
        bluetoothSubtitle.setText(bluetooth.isConnected() ? bluetooth.activeDeviceName
                : bluetooth.status == BluetoothUiState.Status.OFF ? "Off"
                : bluetooth.status == BluetoothUiState.Status.PERMISSION_REQUIRED ? "Access needed"
                : "No active output");
        librarySubtitle.setText("YouTube");
    }

    private void selectCard(MediaSourceType source) {
        updateCard(radioCard, radioTitle, radioIcon, source == MediaSourceType.RADIO);
        updateCard(bluetoothCard, bluetoothTitle, bluetoothIcon, source == MediaSourceType.BLUETOOTH);
        updateCard(libraryCard, libraryTitle, libraryIcon, source == MediaSourceType.VIDEO);
    }

    private void updateCard(View card, TextView title, ImageView icon, boolean selected) {
        card.setBackgroundResource(selected ? R.drawable.bg_card_selected : R.drawable.bg_card);
        int color = ContextCompat.getColor(context, selected ? R.color.hn_cyan : R.color.hn_text_primary);
        title.setTextColor(color);
        icon.setImageTintList(ColorStateList.valueOf(color));
        card.setSelected(selected);
        card.setContentDescription(title.getText() + (selected ? ", selected" : ""));
    }

    private String formatTime(long millis) {
        long seconds = Math.max(0L, millis / 1000L);
        long hours = seconds / 3600L;
        return hours > 0 ? String.format(Locale.getDefault(), "%d:%02d:%02d", hours,
                (seconds % 3600L) / 60L, seconds % 60L)
                : String.format(Locale.getDefault(), "%d:%02d", seconds / 60L, seconds % 60L);
    }

    public void renderPreview(PreviewUiState preview) {
        demoBadge.setVisibility(View.VISIBLE);
        radioPanel.setVisibility(View.GONE);
        libraryPanel.setVisibility(View.GONE);
        videoPanel.setVisibility(View.GONE);
        playerView.setVisibility(View.GONE);
        fullscreen.setVisibility(View.GONE);
        favorite.setVisibility(preview.source == MediaSourceType.RADIO ? View.VISIBLE : View.GONE);
        selectCard(preview.source);
        headerState.setText(preview.header);
        setHero(preview.heroEyebrow, preview.heroTitle, preview.heroSubtitle,
                preview.visualizerMode, false);
        if (preview.showNowPlaying) {
            nowPlayingPanel.setVisibility(View.VISIBLE);
            statePanel.setVisibility(View.GONE);
            mediaBadge.setText(preview.mediaBadge);
            mediaTitle.setText(preview.mediaTitle);
            mediaSubtitle.setText(preview.mediaSubtitle);
            playPause.setImageResource(R.drawable.ic_pause);
            boolean live = "LIVE".equals(preview.duration);
            seekBar.setEnabled(!live);
            seekBar.setProgress(live ? 0 : 380);
            elapsed.setText(preview.elapsed);
            duration.setText(preview.duration);
        } else {
            nowPlayingPanel.setVisibility(View.GONE);
            showState(preview.stateIcon, preview.stateEyebrow, preview.stateTitle,
                    preview.stateMessage, null, StateAction.NONE);
        }
    }
}
