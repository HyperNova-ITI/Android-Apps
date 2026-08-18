package com.hypernova.navigation.ui

import com.hypernova.navigation.domain.model.GeoPoint
import com.hypernova.navigation.domain.model.GeoDistance
import com.hypernova.navigation.domain.model.RouteStep
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlin.math.roundToInt

object NavigationFormatters {

    fun routeDistance(distanceMeters: Double): String =
        if (distanceMeters >= 1_000.0) {
            String.format(
                Locale.getDefault(),
                "%.1f km",
                distanceMeters / 1_000.0
            )
        } else {
            "${distanceMeters.roundToInt()} m"
        }

    fun stepDistance(distanceMeters: Double): String =
        when {
            distanceMeters >= 1_000.0 ->
                String.format(
                    Locale.getDefault(),
                    "%.1f km",
                    distanceMeters / 1_000.0
                )
            distanceMeters >= 100.0 ->
                "${(distanceMeters / 50.0).roundToInt() * 50} m"
            else ->
                "${((distanceMeters / 10.0).roundToInt() * 10).coerceAtLeast(10)} m"
        }

    fun duration(durationSeconds: Double): String {
        val totalMinutes =
            (durationSeconds / 60.0)
                .roundToInt()
                .coerceAtLeast(1)
        val hours = totalMinutes / 60
        val minutes = totalMinutes % 60

        return when {
            hours == 0 -> "$minutes min"
            minutes == 0 -> "$hours hr"
            else -> "$hours hr $minutes min"
        }
    }

    fun arrivalTime(
        durationSeconds: Double,
        nowEpochMillis: Long = System.currentTimeMillis(),
        zoneId: ZoneId = ZoneId.systemDefault(),
        use24HourFormat: Boolean = true
    ): String {
        val arrival =
            Instant.ofEpochMilli(
                nowEpochMillis + (durationSeconds * 1_000L).toLong()
            )

        return DateTimeFormatter
            .ofPattern(
                if (use24HourFormat) "HH:mm" else "h:mm a",
                Locale.getDefault()
            )
            .withZone(zoneId)
            .format(arrival)
    }

    fun straightLineDistance(
        from: GeoPoint,
        to: GeoPoint
    ): Double = GeoDistance.meters(from, to)

    fun maneuverGlyph(step: RouteStep): String =
        when {
            step.maneuverType == "arrive" -> "destination"
            step.maneuverType == "depart" -> "straight"
            step.maneuverType in setOf("roundabout", "rotary") ->
                "roundabout"
            step.maneuverModifier.contains("left") -> "left"
            step.maneuverModifier.contains("right") -> "right"
            step.maneuverModifier == "uturn" -> "uturn"
            else -> "straight"
        }

}
