/*
 * Copyright (C) 2026 HyperNova
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 */

package com.android.car.settings.hypernova;

import android.content.Context;
import android.util.AttributeSet;
import android.view.View;

import androidx.annotation.NonNull;
import androidx.preference.PreferenceViewHolder;

import com.hypernova.settings.R;
import com.android.car.settings.common.SeekBarPreference;

/**
 * HyperNova's compact slider presentation.
 *
 * <p>The inherited CarSettings seek-bar behavior retains rotary direct manipulation and controller
 * callbacks. This subclass only supplies the purpose-designed card and binds the visual minus and
 * plus controls to the same preference change listener.</p>
 */
public final class HyperNovaSliderPreference extends SeekBarPreference {

    public HyperNovaSliderPreference(Context context, AttributeSet attrs, int defStyleAttr,
            int defStyleRes) {
        super(context, attrs, defStyleAttr, defStyleRes);
        setLayoutResource(R.layout.hypernova_slider_card);
    }

    public HyperNovaSliderPreference(Context context, AttributeSet attrs, int defStyleAttr) {
        this(context, attrs, defStyleAttr, 0);
    }

    public HyperNovaSliderPreference(Context context, AttributeSet attrs) {
        super(context, attrs);
        setLayoutResource(R.layout.hypernova_slider_card);
    }

    public HyperNovaSliderPreference(Context context) {
        super(context);
        setLayoutResource(R.layout.hypernova_slider_card);
    }

    @Override
    public void onBindViewHolder(@NonNull PreferenceViewHolder holder) {
        super.onBindViewHolder(holder);
        View decrease = holder.findViewById(R.id.hypernova_slider_decrease);
        View increase = holder.findViewById(R.id.hypernova_slider_increase);
        if (decrease != null) {
            decrease.setEnabled(isEnabled() && getValue() > getMin());
            decrease.setOnClickListener(view -> adjustBy(-resolveIncrement()));
        }
        if (increase != null) {
            increase.setEnabled(isEnabled() && getValue() < getMax());
            increase.setOnClickListener(view -> adjustBy(resolveIncrement()));
        }
    }

    private int resolveIncrement() {
        int increment = getSeekBarIncrement();
        if (increment > 0) {
            return increment;
        }
        return Math.max(1, (getMax() - getMin()) / 20);
    }

    private void adjustBy(int delta) {
        int newValue = Math.max(getMin(), Math.min(getMax(), getValue() + delta));
        if (newValue != getValue() && callChangeListener(newValue)) {
            setValue(newValue);
        }
    }
}
