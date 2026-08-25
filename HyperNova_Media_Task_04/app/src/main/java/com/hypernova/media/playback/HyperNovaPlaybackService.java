package com.hypernova.media.playback;

import android.app.PendingIntent;
import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;

import androidx.annotation.Nullable;
import androidx.media3.common.AudioAttributes;
import androidx.media3.common.C;
import androidx.media3.common.MediaItem;
import androidx.media3.common.MediaMetadata;
import androidx.media3.common.MimeTypes;
import androidx.media3.common.Player;
import androidx.media3.common.util.UnstableApi;
import androidx.media3.exoplayer.ExoPlayer;
import androidx.media3.datasource.DefaultHttpDataSource;
import androidx.media3.datasource.DefaultDataSource;
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory;
import androidx.media3.session.MediaSession;
import androidx.media3.session.MediaSessionService;
import androidx.media3.session.SessionCommand;
import androidx.media3.session.SessionResult;

import com.google.common.util.concurrent.Futures;
import com.google.common.util.concurrent.ListenableFuture;
import com.hypernova.media.HyperNovaMediaApplication;
import com.hypernova.media.MainActivity;
import com.hypernova.media.radio.RadioRepository;
import com.hypernova.media.radio.RadioStation;
import com.hypernova.media.radio.RadioStationSelector;

import java.util.List;

/** The only owner of ExoPlayer and MediaSession in the phone process. */
public final class HyperNovaPlaybackService extends MediaSessionService {
    private static final String NOVA_PACKAGE = "com.hypernova.ai";
    private static final String ACTION_PLAY_RADIO = "com.hypernova.media.command.PLAY_RADIO";
    private static final String EXTRA_QUERY = "query";
    private static final String EXTRA_MESSAGE = "message";
    private static final String EXTRA_STATION_ID = "station_id";
    private static final String EXTRA_STATION_NAME = "station_name";
    private static final SessionCommand PLAY_RADIO_COMMAND =
            new SessionCommand(ACTION_PLAY_RADIO, Bundle.EMPTY);

    private ExoPlayer player;
    private MediaSession mediaSession;

    private final MediaSession.Callback sessionCallback = new MediaSession.Callback() {
        @Override
        public MediaSession.ConnectionResult onConnect(
                MediaSession session, MediaSession.ControllerInfo controller) {
            MediaSession.ConnectionResult base =
                    MediaSession.Callback.super.onConnect(session, controller);
            if (!NOVA_PACKAGE.equals(controller.getPackageName())) return base;
            return MediaSession.ConnectionResult.accept(
                    base.availableSessionCommands.buildUpon().add(PLAY_RADIO_COMMAND).build(),
                    base.availablePlayerCommands);
        }

        @Override
        public ListenableFuture<SessionResult> onCustomCommand(
                MediaSession session, MediaSession.ControllerInfo controller,
                SessionCommand command, Bundle args) {
            if (!NOVA_PACKAGE.equals(controller.getPackageName())
                    || !ACTION_PLAY_RADIO.equals(command.customAction)) {
                return MediaSession.Callback.super.onCustomCommand(
                        session, controller, command, args);
            }
            return Futures.immediateFuture(playRadio(args.getString(EXTRA_QUERY, "popular")));
        }
    };

    @Override
    @UnstableApi
    public void onCreate() {
        super.onCreate();
        AudioAttributes audioAttributes = new AudioAttributes.Builder()
                .setUsage(C.USAGE_MEDIA)
                .setContentType(C.AUDIO_CONTENT_TYPE_MUSIC)
                .build();
        DefaultHttpDataSource.Factory http = new DefaultHttpDataSource.Factory()
                .setConnectTimeoutMs(8_000)
                .setReadTimeoutMs(12_000)
                .setAllowCrossProtocolRedirects(true)
                .setUserAgent("HyperNovaMediaPhone/1.0 (Android)");
        player = new ExoPlayer.Builder(this)
                .setMediaSourceFactory(new DefaultMediaSourceFactory(this)
                        .setDataSourceFactory(new DefaultDataSource.Factory(this, http)))
                .setWakeMode(C.WAKE_MODE_NETWORK)
                .build();
        player.setAudioAttributes(audioAttributes, true);
        player.setHandleAudioBecomingNoisy(true);

        Intent activityIntent = new Intent(this, MainActivity.class)
                .addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP | Intent.FLAG_ACTIVITY_CLEAR_TOP);
        PendingIntent sessionActivity = PendingIntent.getActivity(this, 0, activityIntent,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        mediaSession = new MediaSession.Builder(this, player)
                .setSessionActivity(sessionActivity)
                .setId("hypernova-phone-session")
                .setCallback(sessionCallback)
                .build();
    }

    private SessionResult playRadio(String query) {
        HyperNovaMediaApplication application =
                (HyperNovaMediaApplication) getApplication();
        RadioRepository repository = application.radioStations();
        repository.start();
        List<RadioStation> catalog = repository.cachedCatalog();
        if (catalog.isEmpty()) {
            return result(SessionResult.RESULT_ERROR_INVALID_STATE,
                    "No cached radio stations are available yet", null);
        }
        RadioStation station = RadioStationSelector.select(catalog, query);
        if (station == null) {
            return result(SessionResult.RESULT_ERROR_BAD_VALUE,
                    "No cached radio station matched " + query, null);
        }

        Uri artwork = station.artworkUrl.isEmpty() ? null : Uri.parse(station.artworkUrl);
        MediaMetadata.Builder metadata = new MediaMetadata.Builder()
                .setTitle(station.name)
                .setArtist(station.locationLine())
                .setAlbumTitle(station.technicalLine())
                .setGenre(station.tags)
                .setIsBrowsable(false)
                .setIsPlayable(true);
        if (artwork != null) metadata.setArtworkUri(artwork);
        String path = Uri.parse(station.streamUrl).getPath();
        String mimeType = station.hls || path != null
                && path.toLowerCase(java.util.Locale.ROOT).endsWith(".m3u8")
                ? MimeTypes.APPLICATION_M3U8 : null;
        MediaItem item = new MediaItem.Builder()
                .setMediaId("radio:" + station.id)
                .setUri(station.streamUrl)
                .setMimeType(mimeType)
                .setMediaMetadata(metadata.build())
                .build();
        player.setMediaItem(item);
        player.prepare();
        player.play();
        repository.markPlayed(station.id);
        return result(SessionResult.RESULT_SUCCESS,
                "Selected radio station " + station.name, station);
    }

    private static SessionResult result(int code, String message, @Nullable RadioStation station) {
        Bundle extras = new Bundle();
        extras.putString(EXTRA_MESSAGE, message);
        if (station != null) {
            extras.putString(EXTRA_STATION_ID, station.id);
            extras.putString(EXTRA_STATION_NAME, station.name);
        }
        return new SessionResult(code, extras);
    }

    @Nullable
    @Override
    public MediaSession onGetSession(MediaSession.ControllerInfo controllerInfo) {
        return mediaSession;
    }

    @Override
    public void onTaskRemoved(@Nullable Intent rootIntent) {
        if (player == null || !player.isPlaying()) stopSelf();
    }

    @Override
    public void onDestroy() {
        if (player != null) {
            player.release();
            player = null;
        }
        if (mediaSession != null) {
            mediaSession.release();
            mediaSession = null;
        }
        super.onDestroy();
    }
}
