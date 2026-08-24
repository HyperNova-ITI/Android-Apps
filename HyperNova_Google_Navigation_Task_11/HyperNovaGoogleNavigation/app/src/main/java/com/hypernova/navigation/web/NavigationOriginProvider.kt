package com.hypernova.navigation.web

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.location.Location
import android.location.LocationManager
import androidx.core.content.ContextCompat
import com.hypernova.navigation.model.GeoPoint

internal data class NavigationOrigin(
    val point: GeoPoint,
    val usesDemoOrigin: Boolean,
)

/**
 * Uses a recent Android location when one exists. The NXP bench image currently
 * has no GNSS provider, so route previews fall back explicitly to the frozen ITI
 * Smart Village demo origin instead of fabricating a live vehicle position.
 */
internal class NavigationOriginProvider(context: Context) {
    private val application = context.applicationContext

    fun current(): NavigationOrigin {
        val live = recentLocation()
        return if (live == null) {
            NavigationOrigin(DEMO_ORIGIN, usesDemoOrigin = true)
        } else {
            NavigationOrigin(
                GeoPoint(live.latitude, live.longitude),
                usesDemoOrigin = false,
            )
        }
    }

    private fun recentLocation(): Location? {
        if (
            ContextCompat.checkSelfPermission(application, Manifest.permission.ACCESS_FINE_LOCATION) !=
            PackageManager.PERMISSION_GRANTED
        ) {
            return null
        }
        val manager = application.getSystemService(LocationManager::class.java) ?: return null
        return try {
            manager.getProviders(true)
                .asSequence()
                .mapNotNull(manager::getLastKnownLocation)
                .filter(::isUsable)
                .maxByOrNull(Location::getTime)
        } catch (_: SecurityException) {
            null
        }
    }

    private fun isUsable(location: Location): Boolean {
        if (
            !location.latitude.isFinite() ||
            !location.longitude.isFinite() ||
            location.latitude !in -90.0..90.0 ||
            location.longitude !in -180.0..180.0
        ) {
            return false
        }
        val age = System.currentTimeMillis() - location.time
        return age in 0..MAX_LOCATION_AGE_MILLIS
    }

    private companion object {
        const val MAX_LOCATION_AGE_MILLIS = 10 * 60 * 1_000L
        val DEMO_ORIGIN = GeoPoint(latitude = 30.07112, longitude = 31.02075)
    }
}
