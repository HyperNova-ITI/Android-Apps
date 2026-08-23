package com.hypernova.media.cluster;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import androidx.media3.common.Player;

import com.hypernova.contracts.media.MediaContract;
import com.hypernova.contracts.media.MediaPlaybackSnapshot;
import com.hypernova.media.model.MediaItemModel;
import com.hypernova.media.model.PlaybackUiState;

import org.junit.Test;

public class MediaSnapshotTranslatorTest {

    private static MediaItemModel item(String id, String title, long durationMs) {
        return new MediaItemModel(id, null, title, "Artist One", "Album One",
                "Genre", "Folder", "audio/mpeg", null, durationMs, false);
    }

    @Test
    public void hasMediaFollowsCurrentItemNotDuration() {
        PlaybackUiState state = new PlaybackUiState(true, true,
                Player.STATE_READY, 12_000L, 0L, item("radio:1", "Nova FM", 0L), null);

        MediaPlaybackSnapshot snapshot = MediaSnapshotTranslator.translate(state);

        assertTrue(snapshot.hasMedia());
        assertEquals(0L, snapshot.getDurationMs());
        assertEquals(12_000L, snapshot.getPositionMs());
        assertTrue(snapshot.isPlaying());
        assertEquals(MediaContract.PLAYBACK_STATE_READY, snapshot.getPlaybackState());
    }

    @Test
    public void noItemMeansNoMediaAndSafeDefaults() {
        PlaybackUiState state = PlaybackUiState.DISCONNECTED;

        MediaPlaybackSnapshot snapshot = MediaSnapshotTranslator.translate(state);

        assertFalse(snapshot.hasMedia());
        assertFalse(snapshot.isPlaying());
        assertEquals("", snapshot.getMediaId());
        assertEquals("", snapshot.getTitle());
        assertEquals("", snapshot.getArtist());
        assertEquals("", snapshot.getAlbum());
        assertEquals("", snapshot.getArtworkUri());
        assertEquals(0L, snapshot.getPositionMs());
        assertEquals(0L, snapshot.getDurationMs());
    }

    @Test
    public void negativePositionAndDurationAreClampedWithoutRejectingMedia() {
        PlaybackUiState state = new PlaybackUiState(true, false,
                Player.STATE_BUFFERING, -5_000L, -9_999L, item("usb:2", "Clip", 4_000L),
                null);

        MediaPlaybackSnapshot snapshot = MediaSnapshotTranslator.translate(state);

        assertTrue(snapshot.hasMedia());
        assertEquals("usb:2", snapshot.getMediaId());
        assertEquals(0L, snapshot.getPositionMs());
        assertEquals(0L, snapshot.getDurationMs());
    }

    @Test
    public void metadataIsMappedFromCurrentItem() {
        PlaybackUiState state = new PlaybackUiState(true, false, Player.STATE_ENDED,
                61_500L, 62_000L, item("local:7", "Track Seven", 62_000L), null);

        MediaPlaybackSnapshot snapshot = MediaSnapshotTranslator.translate(state);

        assertEquals("local:7", snapshot.getMediaId());
        assertEquals("Track Seven", snapshot.getTitle());
        assertEquals("Artist One", snapshot.getArtist());
        assertEquals("Album One", snapshot.getAlbum());
        assertEquals(MediaContract.PLAYBACK_STATE_ENDED, snapshot.getPlaybackState());
    }

    @Test
    public void missingArtworkMapsToEmptyStringForFutureUse() {
        MediaPlaybackSnapshot snapshot = MediaSnapshotTranslator.translate(
                new PlaybackUiState(true, true, Player.STATE_READY, 1_000L, 3_000L,
                        item("bt:9", "Stream", 0L), null));

        assertEquals("", snapshot.getArtworkUri());
    }

    @Test
    public void contractConstantsMatchImplementedComponent() {
        assertEquals("com.hypernova.media.action.BIND_STATUS",
                MediaContract.BIND_STATUS_ACTION);
        assertEquals("com.hypernova.media", MediaContract.PACKAGE_NAME);
        assertEquals("com.hypernova.media.cluster.MediaStatusService",
                MediaContract.STATUS_SERVICE);
        assertTrue(MediaContract.API_VERSION >= 1);
    }
}
