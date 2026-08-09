package com.hypernova.media.ui;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;
import android.text.Editable;
import android.text.TextWatcher;
import android.view.View;
import android.view.ViewGroup;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.appcompat.app.AlertDialog;
import androidx.core.content.ContextCompat;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.google.android.material.button.MaterialButton;
import com.hypernova.media.R;
import com.hypernova.media.model.RadioUiState;
import com.hypernova.media.radio.RadioRepository;
import com.hypernova.media.radio.RadioSearchQuery;
import com.hypernova.media.radio.RadioStation;

import java.util.List;
import java.util.Locale;

/** Owns catalog discovery, bounded server search, filters, and custom station management. */
public final class RadioBrowserController {
    public interface Listener { void onStationSelected(RadioStation station); }
    private final Context context;
    private final RadioRepository repository;
    private final Listener listener;
    private final RadioStationAdapter adapter;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private final EditText search;
    private final TextView status;
    private final Runnable searchTask = this::performSearch;

    public RadioBrowserController(View root, RadioRepository repository, Listener listener) {
        context = root.getContext();
        this.repository = repository;
        this.listener = listener;
        adapter = new RadioStationAdapter(listener::onStationSelected, this::showActions);
        RecyclerView list = root.findViewById(R.id.radio_list);
        list.setLayoutManager(new LinearLayoutManager(context));
        list.setAdapter(adapter);
        search = root.findViewById(R.id.radio_search);
        status = root.findViewById(R.id.radio_catalog_status);
        search.addTextChangedListener(new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int start, int count, int after) {}
            @Override public void onTextChanged(CharSequence s, int start, int before, int count) {
                handler.removeCallbacks(searchTask);
                handler.postDelayed(searchTask, 450L);
            }
            @Override public void afterTextChanged(Editable value) {}
        });
        bindChip(root, R.id.radio_chip_popular, () -> repository.selectMode(RadioSearchQuery.Mode.POPULAR));
        bindChip(root, R.id.radio_chip_top_voted, () -> repository.selectMode(RadioSearchQuery.Mode.TOP_VOTED));
        bindChip(root, R.id.radio_chip_trending, () -> repository.selectMode(RadioSearchQuery.Mode.TRENDING));
        bindChip(root, R.id.radio_chip_egypt, () -> repository.search(query().withFilters("EG", "", "")));
        bindChip(root, R.id.radio_chip_arabic, () -> repository.search(query().withFilters("", "arabic", "")));
        bindChip(root, R.id.radio_chip_english, () -> repository.search(query().withFilters("", "english", "")));
        bindChip(root, R.id.radio_chip_genre, this::showGenreFilter);
        bindChip(root, R.id.radio_chip_favorites, () -> repository.selectMode(RadioSearchQuery.Mode.FAVORITES));
        bindChip(root, R.id.radio_chip_recent, () -> repository.selectMode(RadioSearchQuery.Mode.RECENT));
        root.findViewById(R.id.button_radio_refresh).setOnClickListener(v -> repository.refresh());
        root.findViewById(R.id.button_add_station).setOnClickListener(v -> showEditor(null));
        root.findViewById(R.id.button_manage_stations).setOnClickListener(v -> showManage());
        submit(repository.currentState());
    }

    private RadioSearchQuery query() {
        RadioSearchQuery current = repository.currentQuery();
        return new RadioSearchQuery(RadioSearchQuery.Mode.SEARCH, search.getText().toString(),
                current.countryCode, current.language, current.tag, current.codec, 0,
                RadioSearchQuery.DEFAULT_LIMIT);
    }

    private void performSearch() {
        RadioSearchQuery current = repository.currentQuery();
        String value = search.getText().toString().trim();
        if (value.equals(current.name)) return;
        repository.search(new RadioSearchQuery(value.isEmpty() ? RadioSearchQuery.Mode.POPULAR
                : RadioSearchQuery.Mode.SEARCH, value, "", "", "", "", 0,
                RadioSearchQuery.DEFAULT_LIMIT));
    }

    private void bindChip(View root, int id, Runnable action) {
        root.findViewById(id).setOnClickListener(v -> action.run());
    }

    public void submit(RadioUiState state) {
        adapter.submit(state.stations);
        status.setText(state.message);
        status.setTextColor(ContextCompat.getColor(context,
                state.status == RadioUiState.Status.ERROR ? R.color.hn_warning
                        : state.status == RadioUiState.Status.OFFLINE
                        || state.status == RadioUiState.Status.CACHED ? R.color.hn_warning
                        : R.color.hn_text_secondary));
        updateSelectedChips(state.query);
    }

    public void showAdd() { showEditor(null); }
    public void showManageStations() { showManage(); }

    private void updateSelectedChips(RadioSearchQuery query) {
        select(R.id.radio_chip_popular, query.mode == RadioSearchQuery.Mode.POPULAR
                && query.countryCode.isEmpty() && query.language.isEmpty());
        select(R.id.radio_chip_top_voted, query.mode == RadioSearchQuery.Mode.TOP_VOTED);
        select(R.id.radio_chip_trending, query.mode == RadioSearchQuery.Mode.TRENDING);
        select(R.id.radio_chip_egypt, "EG".equalsIgnoreCase(query.countryCode));
        select(R.id.radio_chip_arabic, query.language.toLowerCase(Locale.ROOT).contains("arabic"));
        select(R.id.radio_chip_english, query.language.toLowerCase(Locale.ROOT).contains("english"));
        select(R.id.radio_chip_genre, !query.tag.isEmpty());
        select(R.id.radio_chip_favorites, query.mode == RadioSearchQuery.Mode.FAVORITES);
        select(R.id.radio_chip_recent, query.mode == RadioSearchQuery.Mode.RECENT);
    }

    private void select(int id, boolean selected) {
        MaterialButton button = ((android.app.Activity) context).findViewById(id);
        button.setStrokeColorResource(selected ? R.color.hn_cyan : R.color.hn_border);
        button.setTextColor(ContextCompat.getColor(context,
                selected ? R.color.hn_cyan : R.color.hn_text_primary));
    }

    private void showGenreFilter() {
        String[] genres = {"news", "arabic", "quran", "music", "jazz", "classical", "rock", "pop"};
        new AlertDialog.Builder(context).setTitle("Genre or tag").setItems(genres,
                (dialog, which) -> repository.search(query().withFilters("", "", genres[which])))
                .setNeutralButton("Clear", (dialog, which) -> repository.search(query().withFilters("", "", "")))
                .show();
    }

    private void showEditor(RadioStation existing) {
        LinearLayout form = new LinearLayout(context);
        form.setOrientation(LinearLayout.VERTICAL);
        int padding = Math.round(20 * context.getResources().getDisplayMetrics().density);
        form.setPadding(padding, 0, padding, 0);
        EditText name = field(context.getString(R.string.station_name), existing == null ? "" : existing.name);
        EditText url = field(context.getString(R.string.stream_url), existing == null ? "" : existing.streamUrl);
        EditText artwork = field(context.getString(R.string.artwork_url), existing == null ? "" : existing.artworkUrl);
        EditText country = field("Country or 2-letter code", existing == null ? "" :
                existing.countryName.isEmpty() ? existing.countryCode : existing.countryName);
        EditText language = field("Language", existing == null ? "" : existing.language);
        EditText tags = field("Tags / genres", existing == null ? "" : existing.tags);
        form.addView(name); form.addView(url); form.addView(artwork);
        form.addView(country); form.addView(language); form.addView(tags);
        AlertDialog dialog = new AlertDialog.Builder(context)
                .setTitle(existing == null ? "Add internet station" : "Edit internet station")
                .setMessage("Use a direct HTTP(S) audio stream. Custom streams remain unverified until playback succeeds.")
                .setView(form).setNegativeButton(R.string.cancel, null)
                .setPositiveButton(R.string.save, null).create();
        dialog.setOnShowListener(ignored -> dialog.getButton(AlertDialog.BUTTON_POSITIVE)
                .setOnClickListener(v -> {
                    try {
                        RadioStation saved = existing == null
                                ? repository.add(text(name), text(url), text(artwork), text(country), text(language), text(tags))
                                : repository.update(existing.id, text(name), text(url), text(artwork), text(country), text(language), text(tags));
                        dialog.dismiss(); listener.onStationSelected(saved);
                    } catch (IllegalArgumentException error) { url.setError(error.getMessage()); }
                }));
        dialog.show();
    }

    private String text(EditText view) { return view.getText().toString().trim(); }

    private EditText field(String hint, String value) {
        EditText input = new EditText(context);
        input.setHint(hint); input.setText(value); input.setSingleLine(true);
        input.setTextColor(ContextCompat.getColor(context, R.color.hn_text_primary));
        input.setHintTextColor(ContextCompat.getColor(context, R.color.hn_text_muted));
        input.setLayoutParams(new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                Math.round(54 * context.getResources().getDisplayMetrics().density)));
        return input;
    }

    private void showManage() {
        List<RadioStation> stations = repository.all();
        if (stations.isEmpty()) { showEditor(null); return; }
        String[] names = new String[stations.size()];
        for (int i = 0; i < stations.size(); i++) names[i] = stations.get(i).name;
        new AlertDialog.Builder(context).setTitle("Manage stations")
                .setMessage("Long-press any row for these actions.")
                .setItems(names, (dialog, index) -> showActions(stations.get(index))).show();
    }

    private void showActions(RadioStation station) {
        String favorite = station.favorite ? "Remove favorite" : "Add favorite";
        String[] actions = station.custom
                ? new String[]{"Play", favorite, "Edit", "Delete"}
                : new String[]{"Play", favorite, "Hide broken station"};
        new AlertDialog.Builder(context).setTitle(station.name).setItems(actions, (dialog, which) -> {
            if (which == 0) listener.onStationSelected(station);
            else if (which == 1) repository.toggleFavorite(station.id);
            else if (station.custom && which == 2) showEditor(station);
            else if (station.custom) new AlertDialog.Builder(context).setTitle("Delete station?")
                    .setMessage(station.name).setNegativeButton(R.string.cancel, null)
                    .setPositiveButton(R.string.delete, (d, w) -> repository.delete(station.id)).show();
            else repository.hide(station.id);
        }).show();
    }
}
