package com.hypernova.media.radio;

import androidx.annotation.NonNull;

/** Immutable bounded Radio Browser request and filter selection. */
public final class RadioSearchQuery {
    public enum Mode { POPULAR, TOP_VOTED, TRENDING, SEARCH, FAVORITES, RECENT }

    public static final int DEFAULT_LIMIT = 48;
    public final Mode mode;
    public final String name;
    public final String countryCode;
    public final String language;
    public final String tag;
    public final String codec;
    public final int offset;
    public final int limit;

    public RadioSearchQuery(@NonNull Mode mode, String name, String countryCode,
            String language, String tag, String codec, int offset, int limit) {
        this.mode = mode;
        this.name = clean(name);
        this.countryCode = clean(countryCode).toUpperCase(java.util.Locale.ROOT);
        this.language = clean(language);
        this.tag = clean(tag);
        this.codec = clean(codec);
        this.offset = Math.max(0, offset);
        this.limit = Math.max(1, Math.min(100, limit));
    }

    public static RadioSearchQuery popular() {
        return new RadioSearchQuery(Mode.POPULAR, "", "", "", "", "", 0, DEFAULT_LIMIT);
    }

    public RadioSearchQuery withMode(Mode value) {
        return new RadioSearchQuery(value, name, countryCode, language, tag, codec, 0, limit);
    }

    public RadioSearchQuery withName(String value) {
        return new RadioSearchQuery(value == null || value.trim().isEmpty() ? mode : Mode.SEARCH,
                value, countryCode, language, tag, codec, 0, limit);
    }

    public RadioSearchQuery withCountry(String value) {
        return new RadioSearchQuery(Mode.SEARCH, name, value, language, tag, codec, 0, limit);
    }

    public RadioSearchQuery withLanguage(String value) {
        return new RadioSearchQuery(Mode.SEARCH, name, countryCode, value, tag, codec, 0, limit);
    }

    public RadioSearchQuery withTag(String value) {
        return new RadioSearchQuery(Mode.SEARCH, name, countryCode, language, value, codec, 0, limit);
    }

    public RadioSearchQuery withFilters(String country, String language, String tag) {
        return new RadioSearchQuery(Mode.SEARCH, name, country, language, tag, codec, 0, limit);
    }

    public String label() {
        switch (mode) {
            case TOP_VOTED: return "TOP VOTED";
            case TRENDING: return "TRENDING";
            case FAVORITES: return "FAVORITES";
            case RECENT: return "RECENT";
            case SEARCH: return "SEARCH RESULTS";
            default: return "POPULAR NOW";
        }
    }

    private static String clean(String value) { return value == null ? "" : value.trim(); }
}
