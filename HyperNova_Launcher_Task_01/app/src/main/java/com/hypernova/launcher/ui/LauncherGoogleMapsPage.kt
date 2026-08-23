package com.hypernova.launcher.ui

import java.net.URLEncoder
import java.nio.charset.StandardCharsets

/** Google-owned read-only HOME preview; Navigation remains the route authority. */
internal object LauncherGoogleMapsPage {
    const val DOCUMENT_ORIGIN = "https://nova.hypernova.local/"
    const val MAP_ID = "20d0a8fe56e67ae4e0d3323d"

    fun render(apiKey: String, isNightMode: Boolean): String {
        val encodedKey = URLEncoder.encode(apiKey, StandardCharsets.UTF_8.name())
        val colorScheme = if (isNightMode) "DARK" else "LIGHT"
        val background = if (isNightMode) "#07121d" else "#edf5f7"
        val cardBackground = if (isNightMode) "rgba(5, 20, 31, .96)" else "rgba(250, 253, 254, .94)"
        val primaryText = if (isNightMode) "#f2fbfd" else "#092331"
        val secondaryText = if (isNightMode) "#a9c3cc" else "#46616e"
        return """
            <!doctype html>
            <html lang="en">
            <head>
              <meta charset="utf-8">
              <meta name="viewport" content="width=device-width, initial-scale=1, maximum-scale=1, user-scalable=no">
              <meta http-equiv="Content-Security-Policy" content="default-src 'none'; script-src 'unsafe-inline' 'unsafe-eval' https://maps.googleapis.com https://maps.gstatic.com; style-src 'unsafe-inline' https://fonts.googleapis.com; font-src https://fonts.gstatic.com; img-src data: blob: https://*.googleapis.com https://*.gstatic.com https://*.google.com https://*.googleusercontent.com; connect-src data: https://*.googleapis.com https://*.gstatic.com https://*.google.com; worker-src blob:">
              <style>
                html, body { width: 100%; height: 100%; margin: 0; overflow: hidden; background: $background; font-family: Arial, sans-serif; }
                #map { position: absolute; inset: 0; background: $background; }
                #placeCard {
                  display: none; position: absolute; z-index: 5; top: 38px; left: 12px; right: 12px;
                  padding: 10px 12px; border: 1px solid rgba(0, 142, 164, .35); border-radius: 12px;
                  background: $cardBackground; box-shadow: 0 4px 18px rgba(4, 37, 52, .18);
                  color: $primaryText; pointer-events: auto;
                }
                #placeName { font-size: 15px; line-height: 1.2; font-weight: 700; white-space: nowrap; overflow: hidden; text-overflow: ellipsis; }
                #placeMeta { margin-top: 3px; color: $secondaryText; font-size: 11px; white-space: nowrap; overflow: hidden; text-overflow: ellipsis; }
                #placeRating { margin-top: 4px; color: #007f91; font-size: 11px; font-weight: 700; }
              </style>
              <script>
                'use strict';
                const ORIGIN = { lat: 30.07112, lng: 31.02075 };
                let map;
                let PlaceClass;
                let RouteClass;
                let AdvancedMarkerClass;
                let destinationMarker = null;
                let routeMarkers = [];
                let routePolylines = [];
                let lastQuery = '';

                function text(value) {
                  if (value == null) return '';
                  if (typeof value === 'string') return value;
                  if (typeof value.text === 'string') return value.text;
                  return String(value);
                }

                function point(value) {
                  if (!value) return null;
                  const lat = typeof value.lat === 'function' ? value.lat() : value.lat;
                  const lng = typeof value.lng === 'function' ? value.lng() : value.lng;
                  return Number.isFinite(lat) && Number.isFinite(lng) ? { lat, lng } : null;
                }

                function clearMapObjects() {
                  if (destinationMarker) destinationMarker.map = null;
                  destinationMarker = null;
                  routeMarkers.forEach((value) => { try { value.map = null; } catch (_) {} });
                  routeMarkers = [];
                  routePolylines.forEach((value) => { try { value.setMap(null); } catch (_) {} });
                  routePolylines = [];
                }

                function showPlaceCard(place) {
                  const name = text(place.displayName);
                  const category = text(place.primaryTypeDisplayName);
                  const address = text(place.formattedAddress);
                  const rating = Number(place.rating || 0);
                  const count = Number(place.userRatingCount || 0);
                  document.getElementById('placeName').textContent = name;
                  document.getElementById('placeMeta').textContent = [category, address].filter(Boolean).join(' · ');
                  document.getElementById('placeRating').textContent =
                    rating > 0
                      ? '★ ' + rating.toFixed(1) + (count > 0 ? ' (' + count.toLocaleString() + ' reviews)' : '')
                      : 'Google Maps destination';
                  document.getElementById('placeCard').style.display = 'block';
                }

                window.gm_authFailure = function() {
                  HyperNovaLauncherBridge.onInitializationFailed('Google Maps rejected the browser API key.');
                };

                window.initHyperNovaLauncher = async function() {
                  try {
                    const libraries = await Promise.all([
                      google.maps.importLibrary('maps'),
                      google.maps.importLibrary('places'),
                      google.maps.importLibrary('routes'),
                      google.maps.importLibrary('marker')
                    ]);
                    const MapClass = libraries[0].Map;
                    PlaceClass = libraries[1].Place;
                    RouteClass = libraries[2].Route;
                    AdvancedMarkerClass = libraries[3].AdvancedMarkerElement;
                    map = new MapClass(document.getElementById('map'), {
                      center: ORIGIN,
                      zoom: 15,
                      mapId: '$MAP_ID',
                      colorScheme: '$colorScheme',
                      disableDefaultUI: true,
                      clickableIcons: false,
                      gestureHandling: 'none',
                      keyboardShortcuts: false
                    });
                    map.addListener('click', () => HyperNovaLauncherBridge.openNavigation());
                    HyperNovaLauncherBridge.onReady();
                  } catch (error) {
                    HyperNovaLauncherBridge.onInitializationFailed(String(error && error.message ? error.message : error));
                  }
                };

                window.hypernovaShowIdle = function() {
                  lastQuery = '';
                  clearMapObjects();
                  document.getElementById('placeCard').style.display = 'none';
                  if (map) {
                    map.setCenter(ORIGIN);
                    map.setZoom(15);
                  }
                };

                window.hypernovaShowDestination = async function(query) {
                  query = String(query || '').trim();
                  if (!map || !query || query === lastQuery) return;
                  lastQuery = query;
                  clearMapObjects();
                  try {
                    const result = await PlaceClass.searchByText({
                      textQuery: query,
                      fields: ['id', 'displayName', 'formattedAddress', 'primaryTypeDisplayName', 'location', 'rating', 'userRatingCount'],
                      locationBias: ORIGIN,
                      language: 'en',
                      region: 'eg',
                      maxResultCount: 1
                    });
                    const place = (result.places || [])[0];
                    const position = place ? point(place.location) : null;
                    if (!place || !position) throw new Error('Destination not found');

                    showPlaceCard(place);
                    destinationMarker = new AdvancedMarkerClass({ map, position, title: text(place.displayName) });
                    destinationMarker.addListener('gmp-click', () => HyperNovaLauncherBridge.openNavigation());
                    document.getElementById('placeCard').onclick = () => HyperNovaLauncherBridge.openNavigation();

                    const routes = await RouteClass.computeRoutes({
                      origin: ORIGIN,
                      destination: new PlaceClass({ id: place.id }),
                      travelMode: 'DRIVING',
                      routingPreference: 'TRAFFIC_AWARE',
                      polylineQuality: 'HIGH_QUALITY',
                      fields: ['path', 'distanceMeters', 'durationMillis', 'viewport']
                    });
                    const route = (routes.routes || [])[0];
                    if (!route) {
                      map.setCenter(position);
                      map.setZoom(16);
                      return;
                    }
                    routePolylines = route.createPolylines();
                    routePolylines.forEach((polyline) => {
                      polyline.setOptions({ strokeColor: '#00A6B7', strokeOpacity: .96, strokeWeight: 7 });
                      polyline.setMap(map);
                    });
                    try { routeMarkers = await route.createWaypointAdvancedMarkers({ map }); } catch (_) { routeMarkers = []; }
                    if (route.viewport) map.fitBounds(route.viewport, { top: 112, right: 24, bottom: 26, left: 24 });
                  } catch (error) {
                    lastQuery = '';
                    HyperNovaLauncherBridge.onRenderFailed(String(error && error.message ? error.message : error));
                  }
                };
              </script>
              <script async src="https://maps.googleapis.com/maps/api/js?key=$encodedKey&amp;loading=async&amp;libraries=places,routes,marker&amp;callback=initHyperNovaLauncher&amp;v=weekly"></script>
            </head>
            <body>
              <div id="map" aria-label="Google Maps destination preview"></div>
              <div id="placeCard" role="button" aria-label="Open destination in HyperNova Navigation">
                <div id="placeName"></div><div id="placeMeta"></div><div id="placeRating"></div>
              </div>
            </body>
            </html>
        """.trimIndent()
    }
}
