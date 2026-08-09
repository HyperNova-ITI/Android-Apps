package com.hypernova.media.radio;

import android.net.Uri;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import org.json.JSONObject;

import java.util.Locale;
import java.util.Objects;

/** Immutable catalog or user-owned Internet Radio station. */
public final class RadioStation {
    public final String id;
    public final String name;
    public final String streamUrl;
    public final String originalUrl;
    public final String artworkUrl;
    public final String homepage;
    public final String countryCode;
    public final String countryName;
    public final String language;
    public final String tags;
    public final String codec;
    public final int bitrate;
    public final int votes;
    public final int clickCount;
    public final int clickTrend;
    public final boolean hls;
    public final boolean healthy;
    public final boolean favorite;
    public final long lastPlayedAt;
    public final boolean custom;
    public final boolean hidden;
    public final boolean verified;
    public final long cachedAt;

    public RadioStation(@NonNull String id, @NonNull String name, @NonNull String streamUrl,
            @NonNull String originalUrl, @Nullable String artworkUrl, @Nullable String homepage,
            @Nullable String countryCode, @Nullable String countryName, @Nullable String language,
            @Nullable String tags, @Nullable String codec, int bitrate, int votes, int clickCount,
            int clickTrend, boolean hls, boolean healthy, boolean favorite, long lastPlayedAt,
            boolean custom, boolean hidden, boolean verified, long cachedAt) {
        this.id = clean(id);
        this.name = clean(name);
        this.streamUrl = clean(streamUrl);
        this.originalUrl = clean(originalUrl);
        this.artworkUrl = clean(artworkUrl);
        this.homepage = clean(homepage);
        this.countryCode = clean(countryCode).toUpperCase(Locale.ROOT);
        this.countryName = clean(countryName);
        this.language = clean(language);
        this.tags = cleanTags(tags);
        this.codec = clean(codec).toUpperCase(Locale.ROOT);
        this.bitrate = Math.max(0, bitrate);
        this.votes = Math.max(0, votes);
        this.clickCount = Math.max(0, clickCount);
        this.clickTrend = clickTrend;
        this.hls = hls;
        this.healthy = healthy;
        this.favorite = favorite;
        this.lastPlayedAt = Math.max(0L, lastPlayedAt);
        this.custom = custom;
        this.hidden = hidden;
        this.verified = verified;
        this.cachedAt = Math.max(0L, cachedAt);
    }

    /** Parses API fields defensively; malformed records are rejected by {@link #isPlayable()}. */
    public static RadioStation fromApi(JSONObject value, long cachedAt) {
        String resolved = value.optString("url_resolved", "").trim();
        String original = value.optString("url", "").trim();
        if (resolved.isEmpty()) resolved = original;
        return new RadioStation(value.optString("stationuuid", ""),
                value.optString("name", ""), resolved, original,
                value.optString("favicon", ""), value.optString("homepage", ""),
                value.optString("countrycode", ""), value.optString("country", ""),
                value.optString("language", ""), value.optString("tags", ""),
                value.optString("codec", ""), value.optInt("bitrate", 0),
                value.optInt("votes", 0), value.optInt("clickcount", 0),
                value.optInt("clicktrend", 0), value.optInt("hls", 0) == 1,
                value.optInt("lastcheckok", 0) == 1, false, 0L,
                false, false, true, cachedAt);
    }

    public RadioStation withLocalState(boolean favorite, long lastPlayedAt, boolean hidden,
            boolean verified) {
        return new RadioStation(id, name, streamUrl, originalUrl, artworkUrl, homepage,
                countryCode, countryName, language, tags, codec, bitrate, votes, clickCount,
                clickTrend, hls, healthy, favorite, lastPlayedAt, custom, hidden, verified,
                cachedAt);
    }

    public RadioStation withFavorite(boolean value) {
        return withLocalState(value, lastPlayedAt, hidden, verified);
    }

    public RadioStation playedNow() {
        return withLocalState(favorite, System.currentTimeMillis(), hidden, verified);
    }

    public boolean isPlayable() {
        Uri uri = Uri.parse(streamUrl);
        String scheme = uri.getScheme();
        return !id.isEmpty() && !name.isEmpty() && uri.getHost() != null
                && ("https".equalsIgnoreCase(scheme) || "http".equalsIgnoreCase(scheme));
    }

    public String locationLine() {
        String location = !countryName.isEmpty() ? countryName : countryCode;
        if (!location.isEmpty() && !language.isEmpty()) return location + " · " + language;
        if (!location.isEmpty()) return location;
        return language.isEmpty() ? "International" : language;
    }

    public String technicalLine() {
        String value = codec;
        if (bitrate > 0) value += (value.isEmpty() ? "" : " · ") + bitrate + " kbps";
        if (value.isEmpty()) value = hls ? "HLS" : "Internet stream";
        return value;
    }

    private static String clean(@Nullable String value) {
        return value == null ? "" : value.trim();
    }

    private static String cleanTags(@Nullable String value) {
        String raw = clean(value);
        if (raw.length() > 160) raw = raw.substring(0, 160);
        return raw.replaceAll(",+", ",").replaceAll("^,|,$", "");
    }

    @Override public boolean equals(Object value) {
        return value instanceof RadioStation && id.equals(((RadioStation) value).id);
    }

    @Override public int hashCode() { return Objects.hash(id); }
}
