package com.hypernova.navigation.web

import java.net.URLEncoder
import java.nio.charset.StandardCharsets

internal object GoogleMapsPage {
    const val DOCUMENT_ORIGIN = "https://nova.hypernova.local/"
    const val REQUESTED_CUSTOM_MAP_ID = "20d0a8fe56e67ae4e0d3323d"
    const val MAP_ID = REQUESTED_CUSTOM_MAP_ID

    fun render(apiKey: String, isNightMode: Boolean = false): String {
        val encodedKey = URLEncoder.encode(apiKey, StandardCharsets.UTF_8.name())
        val colorScheme = if (isNightMode) "DARK" else "LIGHT"
        val pageBackground = if (isNightMode) "#07121d" else "#edf5f7"
        val panelBackground = if (isNightMode) "rgba(5, 20, 31, .96)" else "rgba(250, 253, 254, .97)"
        val primaryText = if (isNightMode) "#f2fbfd" else "#092331"
        val secondaryText = if (isNightMode) "#a9c3cc" else "#46616e"
        val softSurface = if (isNightMode) "rgba(255, 255, 255, .06)" else "rgba(9, 35, 49, .06)"
        val reviewText = if (isNightMode) "#d6e6eb" else "#173542"
        val attributionText = if (isNightMode) "#78949e" else "#6b8792"
        val shadow = if (isNightMode) "rgba(0, 0, 0, .48)" else "rgba(4, 37, 52, .20)"
        return """
            <!doctype html>
            <html lang="en">
            <head>
              <meta charset="utf-8">
              <meta name="viewport" content="width=device-width, initial-scale=1, maximum-scale=1, user-scalable=no">
              <meta http-equiv="Content-Security-Policy" content="default-src 'none'; script-src 'unsafe-inline' 'unsafe-eval' https://maps.googleapis.com https://maps.gstatic.com; style-src 'unsafe-inline' https://fonts.googleapis.com; font-src https://fonts.gstatic.com; img-src data: blob: https://*.googleapis.com https://*.gstatic.com https://*.google.com https://*.googleusercontent.com; connect-src data: https://*.googleapis.com https://*.gstatic.com https://*.google.com; worker-src blob:">
              <style>
                :root {
                  --nova-teal: #19d3c5;
                  --nova-cyan: #4be7f3;
                  --nova-panel: $panelBackground;
                  --nova-line: rgba(75, 231, 243, .28);
                  --nova-primary: $primaryText;
                  --nova-secondary: $secondaryText;
                  --nova-soft-surface: $softSurface;
                  --nova-page: $pageBackground;
                  --nova-shadow: $shadow;
                }
                * { box-sizing: border-box; }
                html, body { width: 100%; height: 100%; margin: 0; overflow: hidden; background: var(--nova-page); font-family: Arial, sans-serif; }
                button { font: inherit; }
                #map { position: absolute; inset: 0; background: var(--nova-page); }
                #placeSheet {
                  position: absolute; z-index: 8; left: 18px; right: 18px; bottom: 18px;
                  max-height: 48%; padding: 17px 18px 16px; overflow-y: auto;
                  border: 1px solid var(--nova-line); border-radius: 22px;
                  background: var(--nova-panel); color: var(--nova-primary);
                  box-shadow: 0 18px 48px var(--nova-shadow);
                  opacity: 0; visibility: hidden; pointer-events: none;
                  transform: translateY(24px); transition: opacity .18s ease, transform .18s ease, visibility .18s;
                }
                #placeSheet.visible { opacity: 1; visibility: visible; pointer-events: auto; transform: translateY(0); }
                #sheetHandle { width: 42px; height: 4px; margin: -4px auto 11px; border-radius: 4px; background: rgba(169, 195, 204, .42); }
                #placeClose {
                  position: absolute; right: 13px; top: 12px; width: 40px; height: 40px;
                  border: 0; border-radius: 20px; background: var(--nova-soft-surface);
                  color: var(--nova-primary); font-size: 25px; line-height: 40px;
                }
                #placeEyebrow { padding-right: 44px; color: var(--nova-teal); font-size: 11px; font-weight: 800; letter-spacing: 1.7px; }
                #placeName { margin-top: 5px; padding-right: 44px; font-size: 22px; line-height: 1.16; font-weight: 750; }
                #placeMeta { margin-top: 7px; color: var(--nova-secondary); font-size: 13px; line-height: 1.4; }
                #placeRating { margin-top: 8px; color: var(--nova-cyan); font-size: 14px; font-weight: 750; }
                #reviewsPanel { display: none; margin-top: 13px; }
                #reviewsPanel.visible { display: block; }
                .reviewCard { margin-top: 9px; padding: 11px 12px; border: 1px solid rgba(169, 195, 204, .16); border-radius: 14px; background: var(--nova-soft-surface); }
                .reviewHeader { display: flex; justify-content: space-between; gap: 10px; color: var(--nova-primary); font-size: 12px; font-weight: 750; }
                .reviewTime { color: var(--nova-secondary); font-weight: 500; }
                .reviewText { margin-top: 6px; color: $reviewText; font-size: 12px; line-height: 1.42; display: -webkit-box; -webkit-line-clamp: 3; -webkit-box-orient: vertical; overflow: hidden; }
                #placeAttribution { margin-top: 9px; color: $attributionText; font-size: 10px; }
                #sheetActions { display: flex; gap: 11px; margin-top: 14px; }
                .sheetButton {
                  min-height: 50px; flex: 1; border-radius: 16px; border: 1px solid rgba(75, 231, 243, .42);
                  background: transparent; color: var(--nova-cyan); font-size: 13px; font-weight: 850; letter-spacing: .8px;
                }
                .sheetButton.primary { border: 0; background: linear-gradient(135deg, var(--nova-teal), #1aa3be); color: #021116; box-shadow: 0 8px 24px rgba(25, 211, 197, .22); }
                .sheetButton:disabled { opacity: .48; }
                #recenterButton {
                  position: absolute; z-index: 7; right: 18px; bottom: 18px; width: 54px; height: 54px;
                  border: 1px solid var(--nova-line); border-radius: 18px; background: var(--nova-panel);
                  color: var(--nova-cyan); box-shadow: 0 8px 26px rgba(0, 0, 0, .36); font-size: 26px;
                  transition: bottom .18s ease;
                }
                .vehiclePuck {
                  position: relative; width: 28px; height: 28px; border: 3px solid #efffff; border-radius: 50%;
                  background: var(--nova-teal); box-shadow: 0 0 0 8px rgba(25, 211, 197, .18), 0 4px 15px rgba(0, 0, 0, .52);
                }
                .vehiclePuck::before {
                  content: ''; position: absolute; inset: -12px; border: 2px solid rgba(75, 231, 243, .34); border-radius: 50%;
                  opacity: .46;
                }
                .vehiclePuck::after {
                  content: ''; position: absolute; left: 8px; top: 5px; width: 7px; height: 10px;
                  border-radius: 5px 5px 7px 7px; background: #04202a;
                }
                @keyframes novaLocationPulse { 0% { transform: scale(.72); opacity: .75; } 75%, 100% { transform: scale(1.45); opacity: 0; } }
              </style>
              <script>
                'use strict';
                let map;
                let PlaceClass;
                let RouteClass;
                let AdvancedMarkerClass;
                let placeLibraryPromise = null;
                let routeLibraryPromise = null;
                let searchMarkers = [];
                let routeMarkers = [];
                let routePolylines = [];
                let vehicleMarker = null;
                let lastRouteBounds = null;
                let lastRoutePath = [];
                let lastRouteDurationSeconds = -1;
                let lastRouteDistanceMeters = -1;
                let guidanceTimer = null;
                let topInset = 0;
                let bottomInset = 0;
                let currentSelectedPlace = null;
                let selectedPlaceGeneration = 0;
                let reviewsPlaceId = '';
                const demoOrigin = Object.freeze({ lat: 30.07112, lng: 31.02075 });

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

                async function ensurePlacesLibrary() {
                  if (PlaceClass) return PlaceClass;
                  if (!placeLibraryPromise) {
                    placeLibraryPromise = google.maps.importLibrary('places');
                  }
                  PlaceClass = (await placeLibraryPromise).Place;
                  return PlaceClass;
                }

                async function ensureRoutesLibrary() {
                  if (RouteClass) return RouteClass;
                  if (!routeLibraryPromise) {
                    routeLibraryPromise = google.maps.importLibrary('routes');
                  }
                  RouteClass = (await routeLibraryPromise).Route;
                  return RouteClass;
                }

                function renderProviderAttributions(place) {
                  const names = (place.attributions || [])
                    .map((attribution) => text(attribution && attribution.provider))
                    .filter(Boolean);
                  document.getElementById('placeAttribution').textContent =
                    names.length > 0 ? 'Place data: ' + names.join(', ') : 'Place data by Google Maps';
                }

                function renderPlaceSheet(place) {
                  const name = text(place.displayName);
                  const category = text(place.primaryTypeDisplayName);
                  const address = text(place.formattedAddress);
                  const rating = Number(place.rating || 0);
                  const count = Number(place.userRatingCount || 0);
                  document.getElementById('placeName').textContent = name;
                  document.getElementById('placeMeta').textContent = [category, address].filter(Boolean).join(' · ');
                  document.getElementById('placeRating').textContent =
                    rating > 0
                      ? '★ ' + rating.toFixed(1) + (count > 0 ? '  ·  ' + count.toLocaleString() + ' reviews' : '')
                      : 'Google Maps destination';
                  renderProviderAttributions(place);
                  document.getElementById('reviewsPanel').classList.remove('visible');
                  document.getElementById('reviewsPanel').replaceChildren();
                  document.getElementById('reviewsButton').textContent = 'REVIEWS';
                  document.getElementById('reviewsButton').disabled = false;
                  document.getElementById('startPlaceButton').textContent = 'START';
                  document.getElementById('startPlaceButton').disabled = !place.id || !name;
                  document.getElementById('placeSheet').classList.add('visible');
                  positionOverlays();
                }

                function hidePlaceSheet(clearSelection) {
                  document.getElementById('placeSheet').classList.remove('visible');
                  if (clearSelection !== false) currentSelectedPlace = null;
                  positionOverlays();
                }

                function positionOverlays() {
                  const sheet = document.getElementById('placeSheet');
                  const visible = sheet.classList.contains('visible');
                  sheet.style.bottom = (bottomInset + 18) + 'px';
                  const sheetClearance = visible ? sheet.offsetHeight + 16 : 0;
                  document.getElementById('recenterButton').style.bottom =
                    (bottomInset + sheetClearance + 18) + 'px';
                }

                function recenterOnVehicle() {
                  if (!map) return;
                  map.panTo(demoOrigin);
                  map.setZoom(15);
                }

                function createVehiclePuck() {
                  const content = document.createElement('div');
                  content.className = 'vehiclePuck';
                  vehicleMarker = new AdvancedMarkerClass({
                    map,
                    position: demoOrigin,
                    title: 'Current location · ITI Smart Village',
                    content,
                    zIndex: 1000
                  });
                  return vehicleMarker;
                }

                function destinationPayload(place) {
                  const location = point(place.location);
                  return {
                    placeId: text(place.id),
                    title: text(place.displayName),
                    subtitle: text(place.formattedAddress),
                    category: text(place.primaryTypeDisplayName),
                    latitude: location ? location.latitude : null,
                    longitude: location ? location.longitude : null
                  };
                }

                async function selectPlace(place) {
                  const generation = ++selectedPlaceGeneration;
                  try {
                    if (!place.displayName) {
                      await place.fetchFields({
                        fields: ['id', 'displayName', 'formattedAddress', 'primaryTypeDisplayName', 'location', 'rating', 'userRatingCount']
                      });
                    }
                    if (generation !== selectedPlaceGeneration) return;
                    currentSelectedPlace = place;
                    reviewsPlaceId = '';
                    const location = point(place.location);
                    if (location) map.panTo({ lat: location.latitude, lng: location.longitude });
                    renderPlaceSheet(place);
                  } catch (error) {
                    console.warn('Place details unavailable', error);
                  }
                }

                async function selectPlaceId(placeId) {
                  if (!placeId) return;
                  await ensurePlacesLibrary();
                  await selectPlace(new PlaceClass({ id: placeId }));
                }

                function startSelectedPlace() {
                  if (!currentSelectedPlace) return;
                  const payload = destinationPayload(currentSelectedPlace);
                  if (!payload.placeId || !payload.title) return;
                  document.getElementById('startPlaceButton').disabled = true;
                  document.getElementById('startPlaceButton').textContent = 'CALCULATING';
                  HyperNovaBridge.onDestinationRequested(JSON.stringify(payload));
                  hidePlaceSheet(false);
                }

                function addReviewCard(container, review) {
                  const card = document.createElement('div');
                  card.className = 'reviewCard';
                  const header = document.createElement('div');
                  header.className = 'reviewHeader';
                  const author = document.createElement('span');
                  author.textContent = text(review.authorAttribution && review.authorAttribution.displayName) || 'Google Maps user';
                  const detail = document.createElement('span');
                  detail.className = 'reviewTime';
                  const rating = Number(review.rating || 0);
                  detail.textContent = (rating > 0 ? '★ ' + rating.toFixed(1) : '') +
                    (review.relativePublishTimeDescription ? ' · ' + review.relativePublishTimeDescription : '');
                  header.append(author, detail);
                  const body = document.createElement('div');
                  body.className = 'reviewText';
                  body.textContent = text(review.text) || 'No written comment.';
                  card.append(header, body);
                  container.appendChild(card);
                }

                async function toggleReviews() {
                  if (!currentSelectedPlace || !currentSelectedPlace.id) return;
                  await ensurePlacesLibrary();
                  const panel = document.getElementById('reviewsPanel');
                  const button = document.getElementById('reviewsButton');
                  if (reviewsPlaceId === currentSelectedPlace.id) {
                    const opening = !panel.classList.contains('visible');
                    panel.classList.toggle('visible', opening);
                    button.textContent = opening ? 'HIDE REVIEWS' : 'REVIEWS';
                    positionOverlays();
                    return;
                  }
                  const requestedId = currentSelectedPlace.id;
                  button.disabled = true;
                  button.textContent = 'LOADING';
                  try {
                    const details = new PlaceClass({ id: requestedId });
                    await details.fetchFields({ fields: ['displayName', 'reviews'] });
                    if (!currentSelectedPlace || currentSelectedPlace.id !== requestedId) return;
                    panel.replaceChildren();
                    const reviews = (details.reviews || []).slice(0, 2);
                    if (reviews.length === 0) {
                      const empty = document.createElement('div');
                      empty.className = 'reviewCard';
                      empty.textContent = 'No reviews are available for this place.';
                      panel.appendChild(empty);
                    } else {
                      reviews.forEach((review) => addReviewCard(panel, review));
                    }
                    renderProviderAttributions(details);
                    reviewsPlaceId = requestedId;
                    panel.classList.add('visible');
                    button.textContent = 'HIDE REVIEWS';
                  } catch (error) {
                    panel.replaceChildren();
                    const failure = document.createElement('div');
                    failure.className = 'reviewCard';
                    failure.textContent = 'Reviews are unavailable right now.';
                    panel.appendChild(failure);
                    panel.classList.add('visible');
                    button.textContent = 'RETRY REVIEWS';
                  } finally {
                    button.disabled = false;
                    positionOverlays();
                  }
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

                function stopGuidanceTimer() {
                  if (guidanceTimer != null) clearInterval(guidanceTimer);
                  guidanceTimer = null;
                }

                function bearingBetween(first, second) {
                  const lat1 = first.latitude * Math.PI / 180;
                  const lat2 = second.latitude * Math.PI / 180;
                  const delta = (second.longitude - first.longitude) * Math.PI / 180;
                  const y = Math.sin(delta) * Math.cos(lat2);
                  const x = Math.cos(lat1) * Math.sin(lat2) -
                    Math.sin(lat1) * Math.cos(lat2) * Math.cos(delta);
                  return (Math.atan2(y, x) * 180 / Math.PI + 360) % 360;
                }

                function publishGuidanceSample(tick, totalTicks) {
                  const fraction = Math.min(1, tick / totalTicks);
                  const pathIndex = Math.min(
                    lastRoutePath.length - 1,
                    Math.round(fraction * (lastRoutePath.length - 1))
                  );
                  const current = lastRoutePath[pathIndex];
                  const next = lastRoutePath[Math.min(lastRoutePath.length - 1, pathIndex + 1)];
                  const bearing = bearingBetween(current, next);
                  const position = { lat: current.latitude, lng: current.longitude };
                  if (vehicleMarker) vehicleMarker.position = position;
                  if (map) {
                    map.panTo(position);
                    if (map.getZoom() < 16) map.setZoom(16);
                  }
                  const eta = Math.max(0, Math.round(lastRouteDurationSeconds * (1 - fraction)));
                  const remaining = Math.max(0, Math.round(lastRouteDistanceMeters * (1 - fraction)));
                  const speed = lastRouteDurationSeconds > 0
                    ? lastRouteDistanceMeters / lastRouteDurationSeconds
                    : 0;
                  HyperNovaBridge.onGuidanceProgress(
                    eta,
                    remaining,
                    current.latitude,
                    current.longitude,
                    bearing,
                    speed
                  );
                  if (fraction >= 1) {
                    stopGuidanceTimer();
                    HyperNovaBridge.onGuidanceArrival();
                  }
                }

                window.gm_authFailure = function() {
                  HyperNovaBridge.onInitializationFailed('AUTHORIZATION', 'Google Maps rejected the browser API key.');
                };

                window.initHyperNova = async function() {
                  try {
                    const libraries = await Promise.all([
                      google.maps.importLibrary('maps'),
                      google.maps.importLibrary('marker')
                    ]);
                    const MapClass = libraries[0].Map;
                    AdvancedMarkerClass = libraries[1].AdvancedMarkerElement;
                    map = new MapClass(document.getElementById('map'), {
                      center: demoOrigin,
                      zoom: 15,
                      mapId: '$MAP_ID',
                      colorScheme: '$colorScheme',
                      disableDefaultUI: true,
                      clickableIcons: true,
                      gestureHandling: 'greedy'
                    });
                    createVehiclePuck();
                    map.addListener('click', (event) => {
                      if (event && typeof event.stop === 'function') event.stop();
                      if (event && event.placeId) void selectPlaceId(event.placeId);
                      else hidePlaceSheet(true);
                    });
                    document.getElementById('recenterButton').addEventListener('click', recenterOnVehicle);
                    document.getElementById('placeClose').addEventListener('click', () => hidePlaceSheet(true));
                    document.getElementById('reviewsButton').addEventListener('click', () => void toggleReviews());
                    document.getElementById('startPlaceButton').addEventListener('click', startSelectedPlace);
                    HyperNovaBridge.onReady();
                  } catch (error) {
                    const classified = classifyError(error);
                    HyperNovaBridge.onInitializationFailed(classified[0], classified[1]);
                  }
                };

                window.hypernovaSearch = async function(requestId, query, latitude, longitude) {
                  try {
                    await ensurePlacesLibrary();
                    const origin = { lat: latitude, lng: longitude };
                    const result = await PlaceClass.searchByText({
                      textQuery: query,
                      fields: ['id', 'displayName', 'formattedAddress', 'primaryTypeDisplayName', 'location', 'rating', 'userRatingCount'],
                      locationBias: origin,
                      language: 'en',
                      region: 'eg',
                      maxResultCount: 4
                    });
                    const places = (result.places || []).slice(0, 4);
                    clearObjects(searchMarkers, 'map');
                    hidePlaceSheet(true);
                    const bounds = new google.maps.LatLngBounds();
                    bounds.extend(demoOrigin);
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
                        const marker = new AdvancedMarkerClass({
                          map,
                          position,
                          title: text(place.displayName),
                          gmpClickable: true
                        });
                        marker.addEventListener('gmp-click', () => void selectPlace(place));
                        searchMarkers.push(marker);
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
                    await Promise.all([ensurePlacesLibrary(), ensureRoutesLibrary()]);
                    stopGuidanceTimer();
                    const destinationPlace = new PlaceClass({ id: destination.placeId });
                    await destinationPlace.fetchFields({ fields: ['location'] });
                    hidePlaceSheet(false);
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
                    lastRoutePath = path;
                    lastRouteDurationSeconds = Math.max(0, Math.round((route.durationMillis || 0) / 1000));
                    lastRouteDistanceMeters = Math.max(0, Math.round(route.distanceMeters || 0));
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
                  stopGuidanceTimer();
                  clearObjects(routePolylines, 'setMap');
                  clearObjects(routeMarkers, 'map');
                  lastRouteBounds = null;
                  lastRoutePath = [];
                  lastRouteDurationSeconds = -1;
                  lastRouteDistanceMeters = -1;
                  if (vehicleMarker) vehicleMarker.position = demoOrigin;
                  hidePlaceSheet(true);
                  recenterOnVehicle();
                };

                window.hypernovaStartGuidance = function() {
                  if (!map || lastRoutePath.length < 2) return false;
                  stopGuidanceTimer();
                  /*
                   * The NXP guest has no Google Navigation SDK. This deterministic,
                   * route-generic demo follows the real Google route while keeping the
                   * update rate at 1 Hz. It is explicitly surfaced as simulated whenever
                   * the fixed ITI origin is in use.
                   */
                  const totalTicks = Math.max(
                    20,
                    Math.min(60, Math.ceil(Math.max(1, lastRouteDurationSeconds) / 30))
                  );
                  let tick = 0;
                  publishGuidanceSample(tick, totalTicks);
                  guidanceTimer = setInterval(() => {
                    tick += 1;
                    publishGuidanceSample(tick, totalTicks);
                  }, 1000);
                  return true;
                };

                window.hypernovaSetInsets = function(top, bottom) {
                  topInset = Math.max(0, Number(top) || 0);
                  bottomInset = Math.max(0, Number(bottom) || 0);
                  positionOverlays();
                  fitRoute();
                };

                window.hypernovaSurfaceAttached = function() {
                  if (!map) return;
                  requestAnimationFrame(() => {
                    google.maps.event.trigger(map, 'resize');
                    if (lastRoutePath.length >= 2) fitRoute();
                    else recenterOnVehicle();
                  });
                };
              </script>
              <script async src="https://maps.googleapis.com/maps/api/js?key=$encodedKey&amp;loading=async&amp;callback=initHyperNova&amp;v=quarterly"></script>
            </head>
            <body>
              <div id="map" aria-label="Google Maps route display"></div>
              <button id="recenterButton" aria-label="Recenter on current location">◎</button>
              <section id="placeSheet" aria-label="Selected Google Maps place details">
                <div id="sheetHandle"></div>
                <button id="placeClose" aria-label="Close place details">×</button>
                <div id="placeEyebrow">SELECTED PLACE</div>
                <div id="placeName"></div>
                <div id="placeMeta"></div>
                <div id="placeRating"></div>
                <div id="reviewsPanel"></div>
                <div id="placeAttribution">Place data by Google Maps</div>
                <div id="sheetActions">
                  <button id="reviewsButton" class="sheetButton">REVIEWS</button>
                  <button id="startPlaceButton" class="sheetButton primary">START</button>
                </div>
              </section>
            </body>
            </html>
        """.trimIndent()
    }
}
