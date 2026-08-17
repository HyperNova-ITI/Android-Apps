package com.hypernova.media.ui;

import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageView;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.RecyclerView;

import com.hypernova.media.R;
import com.hypernova.media.model.MediaItemModel;

import java.util.ArrayList;
import java.util.List;

public final class MediaListAdapter extends RecyclerView.Adapter<MediaListAdapter.Holder> {
    public interface Listener { void onMediaClicked(MediaItemModel item); }
    private final Listener listener;
    private final List<MediaItemModel> items = new ArrayList<>();

    public MediaListAdapter(Listener listener) { this.listener = listener; }
    public void submit(List<MediaItemModel> values) {
        items.clear(); items.addAll(values); notifyDataSetChanged();
    }

    @NonNull @Override public Holder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        return new Holder(LayoutInflater.from(parent.getContext())
                .inflate(R.layout.row_media_item, parent, false));
    }

    @Override public void onBindViewHolder(@NonNull Holder holder, int position) {
        MediaItemModel item = items.get(position);
        holder.title.setText(item.getTitle());
        holder.subtitle.setText(item.secondaryText());
        holder.icon.setImageResource(item.isVideo() ? R.drawable.ic_library : R.drawable.ic_music);
        holder.duration.setText(formatDuration(item.getDurationMs()));
        holder.itemView.setOnClickListener(view -> listener.onMediaClicked(item));
    }

    @Override public int getItemCount() { return items.size(); }

    private String formatDuration(long durationMs) {
        if (durationMs <= 0L) return "";
        long seconds = durationMs / 1000L;
        return String.format(java.util.Locale.getDefault(), "%d:%02d", seconds / 60, seconds % 60);
    }

    static final class Holder extends RecyclerView.ViewHolder {
        final ImageView icon; final TextView title; final TextView subtitle; final TextView duration;
        Holder(View view) {
            super(view);
            icon = view.findViewById(R.id.row_icon);
            title = view.findViewById(R.id.row_title);
            subtitle = view.findViewById(R.id.row_subtitle);
            duration = view.findViewById(R.id.row_duration);
        }
    }
}
