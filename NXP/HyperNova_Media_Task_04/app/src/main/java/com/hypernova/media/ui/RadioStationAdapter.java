package com.hypernova.media.ui;

import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageView;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.RecyclerView;

import com.hypernova.media.R;
import com.hypernova.media.radio.RadioStation;

import java.util.ArrayList;
import java.util.List;

public final class RadioStationAdapter extends RecyclerView.Adapter<RadioStationAdapter.Holder> {
    public interface Listener { void onStationClicked(RadioStation station); }
    public interface ManageListener { void onStationLongClicked(RadioStation station); }
    private final Listener listener;
    private final ManageListener manageListener;
    private final RadioArtworkLoader artwork = new RadioArtworkLoader();
    private final List<RadioStation> stations = new ArrayList<>();

    public RadioStationAdapter(Listener listener, ManageListener manageListener) {
        this.listener = listener; this.manageListener = manageListener;
    }
    public void submit(List<RadioStation> values) {
        stations.clear(); stations.addAll(values); notifyDataSetChanged();
    }

    @NonNull @Override public Holder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        return new Holder(LayoutInflater.from(parent.getContext())
                .inflate(R.layout.row_radio_station, parent, false));
    }
    @Override public void onBindViewHolder(@NonNull Holder holder, int position) {
        RadioStation station = stations.get(position);
        artwork.load(holder.icon, station.artworkUrl);
        holder.title.setText(station.name);
        holder.subtitle.setText(station.locationLine());
        String tags = station.tags.isEmpty() ? station.technicalLine()
                : station.technicalLine() + " · " + station.tags;
        holder.technical.setText(tags);
        holder.duration.setText(station.favorite ? "★ LIVE" : station.healthy ? "LIVE" : "UNVERIFIED");
        holder.itemView.setOnClickListener(view -> listener.onStationClicked(station));
        holder.itemView.setOnLongClickListener(view -> {
            manageListener.onStationLongClicked(station); return true;
        });
    }
    @Override public int getItemCount() { return stations.size(); }

    static final class Holder extends RecyclerView.ViewHolder {
        final ImageView icon; final TextView title; final TextView subtitle;
        final TextView technical; final TextView duration;
        Holder(View view) {
            super(view);
            icon = view.findViewById(R.id.radio_row_artwork);
            title = view.findViewById(R.id.radio_row_title);
            subtitle = view.findViewById(R.id.radio_row_location);
            technical = view.findViewById(R.id.radio_row_technical);
            duration = view.findViewById(R.id.radio_row_status);
        }
    }
}
