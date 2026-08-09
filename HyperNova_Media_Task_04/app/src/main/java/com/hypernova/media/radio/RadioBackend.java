package com.hypernova.media.radio;

import java.util.List;

public interface RadioBackend {
    List<RadioStation> getStations();
    void play(RadioStation station);
    void previous();
    void next();
    void retry();
}
