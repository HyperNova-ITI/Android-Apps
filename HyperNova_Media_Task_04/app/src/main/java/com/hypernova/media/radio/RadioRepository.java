package com.hypernova.media.radio;

import android.content.Context;
import android.content.SharedPreferences;
import android.net.ConnectivityManager;
import android.net.Network;
import android.net.NetworkCapabilities;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;

import androidx.annotation.Nullable;

import com.hypernova.media.model.RadioUiState;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicInteger;

/** Lifecycle-safe catalog repository with bounded SQLite cache and mirror failover. */
public final class RadioRepository {
    public interface Listener { void onRadioStateChanged(RadioUiState state); }

    private static final String TAG = "HyperNovaRadio";
    private static final String PREFS = "hypernova_radio_catalog";
    private final Context context;
    private final RadioDatabase database;
    private final RadioApiClient api = new RadioApiClient();
    private final ExecutorService executor = Executors.newSingleThreadExecutor(r -> {
        Thread thread = new Thread(r, "HyperNova-RadioCatalog");
        thread.setDaemon(true); return thread;
    });
    private final Handler main = new Handler(Looper.getMainLooper());
    private final List<Listener> listeners = new CopyOnWriteArrayList<>();
    private final AtomicInteger generation = new AtomicInteger();
    @Nullable private Future<?> request;
    private volatile RadioSearchQuery query;
    private volatile RadioUiState state;
    private boolean started;

    public RadioRepository(Context context) {
        this.context = context.getApplicationContext();
        database = new RadioDatabase(this.context);
        migrateLegacyCustomStations();
        query = restoreQuery();
        state = RadioUiState.initial(database.query(query));
    }

    public RadioUiState currentState() { return state; }
    public RadioSearchQuery currentQuery() { return query; }
    public List<RadioStation> all() { return state.stations; }
    public List<RadioStation> recent() {
        return database.query(query.withMode(RadioSearchQuery.Mode.RECENT));
    }
    @Nullable public RadioStation find(String id) { return database.find(id); }

    public void addListener(Listener listener) {
        listeners.add(listener);
        listener.onRadioStateChanged(state);
    }

    public void removeListener(Listener listener) { listeners.remove(listener); }

    public void start() {
        if (!started) { started = true; refresh(); }
    }

    public void refresh() { load(query, true); }

    public void selectMode(RadioSearchQuery.Mode mode) {
        query = new RadioSearchQuery(mode, "", "", "", "", "", 0,
                RadioSearchQuery.DEFAULT_LIMIT);
        persistQuery();
        if (mode == RadioSearchQuery.Mode.FAVORITES || mode == RadioSearchQuery.Mode.RECENT) {
            publishLocal("Saved on this phone");
        } else load(query, false);
    }

    public void search(RadioSearchQuery value) {
        query = value;
        persistQuery();
        if (value.mode == RadioSearchQuery.Mode.FAVORITES
                || value.mode == RadioSearchQuery.Mode.RECENT) publishLocal("Saved on this phone");
        else load(value, false);
    }

    private void load(RadioSearchQuery requested, boolean explicitRefresh) {
        int token = generation.incrementAndGet();
        if (request != null) request.cancel(true);
        List<RadioStation> cached = database.query(requested);
        state = new RadioUiState(RadioUiState.Status.LOADING, cached, requested,
                cached.isEmpty() ? "Finding healthy stations…" : "Refreshing station health…",
                state.server, state.updatedAt);
        dispatch();
        if (!isOnline()) {
            state = new RadioUiState(RadioUiState.Status.OFFLINE, cached, requested,
                    cached.isEmpty() ? "Offline · no cached stations match these filters."
                            : "Offline · showing cached stations.", "", state.updatedAt);
            dispatch();
            return;
        }
        request = executor.submit(() -> {
            try {
                RadioApiClient.Result result = api.load(requested);
                if (Thread.currentThread().isInterrupted() || token != generation.get()) return;
                database.upsertCatalog(result.stations);
                List<RadioStation> merged = database.query(requested);
                long now = System.currentTimeMillis();
                main.post(() -> {
                    if (token != generation.get()) return;
                    state = new RadioUiState(merged.isEmpty() ? RadioUiState.Status.EMPTY
                            : RadioUiState.Status.READY, merged, requested,
                            merged.isEmpty() ? "No healthy stations matched these filters."
                                    : merged.size() + " healthy stations · Radio Browser",
                            result.server, now);
                    dispatch();
                });
            } catch (Exception error) {
                if (Thread.currentThread().isInterrupted() || token != generation.get()) return;
                Log.w(TAG, "Catalog request failed: " + error.getClass().getSimpleName());
                List<RadioStation> fallback = database.query(requested);
                main.post(() -> {
                    if (token != generation.get()) return;
                    state = new RadioUiState(fallback.isEmpty() ? RadioUiState.Status.ERROR
                            : RadioUiState.Status.CACHED, fallback, requested,
                            fallback.isEmpty() ? "Radio Browser is unavailable. Try again shortly."
                                    : "API unavailable · showing cached stations.", "", state.updatedAt);
                    dispatch();
                });
            }
        });
    }

    public RadioStation add(String name, String url, String artwork, String country,
            String language, String tags) {
        RadioStation station = new RadioStation("custom:" + UUID.randomUUID(), name, url, url,
                artwork, "", country.length() == 2 ? country : "", country.length() == 2 ? "" : country,
                language, tags, "", 0, 0, 0, 0, false, true, false, 0L,
                true, false, false, System.currentTimeMillis());
        if (!station.isPlayable()) throw new IllegalArgumentException(
                "Use a station name and a valid HTTP(S) stream URL.");
        database.saveCustom(station);
        publishLocal("Custom station saved · unverified until playback succeeds");
        return station;
    }

    public RadioStation update(String id, String name, String url, String artwork,
            String country, String language, String tags) {
        RadioStation old = database.find(id);
        if (old == null || !old.custom) throw new IllegalArgumentException("Station can no longer be edited.");
        RadioStation station = new RadioStation(old.id, name, url, url, artwork, "",
                country.length() == 2 ? country : "", country.length() == 2 ? "" : country,
                language, tags, old.codec, old.bitrate, old.votes, old.clickCount, old.clickTrend,
                old.hls, true, old.favorite, old.lastPlayedAt, true, false, false,
                System.currentTimeMillis());
        if (!station.isPlayable()) throw new IllegalArgumentException(
                "Use a station name and a valid HTTP(S) stream URL.");
        database.saveCustom(station);
        publishLocal("Custom station updated · playback verification required");
        return station;
    }

    public void delete(String id) { database.deleteCustom(id); publishLocal("Station deleted"); }

    public RadioStation toggleFavorite(String id) {
        RadioStation station = database.find(id);
        if (station == null) return null;
        database.updateLocalState(id, !station.favorite, null, null, null);
        publishLocal(station.favorite ? "Removed from favorites" : "Added to favorites");
        return database.find(id);
    }

    public RadioStation markPlayed(String id) {
        RadioStation station = database.find(id);
        if (station == null) return null;
        database.updateLocalState(id, null, System.currentTimeMillis(), null, null);
        publishLocal("Recent station updated");
        executor.execute(() -> api.countClick(id));
        return database.find(id);
    }

    public void markVerified(String id) {
        RadioStation station = database.find(id);
        if (station != null && station.custom && !station.verified) {
            database.updateLocalState(id, null, null, null, true);
            publishLocal("Custom stream verified");
        }
    }

    public void hide(String id) {
        database.updateLocalState(id, null, null, true, null);
        publishLocal("Broken station hidden on this phone");
    }

    public void retry() { load(query, true); }

    private void publishLocal(String message) {
        List<RadioStation> values = database.query(query);
        state = new RadioUiState(values.isEmpty() ? RadioUiState.Status.EMPTY
                : RadioUiState.Status.READY, values, query, message, state.server,
                state.updatedAt);
        dispatch();
    }

    private void dispatch() { for (Listener listener : listeners) listener.onRadioStateChanged(state); }

    private boolean isOnline() {
        ConnectivityManager manager = (ConnectivityManager) context.getSystemService(Context.CONNECTIVITY_SERVICE);
        if (manager == null) return false;
        Network network = manager.getActiveNetwork();
        NetworkCapabilities capabilities = network == null ? null : manager.getNetworkCapabilities(network);
        return capabilities != null && capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET);
    }

    private void persistQuery() {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
                .putString("mode", query.mode.name()).putString("name", query.name)
                .putString("country", query.countryCode).putString("language", query.language)
                .putString("tag", query.tag).putString("codec", query.codec).apply();
    }

    private RadioSearchQuery restoreQuery() {
        SharedPreferences preferences = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        RadioSearchQuery.Mode mode;
        try { mode = RadioSearchQuery.Mode.valueOf(preferences.getString("mode", "POPULAR")); }
        catch (Exception ignored) { mode = RadioSearchQuery.Mode.POPULAR; }
        return new RadioSearchQuery(mode, preferences.getString("name", ""),
                preferences.getString("country", ""), preferences.getString("language", ""),
                preferences.getString("tag", ""), preferences.getString("codec", ""),
                0, RadioSearchQuery.DEFAULT_LIMIT);
    }

    private void migrateLegacyCustomStations() {
        SharedPreferences legacy = context.getSharedPreferences("hypernova_radio", Context.MODE_PRIVATE);
        String raw = legacy.getString("stations_v1", "");
        if (raw.isEmpty() || legacy.getBoolean("migrated_to_sqlite", false)) return;
        try {
            JSONArray array = new JSONArray(raw);
            for (int i = 0; i < array.length(); i++) {
                JSONObject value = array.optJSONObject(i);
                if (value == null) continue;
                RadioStation station = new RadioStation("custom:" + value.optString("id", UUID.randomUUID().toString()),
                        value.optString("name", ""), value.optString("url", ""), value.optString("url", ""),
                        value.optString("artwork", ""), "", "", "", "", "", "", 0,
                        0, 0, 0, false, true, value.optBoolean("favorite", false),
                        value.optLong("recent", 0L), true, false, false, System.currentTimeMillis());
                if (station.isPlayable()) database.saveCustom(station);
            }
        } catch (Exception error) { Log.w(TAG, "Legacy custom station migration skipped", error); }
        legacy.edit().putBoolean("migrated_to_sqlite", true).apply();
    }
}
