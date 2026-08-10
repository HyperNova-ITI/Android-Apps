package com.hypernova.media.radio;

import android.content.ContentValues;
import android.content.Context;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import android.database.sqlite.SQLiteOpenHelper;

import androidx.annotation.Nullable;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/** Bounded structured cache for catalog, custom, favorite, recent, and hidden state. */
final class RadioDatabase extends SQLiteOpenHelper {
    private static final String DATABASE_NAME = "hypernova_radio.db";
    private static final int DATABASE_VERSION = 1;
    private static final int MAX_CATALOG_ROWS = 600;

    RadioDatabase(Context context) {
        super(context.getApplicationContext(), DATABASE_NAME, null, DATABASE_VERSION);
    }

    @Override public void onCreate(SQLiteDatabase db) {
        db.execSQL("CREATE TABLE station ("
                + "uuid TEXT PRIMARY KEY, name TEXT NOT NULL, stream_url TEXT NOT NULL, "
                + "original_url TEXT NOT NULL, favicon TEXT NOT NULL, homepage TEXT NOT NULL, "
                + "country_code TEXT NOT NULL, country_name TEXT NOT NULL, language TEXT NOT NULL, "
                + "tags TEXT NOT NULL, codec TEXT NOT NULL, bitrate INTEGER NOT NULL DEFAULT 0, "
                + "votes INTEGER NOT NULL DEFAULT 0, click_count INTEGER NOT NULL DEFAULT 0, "
                + "click_trend INTEGER NOT NULL DEFAULT 0, hls INTEGER NOT NULL DEFAULT 0, "
                + "healthy INTEGER NOT NULL DEFAULT 0, favorite INTEGER NOT NULL DEFAULT 0, "
                + "last_played INTEGER NOT NULL DEFAULT 0, custom INTEGER NOT NULL DEFAULT 0, "
                + "hidden INTEGER NOT NULL DEFAULT 0, verified INTEGER NOT NULL DEFAULT 0, "
                + "cached_at INTEGER NOT NULL DEFAULT 0)");
        db.execSQL("CREATE INDEX station_recent ON station(last_played DESC)");
        db.execSQL("CREATE INDEX station_cache ON station(cached_at DESC)");
    }

    @Override public void onUpgrade(SQLiteDatabase db, int oldVersion, int newVersion) {
        db.execSQL("DROP TABLE IF EXISTS station");
        onCreate(db);
    }

    synchronized void upsertCatalog(List<RadioStation> stations) {
        SQLiteDatabase db = getWritableDatabase();
        Map<String, RadioStation> local = new HashMap<>();
        for (RadioStation station : loadAll(db)) local.put(station.id, station);
        db.beginTransaction();
        try {
            for (RadioStation remote : stations) {
                RadioStation prior = local.get(remote.id);
                RadioStation merged = prior == null ? remote : remote.withLocalState(
                        prior.favorite, prior.lastPlayedAt, prior.hidden, prior.verified);
                db.insertWithOnConflict("station", null, values(merged),
                        SQLiteDatabase.CONFLICT_REPLACE);
            }
            db.setTransactionSuccessful();
        } finally {
            db.endTransaction();
        }
        trim(db);
    }

    synchronized void saveCustom(RadioStation station) {
        getWritableDatabase().insertWithOnConflict("station", null, values(station),
                SQLiteDatabase.CONFLICT_REPLACE);
    }

    synchronized void deleteCustom(String id) {
        getWritableDatabase().delete("station", "uuid=? AND custom=1", new String[]{id});
    }

    synchronized void updateLocalState(String id, @Nullable Boolean favorite,
            @Nullable Long lastPlayed, @Nullable Boolean hidden, @Nullable Boolean verified) {
        ContentValues values = new ContentValues();
        if (favorite != null) values.put("favorite", favorite ? 1 : 0);
        if (lastPlayed != null) values.put("last_played", lastPlayed);
        if (hidden != null) values.put("hidden", hidden ? 1 : 0);
        if (verified != null) values.put("verified", verified ? 1 : 0);
        if (values.size() > 0) getWritableDatabase().update("station", values,
                "uuid=?", new String[]{id});
    }

    @Nullable synchronized RadioStation find(String id) {
        try (Cursor cursor = getReadableDatabase().query("station", null, "uuid=?",
                new String[]{id}, null, null, null, "1")) {
            return cursor.moveToFirst() ? read(cursor) : null;
        }
    }

    synchronized List<RadioStation> query(RadioSearchQuery query) {
        List<RadioStation> result = new ArrayList<>();
        String needle = query.name.toLowerCase(Locale.ROOT);
        String language = query.language.toLowerCase(Locale.ROOT);
        String tag = query.tag.toLowerCase(Locale.ROOT);
        String codec = query.codec.toLowerCase(Locale.ROOT);
        for (RadioStation station : loadAll(getReadableDatabase())) {
            if (station.hidden || !station.isPlayable()) continue;
            if (query.mode == RadioSearchQuery.Mode.FAVORITES && !station.favorite) continue;
            if (query.mode == RadioSearchQuery.Mode.RECENT && station.lastPlayedAt <= 0L) continue;
            if (!needle.isEmpty() && !(station.name + " " + station.tags)
                    .toLowerCase(Locale.ROOT).contains(needle)) continue;
            if (!query.countryCode.isEmpty() && !query.countryCode.equalsIgnoreCase(station.countryCode)) continue;
            if (!language.isEmpty() && !station.language.toLowerCase(Locale.ROOT).contains(language)) continue;
            if (!tag.isEmpty() && !station.tags.toLowerCase(Locale.ROOT).contains(tag)) continue;
            if (!codec.isEmpty() && !station.codec.toLowerCase(Locale.ROOT).contains(codec)) continue;
            result.add(station);
        }
        Comparator<RadioStation> comparator;
        if (query.mode == RadioSearchQuery.Mode.TOP_VOTED) {
            comparator = Comparator.comparingInt((RadioStation value) -> value.votes).reversed();
        } else if (query.mode == RadioSearchQuery.Mode.TRENDING) {
            comparator = Comparator.comparingInt((RadioStation value) -> value.clickTrend).reversed();
        } else if (query.mode == RadioSearchQuery.Mode.RECENT) {
            comparator = Comparator.comparingLong((RadioStation value) -> value.lastPlayedAt).reversed();
        } else if (query.mode == RadioSearchQuery.Mode.FAVORITES) {
            comparator = Comparator.comparing((RadioStation value) -> value.name,
                    String.CASE_INSENSITIVE_ORDER);
        } else {
            comparator = Comparator.comparingInt((RadioStation value) -> value.clickCount).reversed()
                    .thenComparing(value -> value.name, String.CASE_INSENSITIVE_ORDER);
        }
        result.sort(comparator);
        int from = Math.min(query.offset, result.size());
        int to = Math.min(result.size(), from + query.limit);
        return new ArrayList<>(result.subList(from, to));
    }

    synchronized List<RadioStation> allVisible() {
        return query(new RadioSearchQuery(RadioSearchQuery.Mode.POPULAR, "", "", "", "",
                "", 0, MAX_CATALOG_ROWS));
    }

    private List<RadioStation> loadAll(SQLiteDatabase db) {
        List<RadioStation> stations = new ArrayList<>();
        try (Cursor cursor = db.query("station", null, null, null, null, null, null)) {
            while (cursor.moveToNext()) stations.add(read(cursor));
        }
        return stations;
    }

    private RadioStation read(Cursor cursor) {
        return new RadioStation(text(cursor, "uuid"), text(cursor, "name"),
                text(cursor, "stream_url"), text(cursor, "original_url"),
                text(cursor, "favicon"), text(cursor, "homepage"),
                text(cursor, "country_code"), text(cursor, "country_name"),
                text(cursor, "language"), text(cursor, "tags"), text(cursor, "codec"),
                integer(cursor, "bitrate"), integer(cursor, "votes"),
                integer(cursor, "click_count"), integer(cursor, "click_trend"),
                integer(cursor, "hls") == 1, integer(cursor, "healthy") == 1,
                integer(cursor, "favorite") == 1, number(cursor, "last_played"),
                integer(cursor, "custom") == 1, integer(cursor, "hidden") == 1,
                integer(cursor, "verified") == 1, number(cursor, "cached_at"));
    }

    private ContentValues values(RadioStation station) {
        ContentValues values = new ContentValues();
        values.put("uuid", station.id); values.put("name", station.name);
        values.put("stream_url", station.streamUrl); values.put("original_url", station.originalUrl);
        values.put("favicon", station.artworkUrl); values.put("homepage", station.homepage);
        values.put("country_code", station.countryCode); values.put("country_name", station.countryName);
        values.put("language", station.language); values.put("tags", station.tags);
        values.put("codec", station.codec); values.put("bitrate", station.bitrate);
        values.put("votes", station.votes); values.put("click_count", station.clickCount);
        values.put("click_trend", station.clickTrend); values.put("hls", station.hls ? 1 : 0);
        values.put("healthy", station.healthy ? 1 : 0); values.put("favorite", station.favorite ? 1 : 0);
        values.put("last_played", station.lastPlayedAt); values.put("custom", station.custom ? 1 : 0);
        values.put("hidden", station.hidden ? 1 : 0); values.put("verified", station.verified ? 1 : 0);
        values.put("cached_at", station.cachedAt);
        return values;
    }

    private void trim(SQLiteDatabase db) {
        List<String> removable = new ArrayList<>();
        try (Cursor cursor = db.rawQuery("SELECT uuid FROM station WHERE custom=0 AND favorite=0 "
                + "AND last_played=0 ORDER BY cached_at DESC", null)) {
            int index = 0;
            while (cursor.moveToNext()) {
                if (index++ >= MAX_CATALOG_ROWS) removable.add(cursor.getString(0));
            }
        }
        db.beginTransaction();
        try {
            for (String id : removable) db.delete("station", "uuid=?", new String[]{id});
            db.setTransactionSuccessful();
        } finally { db.endTransaction(); }
    }

    private static String text(Cursor cursor, String column) {
        String value = cursor.getString(cursor.getColumnIndexOrThrow(column));
        return value == null ? "" : value;
    }

    private static int integer(Cursor cursor, String column) {
        return cursor.getInt(cursor.getColumnIndexOrThrow(column));
    }

    private static long number(Cursor cursor, String column) {
        return cursor.getLong(cursor.getColumnIndexOrThrow(column));
    }
}
