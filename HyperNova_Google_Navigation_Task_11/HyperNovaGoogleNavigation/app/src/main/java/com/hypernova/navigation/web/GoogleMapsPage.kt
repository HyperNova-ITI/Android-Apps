package com.hypernova.navigation.web

import java.net.URLEncoder
import java.nio.charset.StandardCharsets

internal object GoogleMapsPage {
    const val DOCUMENT_ORIGIN = "https://nova.hypernova.local/"

    fun render(apiKey: String): String {
        val encodedKey = URLEncoder.encode(apiKey, StandardCharsets.UTF_8.name())
        return """
            <!doctype html>
            <html lang="en">
            <head>
              <meta charset="utf-8">
              <meta name="viewport" content="width=device-width, initial-scale=1, maximum-scale=1, user-scalable=no">
              <meta http-equiv="Content-Security-Policy" content="default-src 'none'; script-src 'unsafe-inline' 'unsafe-eval' https://maps.googleapis.com https://maps.gstatic.com; style-src 'unsafe-inline' https://fonts.googleapis.com; font-src https://fonts.gstatic.com; img-src data: blob: https://*.googleapis.com https://*.gstatic.com https://*.google.com https://*.googleusercontent.com; connect-src https://*.googleapis.com https://*.gstatic.com https://*.google.com; worker-src blob:">
              <style>
                html, body { width: 100%; height: 100%; margin: 0; overflow: hidden; background: #07121d; }
                #map { position: absolute; left: 0; right: 0; top: 0; bottom: 0; background: #07121d; }
              </style>
              <script>
                'use strict';
                let map;
                let PlaceClass;
                let RouteClass;
                let AdvancedMarkerClass;
                let searchMarkers = [];
                let routeMarkers = [];
                let routePolylines = [];
                let lastRouteBounds = null;
                let topInset = 0;
                let bottomInset = 0;

                function text(value) {
                  if (value == null) return '';
                  if (typeof value === 'string') return value;
                  if (typeof value.text === 'string') return value.text;
                  return String(value);
                }

                function point(value) {
                  if (!value) return null;
                  const latitude = typeof value.lat === 'function' ? value.lat() : value.lat;
                  const longitude = typeof value.lng === 'function' ? value.lng() : value.lng;
                  if (!Number.isFinite(latitude) || !Number.isFinite(longitude)) return null;
                  return { latitude, longitude };
                }

                function respond(requestId, operation, ok, payload, errorCode, message) {
                  HyperNovaBridge.onResponse(
                    requestId,
                    operation,
                    ok,
                    JSON.stringify(payload == null ? {} : payload),
                    errorCode || '',
                    message || ''
                  );
                }

                function classifyError(error) {
                  const message = error && error.message ? String(error.message) : String(error || 'Google Maps request failed.');
                  if (/api.?key|not.?authorized|permission|denied|billing|quota/i.test(message)) return ['AUTHORIZATION', message];
                  if (/network|fetch|offline|connection|load/i.test(message)) return ['NETWORK', message];
                  return ['INTERNAL', message];
                }

                function clearObjects(values, property) {
                  values.forEach((value) => {
                    try {
                      if (property === 'map') value.map = null;
                      else value.setMap(null);
                    } catch (_) { }
                  });
                  values.length = 0;
                }

                function fitRoute() {
                  if (!map || !lastRouteBounds) return;
                  map.fitBounds(lastRouteBounds, {
                    top: topInset + 28,
                    right: 32,
                    bottom: bottomInset + 28,
                    left: 32
                  });
                }

                window.gm_authFailure = function() {
                  HyperNovaBridge.onInitializationFailed('AUTHORIZATION', 'Google Maps rejected the browser API key.');
                };

                window.initHyperNova = async function() {
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
                      center: { lat: 30.07112, lng: 31.02075 },
                      zoom: 14,
                      mapId: 'DEMO_MAP_ID',
                      mapTypeControl: false,
                      fullscreenControl: false,
                      streetViewControl: false,
                      clickableIcons: false,
                      gestureHandling: 'greedy'
                    });
                    HyperNovaBridge.onReady();
                  } catch (error) {
                    const classified = classifyError(error);
                    HyperNovaBridge.onInitializationFailed(classified[0], classified[1]);
                  }
                };

                window.hypernovaSearch = async function(requestId, query, latitude, longitude) {
                  try {
                    const origin = { lat: latitude, lng: longitude };
                    const result = await PlaceClass.searchByText({
                      textQuery: query,
                      fields: ['id', 'displayName', 'formattedAddress', 'primaryTypeDisplayName', 'location'],
                      locationBias: origin,
                      language: 'en',
                      region: 'eg',
                      maxResultCount: 4
                    });
                    const places = (result.places || []).slice(0, 4);
                    clearObjects(searchMarkers, 'map');
                    const bounds = new google.maps.LatLngBounds();
                    const payload = [];
                    places.forEach((place) => {
                      const location = point(place.location);
                      payload.push({
                        placeId: place.id || '',
                        title: text(place.displayName),
                        subtitle: text(place.formattedAddress),
                        category: text(place.primaryTypeDisplayName),
                        latitude: location ? location.latitude : null,
                        longitude: location ? location.longitude : null
                      });
                      if (location) {
                        const position = { lat: location.latitude, lng: location.longitude };
                        bounds.extend(position);
                        searchMarkers.push(new AdvancedMarkerClass({ map, position, title: text(place.displayName) }));
                      }
                    });
                    if (!bounds.isEmpty()) map.fitBounds(bounds, 72);
                    respond(requestId, 'search', true, payload, '', '');
                  } catch (error) {
                    const classified = classifyError(error);
                    respond(requestId, 'search', false, {}, classified[0], classified[1]);
                  }
                };

                window.hypernovaRoute = async function(requestId, destination, originLatitude, originLongitude) {
                  try {
                    const destinationPlace = new PlaceClass({ id: destination.placeId });
                    const result = await RouteClass.computeRoutes({
                      origin: { lat: originLatitude, lng: originLongitude },
                      destination: destinationPlace,
                      travelMode: 'DRIVING',
                      routingPreference: 'TRAFFIC_AWARE',
                      polylineQuality: 'HIGH_QUALITY',
                      fields: ['path', 'distanceMeters', 'durationMillis', 'viewport']
                    });
                    if (!result.routes || result.routes.length === 0) {
                      respond(requestId, 'route', false, {}, 'NO_ROUTE', 'Google could not find a route.');
                      return;
                    }
                    const route = result.routes[0];
                    const path = (route.path || []).map(point).filter(Boolean);
                    if (path.length < 2) {
                      respond(requestId, 'route', false, {}, 'NO_ROUTE', 'Google route geometry is unavailable.');
                      return;
                    }
                    clearObjects(searchMarkers, 'map');
                    clearObjects(routePolylines, 'setMap');
                    clearObjects(routeMarkers, 'map');
                    routePolylines = route.createPolylines();
                    routePolylines.forEach((polyline) => {
                      polyline.setOptions({ strokeColor: '#19D3C5', strokeOpacity: 0.96, strokeWeight: 8 });
                      polyline.setMap(map);
                    });
                    try {
                      routeMarkers = await route.createWaypointAdvancedMarkers({ map });
                    } catch (_) {
                      routeMarkers = [];
                    }
                    lastRouteBounds = route.viewport || null;
                    fitRoute();
                    respond(requestId, 'route', true, {
                      points: path,
                      etaSeconds: Math.max(-1, Math.round((route.durationMillis || -1000) / 1000)),
                      distanceMeters: Math.max(-1, Math.round(route.distanceMeters || -1))
                    }, '', '');
                  } catch (error) {
                    const classified = classifyError(error);
                    respond(requestId, 'route', false, {}, classified[0], classified[1]);
                  }
                };

                window.hypernovaCancelRoute = function() {
                  clearObjects(routePolylines, 'setMap');
                  clearObjects(routeMarkers, 'map');
                  lastRouteBounds = null;
                };

                window.hypernovaSetInsets = function(top, bottom) {
                  topInset = Math.max(0, Number(top) || 0);
                  bottomInset = Math.max(0, Number(bottom) || 0);
                  fitRoute();
                };
              </script>
              <script async src="https://maps.googleapis.com/maps/api/js?key=$encodedKey&amp;loading=async&amp;libraries=places,routes,marker&amp;callback=initHyperNova&amp;v=weekly"></script>
            </head>
            <body><div id="map" aria-label="Google Maps route display"></div></body>
            </html>
        """.trimIndent()
    }
}
