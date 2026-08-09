package com.hypernova.media.radio;

import android.util.Log;
import android.net.Uri;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.net.HttpURLConnection;
import java.net.InetAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

/** Synchronous worker-thread client for the official Radio Browser mirror network. */
public final class RadioApiClient {
    public static final class Result {
        public final List<RadioStation> stations;
        public final String server;
        Result(List<RadioStation> stations, String server) {
            this.stations = stations; this.server = server;
        }
    }

    private static final String TAG = "HyperNovaRadioApi";
    private static final String DISCOVERY_HOST = "all.api.radio-browser.info";
    private static final String USER_AGENT = "HyperNovaMediaPhone/1.0 (Android)";
    private static final int CONNECT_TIMEOUT_MS = 7_000;
    private static final int READ_TIMEOUT_MS = 12_000;
    private static final int MAX_RESPONSE_BYTES = 2_000_000;
    private final Object hostLock = new Object();
    private List<String> cachedHosts = Collections.emptyList();
    private long hostsCachedAt;

    public Result load(RadioSearchQuery query) throws IOException {
        if (query.mode == RadioSearchQuery.Mode.FAVORITES
                || query.mode == RadioSearchQuery.Mode.RECENT) {
            return new Result(Collections.emptyList(), "local cache");
        }
        if (query.mode == RadioSearchQuery.Mode.POPULAR && query.name.isEmpty()
                && query.countryCode.isEmpty() && query.language.isEmpty() && query.tag.isEmpty()) {
            return loadUsefulDiscovery(query.limit);
        }
        return request(pathFor(query));
    }

    private Result loadUsefulDiscovery(int requestedLimit) throws IOException {
        Map<String, RadioStation> combined = new LinkedHashMap<>();
        String server = "";
        IOException last = null;
        String[] paths = {
                "/json/stations/search?countrycode=EG&order=clickcount&reverse=true&hidebroken=true&limit=18",
                "/json/stations/search?language=arabic&order=clickcount&reverse=true&hidebroken=true&limit=18",
                "/json/stations/search?language=english&order=clickcount&reverse=true&hidebroken=true&limit=18",
                "/json/stations/topclick/30?hidebroken=true"
        };
        ExecutorService parallel = Executors.newFixedThreadPool(paths.length, runnable -> {
            Thread thread = new Thread(runnable, "HyperNova-RadioFeed");
            thread.setDaemon(true); return thread;
        });
        List<Callable<Result>> tasks = new ArrayList<>();
        for (String path : paths) tasks.add(() -> request(path));
        try {
            List<Future<Result>> futures = parallel.invokeAll(tasks, 15, TimeUnit.SECONDS);
            for (Future<Result> future : futures) {
                if (future.isCancelled()) continue;
                try {
                    Result result = future.get();
                    server = result.server;
                    for (RadioStation station : result.stations) combined.put(station.id, station);
                } catch (Exception error) {
                    last = new IOException("Discovery feed failed", error);
                }
            }
        } catch (InterruptedException error) {
            Thread.currentThread().interrupt();
            throw new IOException("Request cancelled", error);
        } finally {
            parallel.shutdownNow();
        }
        if (combined.isEmpty() && last != null) throw last;
        List<RadioStation> values = new ArrayList<>(combined.values());
        values.sort((left, right) -> Integer.compare(right.clickCount, left.clickCount));
        int limit = Math.min(Math.max(requestedLimit, 48), 72);
        Log.i(TAG, "Discovery returned " + values.size() + " healthy unique stations from " + server);
        return new Result(new ArrayList<>(values.subList(0, Math.min(limit, values.size()))), server);
    }

    public void countClick(String stationUuid) {
        if (stationUuid == null || stationUuid.startsWith("custom:")) return;
        try { sendClick("/json/url/" + encode(stationUuid)); }
        catch (IOException error) { Log.d(TAG, "Click count unavailable: " + error.getClass().getSimpleName()); }
    }

    private void sendClick(String path) throws IOException {
        IOException last = null;
        for (String host : discoverHosts()) {
            try { read("https://" + host + path); return; }
            catch (IOException error) { last = error; }
        }
        if (last != null) throw last;
    }

    private String pathFor(RadioSearchQuery query) {
        if (query.mode == RadioSearchQuery.Mode.TOP_VOTED) {
            return "/json/stations/topvote/" + query.limit + "?hidebroken=true&offset=" + query.offset;
        }
        if (query.mode == RadioSearchQuery.Mode.TRENDING) {
            return "/json/stations/search?order=clicktrend&reverse=true&hidebroken=true&limit="
                    + query.limit + "&offset=" + query.offset;
        }
        StringBuilder path = new StringBuilder("/json/stations/search?hidebroken=true");
        append(path, "name", query.name);
        append(path, "countrycode", query.countryCode);
        append(path, "language", query.language);
        append(path, "tag", query.tag);
        append(path, "codec", query.codec);
        path.append("&order=clickcount&reverse=true&offset=").append(query.offset)
                .append("&limit=").append(query.limit);
        return path.toString();
    }

    private Result request(String path) throws IOException {
        IOException last = null;
        for (String host : discoverHosts()) {
            if (Thread.currentThread().isInterrupted()) throw new IOException("Request cancelled");
            try {
                return new Result(parse(read("https://" + host + path)), host);
            } catch (IOException | RuntimeException error) {
                last = error instanceof IOException ? (IOException) error
                        : new IOException("Invalid response from " + host, error);
                Log.w(TAG, "Mirror failed: " + host + " (" + error.getClass().getSimpleName() + ")");
            }
        }
        throw last == null ? new IOException("No Radio Browser mirror available") : last;
    }

    private List<RadioStation> parse(byte[] body) throws IOException {
        try {
            JSONArray array = new JSONArray(new String(body, StandardCharsets.UTF_8));
            List<RadioStation> result = new ArrayList<>();
            Set<String> seen = new LinkedHashSet<>();
            long now = System.currentTimeMillis();
            for (int i = 0; i < array.length(); i++) {
                JSONObject value = array.optJSONObject(i);
                if (value == null) continue;
                RadioStation station = RadioStation.fromApi(value, now);
                if (station.healthy && station.isPlayable() && seen.add(station.id)) result.add(station);
            }
            return result;
        } catch (Exception error) {
            throw new IOException("Radio Browser returned malformed JSON", error);
        }
    }

    private byte[] read(String url) throws IOException {
        HttpURLConnection connection = null;
        try {
            connection = (HttpURLConnection) URI.create(url).toURL().openConnection();
            connection.setConnectTimeout(CONNECT_TIMEOUT_MS);
            connection.setReadTimeout(READ_TIMEOUT_MS);
            connection.setInstanceFollowRedirects(true);
            connection.setRequestProperty("User-Agent", USER_AGENT);
            connection.setRequestProperty("Accept", "application/json");
            connection.setRequestProperty("Accept-Charset", "utf-8");
            int code = connection.getResponseCode();
            if (code < 200 || code >= 300) throw new IOException("HTTP " + code);
            try (BufferedInputStream input = new BufferedInputStream(connection.getInputStream());
                    ByteArrayOutputStream output = new ByteArrayOutputStream(64 * 1024)) {
                byte[] buffer = new byte[8192];
                int total = 0;
                int read;
                while ((read = input.read(buffer)) != -1) {
                    if (Thread.currentThread().isInterrupted()) throw new IOException("Request cancelled");
                    total += read;
                    if (total > MAX_RESPONSE_BYTES) throw new IOException("Response too large");
                    output.write(buffer, 0, read);
                }
                return output.toByteArray();
            }
        } finally {
            if (connection != null) connection.disconnect();
        }
    }

    private List<String> discoverHosts() {
        synchronized (hostLock) {
            long now = System.currentTimeMillis();
            if (!cachedHosts.isEmpty() && now - hostsCachedAt < 6 * 60 * 60 * 1000L) {
                return new ArrayList<>(cachedHosts);
            }
            Set<String> discovered = new LinkedHashSet<>();
            try {
                InetAddress[] addresses = InetAddress.getAllByName(DISCOVERY_HOST);
                for (InetAddress address : addresses) {
                    String host = address.getCanonicalHostName().toLowerCase(java.util.Locale.ROOT);
                    if (host.endsWith(".api.radio-browser.info") && !DISCOVERY_HOST.equals(host)) {
                        discovered.add(host);
                    }
                }
            } catch (Exception error) {
                Log.w(TAG, "Mirror discovery unavailable", error);
            }
            // The discovery hostname itself remains a network-managed fallback, not a fixed mirror.
            discovered.add(DISCOVERY_HOST);
            cachedHosts = new ArrayList<>(discovered);
            Collections.shuffle(cachedHosts);
            hostsCachedAt = now;
            return new ArrayList<>(cachedHosts);
        }
    }

    private static void append(StringBuilder output, String name, String value) {
        if (value != null && !value.isEmpty()) output.append('&').append(name).append('=').append(encode(value));
    }

    private static String encode(String value) {
        return Uri.encode(value);
    }
}
