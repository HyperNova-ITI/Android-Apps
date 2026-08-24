package com.hypernova.media;

import android.Manifest;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.res.Configuration;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.SeekBar;
import android.widget.TextView;
import android.widget.Toast;
import android.animation.ObjectAnimator;
import android.animation.ValueAnimator;

import androidx.activity.OnBackPressedCallback;
import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.content.ContextCompat;
import androidx.core.view.ViewCompat;
import androidx.core.view.WindowCompat;
import androidx.core.view.WindowInsetsCompat;
import androidx.core.view.WindowInsetsControllerCompat;
import androidx.core.widget.NestedScrollView;
import androidx.transition.AutoTransition;
import androidx.transition.TransitionManager;

import com.hypernova.media.bluetooth.BluetoothAudioBackend;
import com.hypernova.media.audio.MediaVolumeController;
import com.hypernova.media.debug.DemoModeController;
import com.hypernova.media.model.BluetoothUiState;
import com.hypernova.media.model.LibraryUiState;
import com.hypernova.media.model.MediaSourceType;
import com.hypernova.media.model.PlaybackUiState;
import com.hypernova.media.model.RadioUiState;
import com.hypernova.media.playback.PlaybackController;
import com.hypernova.media.radio.RadioStation;
import com.hypernova.media.radio.RadioRepository;
import com.hypernova.media.radio.InternetRadioBackend;
import com.hypernova.media.ui.MainUiRenderer;
import com.hypernova.media.ui.RadioBrowserController;
import com.hypernova.media.video.YoutubeWebSession;
import com.hypernova.visuals.CockpitNavigationController;

import java.text.DateFormat;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

public final class MainActivity extends AppCompatActivity implements PlaybackController.Listener,
        BluetoothAudioBackend.Listener, RadioRepository.Listener, InternetRadioBackend.Listener,
        YoutubeWebSession.FullscreenListener, YoutubeWebSession.NavigationListener {
    private static final String PREF_PERMISSION = "permission_prompts";
    private static final String HYPERNOVA_SETTINGS_PACKAGE =
            "com.hypernova.settings";
    private static final String HYPERNOVA_SETTINGS_OPEN_ACTION =
            "com.hypernova.settings.action.OPEN";
    private final Handler clockHandler = new Handler(Looper.getMainLooper());
    private final Runnable clockTicker = new Runnable() {
        @Override public void run() {
            if (headerTime != null) headerTime.setText(DateFormat.getTimeInstance(DateFormat.SHORT).format(new java.util.Date()));
            clockHandler.postDelayed(this, 30_000L);
        }
    };

    private HyperNovaMediaApplication application;
    private MainUiRenderer renderer;
    private PlaybackUiState playbackState = PlaybackUiState.DISCONNECTED;
    private BluetoothUiState bluetoothState;
    private final LibraryUiState libraryState = LibraryUiState.noFolder();
    private RadioUiState radioState = RadioUiState.initial(Collections.emptyList());
    private MediaSourceType selectedSource = MediaSourceType.HOME;
    private RadioBrowserController radioBrowser;
    private TextView headerTime;
    private View volumePanel;
    private SeekBar volumeSeekBar;
    private TextView volumeValue;
    private boolean userAdjustingVolume;

    private final Handler volumeHandler =
            new Handler(Looper.getMainLooper());

    private final Runnable volumeTicker =
            new Runnable() {
                @Override
                public void run() {
                    refreshVolumeUi();
                    volumeHandler.postDelayed(this, 1000L);
                }
            };

    private NestedScrollView contentScroll;
    private ViewGroup contentStack;
    private FrameLayout videoRenderHost;
    private FrameLayout fullscreenVideoHost;
    private View fullscreenPanel;
    private boolean fullscreen;
    private boolean webFullscreen;
    private android.webkit.WebChromeClient.CustomViewCallback webFullscreenCallback;
    private View youtubeBackButton;
    private boolean youtubeCanNavigateBack;
    private boolean demoMode;
    private DemoModeController demoController;
    private boolean shuffle;
    private ObjectAnimator statusAnimator;
    private final ActivityResultLauncher<String> bluetoothPermissionLauncher = registerForActivityResult(
            new ActivityResultContracts.RequestPermission(), granted -> {
                getSharedPreferences(PREF_PERMISSION, MODE_PRIVATE)
                        .edit()
                        .putBoolean("bluetooth_requested", true)
                        .apply();

                application.bluetooth().refresh();

                if (granted) {
                    openBluetoothSettings();
                } else {
                    showBluetoothPermissionDeniedDialog();
                }
            });

    private final ActivityResultLauncher<String> notificationPermissionLauncher = registerForActivityResult(
            new ActivityResultContracts.RequestPermission(), ignored ->
                    getSharedPreferences(PREF_PERMISSION, MODE_PRIVATE).edit().putBoolean("notification_requested", true).apply());

    @Override protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        WindowCompat.setDecorFitsSystemWindows(getWindow(), false);
        setContentView(R.layout.activity_main);
        CockpitNavigationController.bind(
                findViewById(R.id.cockpitNavigation),
                CockpitNavigationController.Destination.MEDIA);
        application = (HyperNovaMediaApplication) getApplication();
        renderer = new MainUiRenderer(findViewById(R.id.main));
        demoController = new DemoModeController(this, findViewById(R.id.main), renderer, active -> {
            demoMode = active;
            if (!active) { selectedSource = MediaSourceType.HOME; render(); }
        });
        bindViews();
        bindActions();
        application.youtubeWeb().setNavigationListener(this);
        configureResponsiveLayout();
        configureInsets();
        configureSystemBarAppearance();
        bluetoothState = application.bluetooth().currentState();
        radioState = application.radioStations().currentState();
        playbackState = application.playback().getState();
        attachPlayerIfReady();
        render();
        demoController.applyIntent(getIntent());
    }

    @Override protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        setIntent(intent);
        demoController.applyIntent(intent);
    }

    private void bindViews() {
        headerTime = findViewById(R.id.header_time);
        volumePanel = findViewById(R.id.volume_panel);
        volumeSeekBar = findViewById(R.id.volume_seek_bar);
        volumeValue = findViewById(R.id.volume_value);
        contentScroll = findViewById(R.id.content_scroll);
        contentStack = findViewById(R.id.content_stack);
        videoRenderHost = findViewById(R.id.video_render_host);
        fullscreenVideoHost = findViewById(R.id.fullscreen_video_host);
        fullscreenPanel = findViewById(R.id.fullscreen_panel);
        View root = findViewById(R.id.main);
        radioBrowser = new RadioBrowserController(root, application.radioStations(), this::playStation);
        youtubeBackButton = findViewById(R.id.button_youtube_back);
    }

    private void bindActions() {
        findViewById(R.id.card_radio).setOnClickListener(v -> selectSource(MediaSourceType.RADIO));
        findViewById(R.id.card_bluetooth).setOnClickListener(v -> selectSource(MediaSourceType.BLUETOOTH));
        findViewById(R.id.card_library).setOnClickListener(v -> selectSource(MediaSourceType.VIDEO));
        findViewById(R.id.button_back).setOnClickListener(v -> handleBack());
        findViewById(R.id.button_play_pause).setOnClickListener(v -> {
            if (selectedSource == MediaSourceType.BLUETOOTH) {
                application.bluetooth().playPauseRemote();
            } else {
                ensureNotificationPermission();
                application.playback().playPause();
            }
        });

        findViewById(R.id.button_previous)
                .setOnClickListener(v -> previous());

        findViewById(R.id.button_next)
                .setOnClickListener(v -> next());

        findViewById(R.id.button_rewind).setOnClickListener(v -> {
            if (selectedSource == MediaSourceType.BLUETOOTH) {
                application.bluetooth().seekRemoteBy(-10_000L);
            } else {
                application.playback().seekBy(-10_000L);
            }
        });

        findViewById(R.id.button_forward).setOnClickListener(v -> {
            if (selectedSource == MediaSourceType.BLUETOOTH) {
                application.bluetooth().seekRemoteBy(10_000L);
            } else {
                application.playback().seekBy(10_000L);
            }
        });
        findViewById(R.id.button_shuffle).setOnClickListener(v -> {
            if (selectedSource == MediaSourceType.RADIO) {
                application.playback().stopAndClear(); return;
            }
            shuffle = !shuffle; application.playback().setShuffle(shuffle); v.setActivated(shuffle);
        });
        findViewById(R.id.button_repeat).setOnClickListener(v -> {
            if (selectedSource == MediaSourceType.RADIO) application.radio().retry();
            else application.playback().cycleRepeat();
        });
        findViewById(R.id.button_more).setOnClickListener(v -> {
            if (selectedSource == MediaSourceType.RADIO) radioBrowser.showManageStations();
            else showQueueDialog();
        });
        findViewById(R.id.button_favorite).setOnClickListener(v -> toggleFavorite());
        findViewById(R.id.button_fullscreen).setOnClickListener(v -> enterFullscreen());
        findViewById(R.id.button_exit_fullscreen).setOnClickListener(v -> exitFullscreen());
        findViewById(R.id.state_action).setOnClickListener(v -> handleStateAction());
        findViewById(R.id.button_youtube_back).setOnClickListener(v -> application.youtubeWeb().goBackOrHome());
        findViewById(R.id.button_youtube_home).setOnClickListener(v -> application.youtubeWeb().goHome());
        findViewById(R.id.button_video_web_fullscreen).setOnClickListener(v -> enterFullscreen());
        bindVolumeControl();
        bindSeekBar();
        getOnBackPressedDispatcher().addCallback(this, new OnBackPressedCallback(true) {
            @Override public void handleOnBackPressed() { handleBack(); }
        });
    }

    private void bindVolumeControl() {
        volumeSeekBar.setMax(100);

        volumeSeekBar.setOnSeekBarChangeListener(
                new SeekBar.OnSeekBarChangeListener() {
                    @Override
                    public void onProgressChanged(
                            SeekBar seekBar,
                            int progress,
                            boolean fromUser) {

                        if (!fromUser) {
                            return;
                        }

                        volumeValue.setText(progress + "%");

                        application.volume()
                                .setPercentage(progress);
                    }

                    @Override
                    public void onStartTrackingTouch(
                            SeekBar seekBar) {

                        userAdjustingVolume = true;
                    }

                    @Override
                    public void onStopTrackingTouch(
                            SeekBar seekBar) {

                        application.volume()
                                .setPercentage(
                                        seekBar.getProgress());

                        userAdjustingVolume = false;
                        refreshVolumeUi();
                    }
                });

        refreshVolumeUi();
    }

    private void refreshVolumeUi() {
        if (volumePanel == null
                || volumeSeekBar == null
                || volumeValue == null
                || application == null) {
            return;
        }

        boolean visible =
                selectedSource != MediaSourceType.HOME;

        volumePanel.setVisibility(
                visible
                        ? View.VISIBLE
                        : View.GONE);

        if (!visible) {
            return;
        }

        MediaVolumeController volume =
                application.volume();

        volume.refresh();

        boolean available =
                volume.isAvailable();

        volumeSeekBar.setEnabled(available);

        if (!available) {
            volumeValue.setText("--");
            return;
        }

        int percentage =
                volume.getPercentage();

        if (!userAdjustingVolume) {
            volumeSeekBar.setProgress(percentage);
        }

        volumeValue.setText(percentage + "%");
    }

    private void bindSeekBar() {
        ((SeekBar) findViewById(R.id.seek_bar)).setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override public void onProgressChanged(SeekBar seekBar, int progress, boolean fromUser) {}
            @Override public void onStartTrackingTouch(SeekBar seekBar) { renderer.setUserSeeking(true); }
            @Override public void onStopTrackingTouch(SeekBar seekBar) {
                if (selectedSource == MediaSourceType.BLUETOOTH) {
                    if (bluetoothState != null
                            && bluetoothState.remoteDurationMs > 0L) {

                        long target =
                                bluetoothState.remoteDurationMs
                                        * seekBar.getProgress()
                                        / 1000L;

                        application.bluetooth()
                                .seekRemoteTo(target);

                        /*
                         * Keep the user's thumb position stable briefly while
                         * the remote AVRCP MediaSession publishes its next
                         * playback-position snapshot.
                         */
                        seekBar.postDelayed(
                                () -> renderer.setUserSeeking(false),
                                700L);

                        return;
                    }

                    renderer.setUserSeeking(false);
                    return;
                }

                if (playbackState.durationMs > 0L) {
                    application.playback().seekTo(
                            playbackState.durationMs
                                    * seekBar.getProgress()
                                    / 1000L);
                }

                renderer.setUserSeeking(false);
            }
        });
    }

    private void configureInsets() {
        View root = findViewById(R.id.main);
        ViewCompat.setOnApplyWindowInsetsListener(root, (view, insets) -> {
            androidx.core.graphics.Insets bars = insets.getInsets(WindowInsetsCompat.Type.systemBars());
            view.setPadding(bars.left, bars.top, bars.right, bars.bottom);
            return insets;
        });
        ViewCompat.requestApplyInsets(root);
    }

    private void configureResponsiveLayout() {
        if (getResources().getConfiguration().screenWidthDp >= 480) return;
        int[] cards = {R.id.card_radio, R.id.card_bluetooth, R.id.card_library};
        int[] subtitles = {R.id.source_subtitle_radio, R.id.source_subtitle_bluetooth,
                R.id.source_subtitle_library};
        int[] titles = {R.id.source_title_radio, R.id.source_title_bluetooth, R.id.source_title_library};
        int sidePadding = Math.round(4f * getResources().getDisplayMetrics().density);
        int verticalPadding = Math.round(5f * getResources().getDisplayMetrics().density);
        for (int i = 0; i < cards.length; i++) {
            android.widget.LinearLayout card = findViewById(cards[i]);
            card.setOrientation(android.widget.LinearLayout.VERTICAL);
            card.setGravity(android.view.Gravity.CENTER);
            card.setPadding(sidePadding, verticalPadding, sidePadding, verticalPadding);
            android.widget.LinearLayout copy = (android.widget.LinearLayout) card.getChildAt(1);
            copy.setGravity(android.view.Gravity.CENTER);
            copy.setLayoutParams(new android.widget.LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
            ((TextView) findViewById(titles[i])).setGravity(android.view.Gravity.CENTER);
            findViewById(subtitles[i]).setVisibility(View.GONE);
        }
    }

    @Override protected void onStart() {
        super.onStart();
        application.playback().addListener(this);
        application.bluetooth().start(this);
        application.radioStations().addListener(this);
        application.radio().setListener(this);
        application.radioStations().start();
        renderer.visualizer().start();
        startStatusAnimation();
        clockHandler.removeCallbacks(clockTicker);
        clockHandler.post(clockTicker);

        volumeHandler.removeCallbacks(volumeTicker);
        volumeHandler.post(volumeTicker);
    }

    @Override protected void onResume() {
        super.onResume();
        configureSystemBarAppearance();
        if (selectedSource == MediaSourceType.VIDEO) attachYoutube();
        refreshVolumeUi();
    }

    @Override public void onWindowFocusChanged(boolean hasFocus) {
        super.onWindowFocusChanged(hasFocus);
        if (hasFocus) configureSystemBarAppearance();
    }

    @Override protected void onStop() {
        renderer.visualizer().stop();
        stopStatusAnimation();
        clockHandler.removeCallbacks(clockTicker);
        volumeHandler.removeCallbacks(volumeTicker);
        application.playback().removeListener(this);
        application.bluetooth().stop(this);
        application.youtubeWeb().detach(true);
        application.radioStations().removeListener(this);
        application.radio().setListener(null);
        super.onStop();
    }

    private void selectSource(MediaSourceType source) {
        demoController.exit();
        if (selectedSource == MediaSourceType.VIDEO && source != MediaSourceType.VIDEO) leaveVideo();
        selectedSource = source;
        if (source == MediaSourceType.BLUETOOTH) application.bluetooth().refresh();
        if (source == MediaSourceType.VIDEO) {
            application.playback().pause();
            // Explicit source selection always resets only the page to YouTube home; session stays.
            application.youtubeWeb().openYoutubeHome(this);
            attachYoutube();
        }
        contentScroll.stopNestedScroll();
        contentScroll.scrollTo(0, 0);
        TransitionManager.beginDelayedTransition(contentStack, new AutoTransition().setDuration(180L));
        render();
    }

    private void render() {
        if (renderer == null || demoMode) return;
        refreshVolumeUi();

        renderer.render(selectedSource, playbackState, bluetoothState, libraryState,
                radioState, application.radio(), false);
        attachPlayerIfReady();
        renderVideoControls();
    }

    private void attachPlayerIfReady() {
        if (application == null || renderer == null) return;
        if (renderer.playerView().getPlayer() != application.playback().getPlayer()) {
            renderer.playerView().setPlayer(application.playback().getPlayer());
        }
    }

    @Override public void onPlaybackStateChanged(PlaybackUiState state) {
        playbackState = state;
        if (state.playing && state.item != null && state.item.getId().startsWith("radio:custom:")) {
            application.radioStations().markVerified(
                    state.item.getId().substring("radio:".length()));
        }
        attachPlayerIfReady();
        render();
    }
    @Override public void onBluetoothStateChanged(BluetoothUiState state) {
        bluetoothState = state; render();
    }
    @Override public void onYoutubeNavigationChanged(boolean canGoBack, boolean canReturnHome,
            String url, boolean playbackKnown, boolean playing) {
        youtubeCanNavigateBack = canReturnHome;
        if (renderer != null) renderer.setYoutubePlaybackState(playbackKnown, playing);
        renderVideoControls();
        if (selectedSource == MediaSourceType.VIDEO) render();
    }
    @Override public void onRadioStateChanged(RadioUiState state) {
        radioState = state;
        radioBrowser.submit(state); render();
    }

    @Override public void onInternetRadioBackendChanged() { render(); }

    private void playStation(RadioStation station) {
        if (application.playback().getPlayer() == null) {
            toast("Playback service is still connecting."); return;
        }
        demoController.exit(); selectedSource = MediaSourceType.RADIO;
        ensureNotificationPermission(); application.radio().play(station); revealPlayer(); render();
    }

    private void revealPlayer() {
        contentScroll.stopNestedScroll();
        contentScroll.post(() -> contentScroll.smoothScrollTo(0, 0));
    }

    private void previous() {
        if (selectedSource == MediaSourceType.RADIO) {
            application.radio().previous();
        } else if (selectedSource == MediaSourceType.BLUETOOTH) {
            application.bluetooth().previousRemote();
        } else {
            application.playback().previous();
        }
    }

    private void next() {
        if (selectedSource == MediaSourceType.RADIO) {
            application.radio().next();
        } else if (selectedSource == MediaSourceType.BLUETOOTH) {
            application.bluetooth().nextRemote();
        } else {
            application.playback().next();
        }
    }

    private void handleStateAction() {
        switch (renderer.stateAction()) {
            case ADD_STATION: radioBrowser.showAdd(); break;
            case BLUETOOTH_PERMISSION: requestBluetoothPermissionOrSettings(); break;
            case BLUETOOTH_SETTINGS: openBluetoothSettings(); break;
            case RETRY:
                if (selectedSource == MediaSourceType.RADIO
                        && application.radio().getError() != null) application.radio().retry();
                else if (selectedSource == MediaSourceType.RADIO) application.radioStations().retry();
                else application.playback().retry();
                break;
            default: break;
        }
    }

    private void requestBluetoothPermissionOrSettings() {
        if (Build.VERSION.SDK_INT < 31 || ContextCompat.checkSelfPermission(
                this, Manifest.permission.BLUETOOTH_CONNECT)
                == PackageManager.PERMISSION_GRANTED) {
            application.bluetooth().refresh();
            openBluetoothSettings();
            return;
        }

        bluetoothPermissionLauncher.launch(
                Manifest.permission.BLUETOOTH_CONNECT);
    }

    private void showBluetoothPermissionDeniedDialog() {
        new AlertDialog.Builder(this)
                .setTitle("Bluetooth access required")
                .setMessage(
                        "Allow Nearby devices access so HyperNova Media can "
                                + "detect the connected phone. You can still "
                                + "open HyperNova Bluetooth Settings to pair "
                                + "a device.")
                .setNegativeButton(R.string.cancel, null)
                .setPositiveButton(
                        "Open Bluetooth settings",
                        (dialog, which) -> openBluetoothSettings())
                .show();
    }

    private boolean wasRequested(String key) {
        return getSharedPreferences(PREF_PERMISSION, MODE_PRIVATE).getBoolean(key, false);
    }

    private void ensureNotificationPermission() {
        if (Build.VERSION.SDK_INT >= 33 && ContextCompat.checkSelfPermission(this,
                Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
                && !wasRequested("notification_requested")) {
            notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS);
        }
    }

    private void openBluetoothSettings() {
        Intent bluetoothIntent =
                new Intent(Settings.ACTION_BLUETOOTH_SETTINGS)
                        .setPackage(HYPERNOVA_SETTINGS_PACKAGE)
                        .addFlags(
                                Intent.FLAG_ACTIVITY_CLEAR_TOP
                                        | Intent.FLAG_ACTIVITY_SINGLE_TOP);

        if (bluetoothIntent.resolveActivity(getPackageManager()) != null) {
            startActivity(bluetoothIntent);
            return;
        }

        Intent settingsHomeIntent =
                new Intent(HYPERNOVA_SETTINGS_OPEN_ACTION)
                        .setPackage(HYPERNOVA_SETTINGS_PACKAGE)
                        .addCategory(Intent.CATEGORY_DEFAULT)
                        .addFlags(
                                Intent.FLAG_ACTIVITY_CLEAR_TOP
                                        | Intent.FLAG_ACTIVITY_SINGLE_TOP);

        if (settingsHomeIntent.resolveActivity(getPackageManager()) != null) {
            startActivity(settingsHomeIntent);
            return;
        }

        try {
            startActivity(
                    new Intent(Settings.ACTION_BLUETOOTH_SETTINGS));
        } catch (Exception error) {
            toast("Bluetooth settings are unavailable.");
        }
    }

    private void toggleFavorite() {
        if (selectedSource == MediaSourceType.RADIO && application.radio().getSelected() != null) {
            application.radioStations().toggleFavorite(application.radio().getSelected().id);
            toast("Station favorite updated");
        } else if (playbackState.item != null) {
            Set<String> values = new HashSet<>(getSharedPreferences("hypernova_favorites", MODE_PRIVATE)
                    .getStringSet("media_ids", Collections.emptySet()));
            if (!values.add(playbackState.item.getId())) values.remove(playbackState.item.getId());
            getSharedPreferences("hypernova_favorites", MODE_PRIVATE).edit().putStringSet("media_ids", values).apply();
            toast(values.contains(playbackState.item.getId()) ? "Added to favorites" : "Removed from favorites");
        }
    }

    private void showSpeedDialog() {
        String[] labels = {"0.75×", "1.0×", "1.25×", "1.5×", "2.0×"};
        float[] values = {0.75f, 1f, 1.25f, 1.5f, 2f};
        new AlertDialog.Builder(this).setTitle("Playback speed").setItems(labels,
                (dialog, which) -> application.playback().setSpeed(values[which])).show();
    }

    private void showQueueDialog() {
        List<String> queue = application.playback().getQueueTitles();
        List<String> actions = new java.util.ArrayList<>(queue);
        actions.add("Playback speed settings");
        int checked = application.playback().getCurrentQueueIndex();
        new AlertDialog.Builder(this).setTitle(queue.isEmpty() ? "Queue is empty" : "Playback queue")
                .setSingleChoiceItems(actions.toArray(new String[0]), checked, (dialog, which) -> {
                    dialog.dismiss();
                    if (which < queue.size()) application.playback().skipToQueueIndex(which);
                    else showSpeedDialog();
                }).show();
    }

    private void enterFullscreen() {
        if (fullscreen || selectedSource != MediaSourceType.VIDEO) return;
        fullscreen = true;
        application.youtubeWeb().attach(this, fullscreenVideoHost, this);
        fullscreenPanel.setVisibility(View.VISIBLE);
        new WindowInsetsControllerCompat(getWindow(), getWindow().getDecorView())
                .hide(WindowInsetsCompat.Type.systemBars());
    }

    private void exitFullscreen() {
        if (!fullscreen) return;
        fullscreen = false;
        if (webFullscreen && webFullscreenCallback != null) {
            android.webkit.WebChromeClient.CustomViewCallback callback = webFullscreenCallback;
            webFullscreenCallback = null;
            webFullscreen = false;
            callback.onCustomViewHidden();
        }
        fullscreenVideoHost.removeAllViews();
        if (selectedSource == MediaSourceType.VIDEO) {
            attachYoutube();
        }
        fullscreenPanel.setVisibility(View.GONE);
        new WindowInsetsControllerCompat(getWindow(), getWindow().getDecorView())
                .hide(WindowInsetsCompat.Type.systemBars());
    }

    private void handleBack() {
        if (fullscreen) {
            exitFullscreen();
            return;
        }

        if (selectedSource == MediaSourceType.VIDEO
                && application.youtubeWeb().canNavigateBackOrHome()) {
            application.youtubeWeb().goBackOrHome();
            return;
        }

        if (demoMode) {
            demoController.exit();
            return;
        }

        if (selectedSource == MediaSourceType.VIDEO) {
            leaveVideo();
            openLauncherHome();
            return;
        }

        if (selectedSource != MediaSourceType.HOME) {
            selectedSource = MediaSourceType.HOME;
            render();
            return;
        }

        openLauncherHome();
    }

    private void openLauncherHome() {
        Intent homeIntent = new Intent(Intent.ACTION_MAIN);
        homeIntent.addCategory(Intent.CATEGORY_HOME);
        homeIntent.addFlags(
                Intent.FLAG_ACTIVITY_NEW_TASK
                        | Intent.FLAG_ACTIVITY_CLEAR_TOP
                        | Intent.FLAG_ACTIVITY_SINGLE_TOP);

        startActivity(homeIntent);
        finish();
    }

    private void toast(String message) { Toast.makeText(this, message, Toast.LENGTH_SHORT).show(); }

    private void attachYoutube() {
        if (videoRenderHost == null || fullscreen) return;
        application.youtubeWeb().attach(this, videoRenderHost, this);
    }

    private void leaveVideo() {
        if (fullscreen) exitFullscreen();
        application.youtubeWeb().detach(true);
    }

    private void renderVideoControls() {
        if (youtubeBackButton == null) return;
        youtubeBackButton.setVisibility(selectedSource == MediaSourceType.VIDEO && youtubeCanNavigateBack
                ? View.VISIBLE : View.INVISIBLE);
    }

    @Override public void onWebFullscreen(View view,
            android.webkit.WebChromeClient.CustomViewCallback callback) {
        if (fullscreen) return;
        fullscreen = true;
        webFullscreen = true;
        webFullscreenCallback = callback;
        fullscreenVideoHost.removeAllViews();
        fullscreenVideoHost.addView(view, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        fullscreenPanel.setVisibility(View.VISIBLE);
        new WindowInsetsControllerCompat(getWindow(), getWindow().getDecorView())
                .hide(WindowInsetsCompat.Type.systemBars());
    }

    @Override public void onWebFullscreenHidden() {
        if (webFullscreen) exitFullscreen();
    }

    private void configureSystemBarAppearance() {
        boolean light = (getResources().getConfiguration().uiMode & Configuration.UI_MODE_NIGHT_MASK)
                != Configuration.UI_MODE_NIGHT_YES;
        WindowInsetsControllerCompat controller = new WindowInsetsControllerCompat(
                getWindow(), getWindow().getDecorView());
        controller.setAppearanceLightStatusBars(light);
        controller.setAppearanceLightNavigationBars(light);
        controller.hide(WindowInsetsCompat.Type.systemBars());
        controller.setSystemBarsBehavior(
                WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE);
    }

    private void startStatusAnimation() {
        View dot = findViewById(R.id.status_dot);
        statusAnimator = ObjectAnimator.ofFloat(dot, View.ALPHA, 1f, 0.42f, 1f);
        statusAnimator.setDuration(1800L);
        statusAnimator.setRepeatCount(ValueAnimator.INFINITE);
        statusAnimator.start();
    }

    private void stopStatusAnimation() {
        if (statusAnimator != null) { statusAnimator.cancel(); statusAnimator = null; }
    }

}
