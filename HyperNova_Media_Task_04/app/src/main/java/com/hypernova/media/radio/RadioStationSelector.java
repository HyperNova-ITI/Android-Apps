package com.hypernova.media.radio;

import androidx.annotation.Nullable;

import java.net.URI;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/** Selects one real cached station for a bounded NOVA MediaSession request. */
public final class RadioStationSelector {
    private RadioStationSelector() {}

    @Nullable
    public static RadioStation select(List<RadioStation> stations, String rawQuery) {
        List<RadioStation> playable = new ArrayList<>();
        for (RadioStation station : stations) {
            if (isPlayable(station) && station.healthy) playable.add(station);
        }
        if (playable.isEmpty()) {
            for (RadioStation station : stations) {
                if (isPlayable(station)) playable.add(station);
            }
        }
        if (playable.isEmpty()) return null;

        String query = normalize(rawQuery);
        if (query.isEmpty() || query.equals("music") || query.equals("radio")
                || query.equals("popular") || query.equals("something")
                || query.equals("anything")) {
            return preferVerified(playable);
        }

        RadioStation exact = first(playable, station -> normalize(station.name).equals(query));
        if (exact != null) return exact;

        RadioStation text = first(playable, station -> searchable(station).contains(query));
        if (text != null) return text;

        // A spoken description is often less exact than a typed catalog search (for example,
        // "that LA pop station" instead of "102.7 KIIS FM"). Select only when at least one
        // meaningful query token is present in real station metadata, then rank the real matches.
        // This stays bounded to the cached catalog and cannot invent a station or stream URL.
        RadioStation tokenMatch = bestTokenMatch(playable, query);
        if (tokenMatch != null) return tokenMatch;

        String[] moodTags = moodTags(query);
        if (moodTags != null) {
            RadioStation mood = first(playable, station -> {
                String value = searchable(station);
                for (String tag : moodTags) if (value.contains(tag)) return true;
                return false;
            });
            if (mood != null) return mood;
        }
        return null;
    }

    @Nullable
    private static RadioStation bestTokenMatch(List<RadioStation> stations, String query) {
        Set<String> tokens = meaningfulTokens(query);
        if (tokens.isEmpty()) return null;
        RadioStation best = null;
        int bestScore = 0;
        for (RadioStation station : stations) {
            Set<String> name = tokenSet(normalize(station.name));
            Set<String> tags = tokenSet(normalize(station.tags));
            Set<String> metadata = tokenSet(normalize(
                    station.language + " " + station.countryName + " " + station.countryCode));
            int score = 0;
            for (String token : tokens) {
                if (name.contains(token)) score += 5;
                else if (tags.contains(token)) score += 3;
                else if (metadata.contains(token)) score += 1;
            }
            if (score > bestScore || (score == bestScore && score > 0 && betterRank(station, best))) {
                best = station;
                bestScore = score;
            }
        }
        return bestScore > 0 ? best : null;
    }

    private static boolean betterRank(RadioStation candidate, @Nullable RadioStation current) {
        if (current == null) return true;
        if (candidate.verified != current.verified) return candidate.verified;
        if (candidate.votes != current.votes) return candidate.votes > current.votes;
        return candidate.clickCount > current.clickCount;
    }

    private static Set<String> meaningfulTokens(String query) {
        Set<String> tokens = tokenSet(query);
        tokens.removeAll(new HashSet<>(Arrays.asList(
                "a", "an", "the", "some", "that", "this", "radio", "station", "channel",
                "play", "put", "tune", "listen", "around", "about", "near", "called", "named",
                "cant", "cannot", "remember", "exact", "name", "one", "with", "from", "for")));
        tokens.removeIf(token -> token.length() < 2);
        return tokens;
    }

    private static Set<String> tokenSet(String value) {
        Set<String> tokens = new HashSet<>();
        if (value == null || value.isEmpty()) return tokens;
        for (String token : value.split(" ")) if (!token.isEmpty()) tokens.add(token);
        return tokens;
    }

    private static RadioStation preferVerified(List<RadioStation> stations) {
        RadioStation verified = first(stations, station -> station.verified);
        return verified == null ? stations.get(0) : verified;
    }

    private static String[] moodTags(String query) {
        if (containsAny(query, "relax", "calm", "quiet", "chill")) {
            return new String[]{"chill", "relax", "jazz", "lounge", "ambient", "classical"};
        }
        if (containsAny(query, "energy", "energetic", "upbeat", "party")) {
            return new String[]{"dance", "pop", "rock", "party", "hits"};
        }
        if (containsAny(query, "focus", "study", "work")) {
            return new String[]{"classical", "ambient", "instrumental", "jazz", "focus"};
        }
        return null;
    }

    private static boolean containsAny(String value, String... needles) {
        for (String needle : needles) if (value.contains(needle)) return true;
        return false;
    }

    private static String searchable(RadioStation station) {
        return normalize(station.name + " " + station.tags + " " + station.language
                + " " + station.countryName);
    }

    private static boolean isPlayable(@Nullable RadioStation station) {
        if (station == null || station.id.isEmpty() || station.name.isEmpty()) return false;
        try {
            URI uri = URI.create(station.streamUrl);
            String scheme = uri.getScheme();
            return uri.getHost() != null
                    && ("https".equalsIgnoreCase(scheme) || "http".equalsIgnoreCase(scheme));
        } catch (IllegalArgumentException ignored) {
            return false;
        }
    }

    private interface Predicate { boolean test(RadioStation station); }

    @Nullable
    private static RadioStation first(List<RadioStation> stations, Predicate predicate) {
        for (RadioStation station : stations) if (predicate.test(station)) return station;
        return null;
    }

    private static String normalize(String value) {
        return (value == null ? "" : value).trim().toLowerCase(Locale.ROOT)
                .replaceAll("[^a-z0-9]+", " ").trim();
    }
}
