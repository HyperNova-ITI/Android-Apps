package com.hypernova.launcher.ui

import java.net.URLEncoder
import java.nio.charset.StandardCharsets

/** Google-owned, read-only HOME map. Navigation remains the only route authority. */
internal object LauncherGoogleMapsPage {
    const val DOCUMENT_ORIGIN = "https://nova.hypernova.local/"
    const val MAP_ID = "20d0a8fe56e67ae4e0d3323d"

    fun render(apiKey: String, isNightMode: Boolean): String {
        val encodedKey = URLEncoder.encode(apiKey, StandardCharsets.UTF_8.name())
        val colorScheme = if (isNightMode) "DARK" else "LIGHT"
        val background = if (isNightMode) "#07121d" else "#edf5f7"
        return """
            <!doctype html>
            <html lang="en">
            <head>
              <meta charset="utf-8">
              <meta name="viewport" content="width=device-width, initial-scale=1, maximum-scale=1, user-scalable=no">
              <meta http-equiv="Content-Security-Policy" content="default-src 'none'; script-src 'unsafe-inline' 'unsafe-eval' https://maps.googleapis.com https://maps.gstatic.com; style-src 'unsafe-inline'; img-src data: blob: https://*.googleapis.com https://*.gstatic.com https://*.google.com; connect-src data: https://*.googleapis.com https://*.gstatic.com https://*.google.com; worker-src blob:">
              <style>
                * { box-sizing: border-box; }
                html, body { width: 100%; height: 100%; margin: 0; overflow: hidden; background: $background; }
                #map { position: absolute; inset: 0; background: $background; }
              </style>
              <script>
                'use strict';
                const ORIGIN = Object.freeze({ lat: 30.07112, lng: 31.02075 });
                let map;
                let routeLine = null;

                function clearRoute() {
                  if (routeLine) routeLine.setMap(null);
                  routeLine = null;
                }

                window.gm_authFailure = function() {
                  HyperNovaLauncherBridge.onInitializationFailed('Google Maps rejected the browser API key.');
                };

                window.initHyperNovaLauncher = async function() {
                  try {
                    const { Map, RenderingType } = await google.maps.importLibrary('maps');
                    map = new Map(document.getElementById('map'), {
                      center: ORIGIN,
                      zoom: 15,
                      mapId: '$MAP_ID',
                      colorScheme: '$colorScheme',
                      renderingType: RenderingType.RASTER,
                      disableDefaultUI: true,
                      clickableIcons: false,
                      gestureHandling: 'none',
                      keyboardShortcuts: false
                    });
                    // A lightweight circle avoids importing Places, Routes, or Marker libraries
                    // merely to show the fixed ITI demo location.
                    new google.maps.Circle({
                      map,
                      center: ORIGIN,
                      radius: 22,
                      fillColor: '#19d3c5',
                      fillOpacity: 1,
                      strokeColor: '#efffff',
                      strokeOpacity: 1,
                      strokeWeight: 3,
                      clickable: false
                    });
                    map.addListener('click', () => HyperNovaLauncherBridge.openNavigation());
                    google.maps.event.addListenerOnce(map, 'tilesloaded', () => {
                      HyperNovaLauncherBridge.onReady();
                    });
                  } catch (error) {
                    HyperNovaLauncherBridge.onInitializationFailed(
                      String(error && error.message ? error.message : error)
                    );
                  }
                };

                window.hypernovaShowIdle = function() {
                  clearRoute();
                  if (!map) return;
                  map.setCenter(ORIGIN);
                  map.setZoom(15);
                };

                window.hypernovaShowRoute = function(points) {
                  if (!map || !Array.isArray(points) || points.length < 2) {
                    window.hypernovaShowIdle();
                    return;
                  }
                  const path = points
                    .map((point) => ({ lat: Number(point.lat), lng: Number(point.lng) }))
                    .filter((point) => Number.isFinite(point.lat) && Number.isFinite(point.lng));
                  if (path.length < 2) {
                    window.hypernovaShowIdle();
                    return;
                  }
                  clearRoute();
                  routeLine = new google.maps.Polyline({
                    map,
                    path,
                    strokeColor: '#19d3c5',
                    strokeOpacity: .96,
                    strokeWeight: 6,
                    clickable: false
                  });
                  const bounds = new google.maps.LatLngBounds();
                  path.forEach((point) => bounds.extend(point));
                  map.fitBounds(bounds, { top: 28, right: 22, bottom: 72, left: 22 });
                };
              </script>
              <script async src="https://maps.googleapis.com/maps/api/js?key=$encodedKey&amp;loading=async&amp;callback=initHyperNovaLauncher&amp;v=quarterly"></script>
            </head>
            <body><div id="map" aria-label="Google Maps route preview"></div></body>
            </html>
        """.trimIndent()
    }
}
