package com.hypernova.media.ui;

import android.content.Context;
import android.content.SharedPreferences;
import android.text.Editable;
import android.text.TextWatcher;
import android.view.View;
import android.widget.EditText;

import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.hypernova.media.R;
import com.hypernova.media.model.MediaItemModel;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/** Owns library search, category sorting, recent history, and list rendering. */
public final class LibraryBrowserController {
    public interface Listener { void onMediaSelected(MediaItemModel item, List<MediaItemModel> queue); }
    private enum Filter { TRACKS, ARTISTS, ALBUMS, GENRES, VIDEOS, FOLDERS, RECENT, FAVORITES }
    private final Context context;
    private final Listener listener;
    private final MediaListAdapter adapter;
    private final List<MediaItemModel> items = new ArrayList<>();
    private Filter filter = Filter.TRACKS;
    private String query = "";

    public LibraryBrowserController(View root, Listener listener) {
        context = root.getContext();
        this.listener = listener;
        adapter = new MediaListAdapter(item -> {
            List<MediaItemModel> queue = filteredItems();
            rememberRecent(item.getId());
            listener.onMediaSelected(item, queue);
        });
        RecyclerView list = root.findViewById(R.id.library_list);
        list.setLayoutManager(new LinearLayoutManager(context));
        list.setAdapter(adapter);
        ((EditText) root.findViewById(R.id.library_search)).addTextChangedListener(new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int start, int count, int after) {}
            @Override public void onTextChanged(CharSequence s, int start, int before, int count) {}
            @Override public void afterTextChanged(Editable value) { query = value.toString(); refresh(); }
        });
        bind(root, R.id.chip_tracks, Filter.TRACKS);
        bind(root, R.id.chip_artists, Filter.ARTISTS);
        bind(root, R.id.chip_albums, Filter.ALBUMS);
        bind(root, R.id.chip_genres, Filter.GENRES);
        bind(root, R.id.chip_videos, Filter.VIDEOS);
        bind(root, R.id.chip_folders, Filter.FOLDERS);
        bind(root, R.id.chip_recent, Filter.RECENT);
        bind(root, R.id.chip_favorites, Filter.FAVORITES);
        updateActivation(root);
    }

    public void submit(List<MediaItemModel> values) {
        items.clear(); items.addAll(values); refresh();
    }

    private void bind(View root, int id, Filter value) {
        root.findViewById(id).setOnClickListener(view -> {
            filter = value; updateActivation(root); refresh();
        });
    }

    private void updateActivation(View root) {
        int[] ids = {R.id.chip_tracks, R.id.chip_artists, R.id.chip_albums, R.id.chip_genres,
                R.id.chip_videos, R.id.chip_folders, R.id.chip_recent, R.id.chip_favorites};
        Filter[] filters = Filter.values();
        for (int i = 0; i < ids.length; i++) root.findViewById(ids[i]).setActivated(filters[i] == filter);
    }

    public void refresh() { adapter.submit(filteredItems()); }

    private List<MediaItemModel> filteredItems() {
        String needle = query.trim().toLowerCase(Locale.ROOT);
        Set<String> recent = history().getStringSet("recent_ids", Collections.emptySet());
        Set<String> favorites = context.getSharedPreferences("hypernova_favorites", Context.MODE_PRIVATE)
                .getStringSet("media_ids", Collections.emptySet());
        List<MediaItemModel> result = new ArrayList<>();
        for (MediaItemModel item : items) {
            if (!matchesFilter(item, recent, favorites)) continue;
            String haystack = (item.getTitle() + " " + item.getArtist() + " " + item.getAlbum()
                    + " " + item.getGenre() + " " + item.getFolder()).toLowerCase(Locale.ROOT);
            if (needle.isEmpty() || haystack.contains(needle)) result.add(item);
        }
        Comparator<MediaItemModel> comparator;
        if (filter == Filter.ARTISTS) comparator = Comparator.comparing(
                MediaItemModel::getArtist, String.CASE_INSENSITIVE_ORDER).thenComparing(MediaItemModel::getTitle);
        else if (filter == Filter.ALBUMS) comparator = Comparator.comparing(
                MediaItemModel::getAlbum, String.CASE_INSENSITIVE_ORDER).thenComparing(MediaItemModel::getTitle);
        else if (filter == Filter.GENRES) comparator = Comparator.comparing(
                MediaItemModel::getGenre, String.CASE_INSENSITIVE_ORDER).thenComparing(MediaItemModel::getTitle);
        else if (filter == Filter.FOLDERS) comparator = Comparator.comparing(
                MediaItemModel::getFolder, String.CASE_INSENSITIVE_ORDER).thenComparing(MediaItemModel::getTitle);
        else comparator = Comparator.comparing(MediaItemModel::getTitle, String.CASE_INSENSITIVE_ORDER);
        result.sort(comparator);
        return result;
    }

    private boolean matchesFilter(MediaItemModel item, Set<String> recent, Set<String> favorites) {
        switch (filter) {
            case VIDEOS: return item.isVideo();
            case RECENT: return recent.contains(item.getId());
            case FAVORITES: return favorites.contains(item.getId());
            case GENRES: return !item.isVideo() && !item.getGenre().isEmpty();
            case ARTISTS: return !item.isVideo() && !item.getArtist().isEmpty();
            case ALBUMS: return !item.isVideo() && !item.getAlbum().isEmpty();
            case FOLDERS: return !item.getFolder().isEmpty();
            default: return !item.isVideo();
        }
    }

    private void rememberRecent(String id) {
        Set<String> current = new HashSet<>(history().getStringSet("recent_ids", Collections.emptySet()));
        if (current.size() > 49) current.clear();
        current.add(id);
        history().edit().putStringSet("recent_ids", current).apply();
    }

    private SharedPreferences history() {
        return context.getSharedPreferences("hypernova_history", Context.MODE_PRIVATE);
    }
}
