package com.hypernova.media.radio;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;

import org.junit.Test;

import java.util.Arrays;
import java.util.Collections;

public final class RadioStationSelectorTest {
    @Test
    public void genericMusicPrefersAPlayableVerifiedStation() {
        RadioStation unverified = station("one", "Unknown", "talk", false);
        RadioStation verified = station("two", "Nile FM", "pop,hits", true);

        assertEquals(verified, RadioStationSelector.select(
                Arrays.asList(unverified, verified), "music"));
    }

    @Test
    public void moodSelectsOnlyFromRealMatchingCatalogEntries() {
        RadioStation news = station("one", "News One", "news,talk", true);
        RadioStation jazz = station("two", "Smooth Jazz", "jazz,chill", true);

        assertEquals(jazz, RadioStationSelector.select(
                Arrays.asList(news, jazz), "something relaxing"));
        assertNull(RadioStationSelector.select(
                Arrays.asList(news, jazz), "heavy metal"));
    }

    @Test
    public void approximateSpokenDescriptionUsesRealMetadataAndRanking() {
        RadioStation news = station("one", "News One", "news,talk", true);
        RadioStation lowRankPop = station("two", "Small Wave", "pop,hits", false);
        RadioStation kiis = station("three", "102.7 KIIS FM", "pop,hits", true);

        assertEquals(kiis, RadioStationSelector.select(
                Arrays.asList(news, lowRankPop, kiis), "pop LA"));
        assertEquals(kiis, RadioStationSelector.select(
                Arrays.asList(news, kiis), "something around 102 7"));
    }

    @Test
    public void emptyCatalogCannotProduceAnInventedStation() {
        assertNull(RadioStationSelector.select(Collections.emptyList(), "popular"));
    }

    private static RadioStation station(
            String id, String name, String tags, boolean verified) {
        return new RadioStation(
                id, name, "https://radio.example/" + id, "https://radio.example/" + id,
                "", "", "EG", "Egypt", "English", tags, "MP3", 128,
                100, 100, 10, false, true, false, 0L,
                false, false, verified, 1L);
    }
}
