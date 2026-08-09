package com.hypernova.media.ui;

import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.util.LruCache;
import android.widget.ImageView;

import com.hypernova.media.R;

import java.io.BufferedInputStream;
import java.io.ByteArrayOutputStream;
import java.net.HttpURLConnection;
import java.net.URI;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** Small bounded favicon loader; failures intentionally fall back to the vector radio mark. */
final class RadioArtworkLoader {
    private static final int MAX_BYTES = 512 * 1024;
    private final LruCache<String, Bitmap> cache = new LruCache<>(24);
    private final ExecutorService executor = Executors.newFixedThreadPool(2);

    void load(ImageView target, String url) {
        target.setTag(url);
        Bitmap cached = cache.get(url);
        if (cached != null) { target.setImageBitmap(cached); return; }
        target.setImageResource(R.drawable.ic_radio);
        if (url == null || url.isEmpty()) return;
        executor.execute(() -> {
            HttpURLConnection connection = null;
            try {
                connection = (HttpURLConnection) URI.create(url).toURL().openConnection();
                connection.setConnectTimeout(4_000);
                connection.setReadTimeout(5_000);
                connection.setInstanceFollowRedirects(true);
                connection.setRequestProperty("User-Agent", "HyperNovaMediaPhone/1.0 (Android)");
                int length = connection.getContentLength();
                if (connection.getResponseCode() / 100 != 2 || length > MAX_BYTES) return;
                Bitmap bitmap;
                try (BufferedInputStream input = new BufferedInputStream(connection.getInputStream())) {
                    ByteArrayOutputStream bytes = new ByteArrayOutputStream(
                            length > 0 ? Math.min(length, MAX_BYTES) : 16 * 1024);
                    byte[] buffer = new byte[8192];
                    int total = 0;
                    int count;
                    while ((count = input.read(buffer)) != -1) {
                        total += count;
                        if (total > MAX_BYTES) return;
                        bytes.write(buffer, 0, count);
                    }
                    byte[] encoded = bytes.toByteArray();
                    bitmap = BitmapFactory.decodeByteArray(encoded, 0, encoded.length);
                }
                if (bitmap == null) return;
                cache.put(url, bitmap);
                target.post(() -> { if (url.equals(target.getTag())) target.setImageBitmap(bitmap); });
            } catch (Exception ignored) {
                // Remote artwork is optional and never changes station availability.
            } finally { if (connection != null) connection.disconnect(); }
        });
    }
}
