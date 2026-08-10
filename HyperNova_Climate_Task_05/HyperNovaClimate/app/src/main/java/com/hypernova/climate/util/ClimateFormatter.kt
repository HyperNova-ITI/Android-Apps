package com.hypernova.climate.util

import com.hypernova.climate.model.AirQualityState
import java.util.Locale

/** Formats climate values for display (README §7 ClimateFormatter). */
object ClimateFormatter {

    private const val UNAVAILABLE = "--"

    /** e.g. 22.0 -> "22.0°C"; null -> "--". */
    fun temperature(valueC: Float?): String =
        if (valueC == null) UNAVAILABLE
        else String.format(Locale.US, "%.1f°C", valueC)

    /** e.g. 3 -> "3"; null -> "--". */
    fun fanLevel(level: Int?): String =
        level?.toString() ?: UNAVAILABLE

    fun airQuality(state: AirQualityState?): String = when (state) {
        AirQualityState.GOOD -> "GOOD"
        AirQualityState.MODERATE -> "MODERATE"
        AirQualityState.POOR -> "POOR"
        else -> UNAVAILABLE
    }
}
