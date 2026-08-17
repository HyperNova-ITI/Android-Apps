package com.hypernova.media.ui;

import com.hypernova.media.model.MediaSourceType;
import com.hypernova.media.visualizer.VisualizerMode;

/** Generic renderer input; concrete debug-only sample metadata lives outside main sources. */
public final class PreviewUiState {
    public final MediaSourceType source;
    public final String header;
    public final String heroEyebrow;
    public final String heroTitle;
    public final String heroSubtitle;
    public final VisualizerMode visualizerMode;
    public final boolean showNowPlaying;
    public final String mediaBadge;
    public final String mediaTitle;
    public final String mediaSubtitle;
    public final String elapsed;
    public final String duration;
    public final int stateIcon;
    public final String stateEyebrow;
    public final String stateTitle;
    public final String stateMessage;

    public PreviewUiState(MediaSourceType source, String header, String heroEyebrow,
            String heroTitle, String heroSubtitle, VisualizerMode visualizerMode,
            boolean showNowPlaying, String mediaBadge, String mediaTitle, String mediaSubtitle,
            String elapsed, String duration, int stateIcon, String stateEyebrow,
            String stateTitle, String stateMessage) {
        this.source = source;
        this.header = header;
        this.heroEyebrow = heroEyebrow;
        this.heroTitle = heroTitle;
        this.heroSubtitle = heroSubtitle;
        this.visualizerMode = visualizerMode;
        this.showNowPlaying = showNowPlaying;
        this.mediaBadge = mediaBadge;
        this.mediaTitle = mediaTitle;
        this.mediaSubtitle = mediaSubtitle;
        this.elapsed = elapsed;
        this.duration = duration;
        this.stateIcon = stateIcon;
        this.stateEyebrow = stateEyebrow;
        this.stateTitle = stateTitle;
        this.stateMessage = stateMessage;
    }
}
