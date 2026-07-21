package com.hypernova.navigation

import android.os.Bundle
import android.os.SystemClock
import android.text.InputType
import android.view.View
import android.view.WindowManager
import android.widget.LinearLayout
import android.widget.Toast
import androidx.activity.enableEdgeToEdge
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.textfield.TextInputEditText
import com.google.android.material.textfield.TextInputLayout
import com.hypernova.navigation.databinding.ActivityMainBinding
import org.json.JSONArray
import org.maplibre.android.MapLibre
import org.maplibre.android.camera.CameraPosition
import org.maplibre.android.camera.CameraUpdateFactory
import org.maplibre.android.geometry.LatLng
import org.maplibre.android.maps.MapLibreMap
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.nio.charset.StandardCharsets
import java.util.LinkedHashMap
import java.util.Locale
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding

    private var mapLibreMap: MapLibreMap? = null
    private var isMapReady = false
    private var isSearchInProgress = false

    private var searchDialog: AlertDialog? = null
    private var resultsDialog: AlertDialog? = null

    /*
     * A single background thread prevents parallel requests
     * to the public place search service.
     */
    private val searchExecutor: ExecutorService =
        Executors.newSingleThreadExecutor()

    /*
     * Keeps recent search results in memory.
     *
     * Searching for the same text again can reuse the cached result.
     */
    private val searchCache =
        object : LinkedHashMap<String, List<PlaceSearchResult>>(
            MAX_CACHED_SEARCHES,
            CACHE_LOAD_FACTOR,
            true
        ) {
            override fun removeEldestEntry(
                eldest: MutableMap.MutableEntry<
                        String,
                        List<PlaceSearchResult>
                        >?
            ): Boolean {
                return size > MAX_CACHED_SEARCHES
            }
        }

    /*
     * Accessed from the single search executor thread.
     */
    private var lastSearchRequestTimeMs = 0L

    private val mapLoadTimeout = Runnable {
        if (!isMapReady) {
            showMapError()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        enableEdgeToEdge()

        // MapLibre must be initialized before inflating MapView.
        MapLibre.getInstance(this)

        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        binding.mapView.onCreate(savedInstanceState)

        applySystemBarInsets()
        configureButtons()
        requestMap()
    }

    private fun applySystemBarInsets() {
        ViewCompat.setOnApplyWindowInsetsListener(
            binding.main
        ) { view, insets ->

            val systemBars = insets.getInsets(
                WindowInsetsCompat.Type.systemBars()
            )

            view.setPadding(
                systemBars.left,
                systemBars.top,
                systemBars.right,
                systemBars.bottom
            )

            insets
        }
    }

    private fun configureButtons() {
        binding.retryButton.setOnClickListener {
            requestMap()
        }

        binding.zoomInButton.setOnClickListener {
            mapLibreMap?.animateCamera(
                CameraUpdateFactory.zoomIn()
            )
        }

        binding.zoomOutButton.setOnClickListener {
            mapLibreMap?.animateCamera(
                CameraUpdateFactory.zoomOut()
            )
        }

        binding.resetCameraButton.setOnClickListener {
            moveCameraToCairo()
        }

        binding.searchDestinationCard.setOnClickListener {
            openSearchDialog()
        }

        binding.homeDestinationButton.setOnClickListener {
            showSavedDestinationUnavailable(
                destinationName = "Home"
            )
        }

        binding.workDestinationButton.setOnClickListener {
            showSavedDestinationUnavailable(
                destinationName = "Work"
            )
        }

        binding.chargingDestinationButton.setOnClickListener {
            openSearchDialog(
                initialQuery = "EV charging station"
            )
        }

        binding.recentDestinationButton.setOnClickListener {
            showRecentDestinationsUnavailable()
        }
    }

    private fun openSearchDialog(initialQuery: String = "") {
        if (isSearchInProgress) {
            Toast.makeText(
                this,
                "A destination search is already running",
                Toast.LENGTH_SHORT
            ).show()

            return
        }

        searchDialog?.dismiss()

        val inputContainer = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL

            setPadding(
                dpToPx(24),
                dpToPx(8),
                dpToPx(24),
                0
            )
        }

        val inputLayout = TextInputLayout(this).apply {
            hint = "Place, address or category"

            boxBackgroundMode =
                TextInputLayout.BOX_BACKGROUND_OUTLINE

            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
        }

        val destinationInput =
            TextInputEditText(inputLayout.context).apply {
                inputType =
                    InputType.TYPE_CLASS_TEXT or
                            InputType.TYPE_TEXT_FLAG_CAP_SENTENCES

                isSingleLine = true
                setText(initialQuery)

                if (initialQuery.isNotEmpty()) {
                    setSelection(initialQuery.length)
                }
            }

        inputLayout.addView(
            destinationInput,
            LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
        )

        inputContainer.addView(inputLayout)

        val dialog = MaterialAlertDialogBuilder(this)
            .setTitle("Search destination")
            .setMessage(
                "Enter a real place, address or category."
            )
            .setView(inputContainer)
            .setNegativeButton("Cancel", null)
            .setPositiveButton("Search", null)
            .create()

        dialog.setOnShowListener {
            destinationInput.requestFocus()

            dialog.window?.setSoftInputMode(
                WindowManager.LayoutParams
                    .SOFT_INPUT_STATE_ALWAYS_VISIBLE
            )

            dialog.getButton(AlertDialog.BUTTON_POSITIVE)
                .setOnClickListener {
                    val query = destinationInput.text
                        ?.toString()
                        ?.trim()
                        .orEmpty()

                    if (query.isBlank()) {
                        inputLayout.error =
                            "Enter a destination first"

                        return@setOnClickListener
                    }

                    inputLayout.error = null

                    dialog.dismiss()
                    searchForPlaces(query)
                }
        }

        dialog.setOnDismissListener {
            if (searchDialog === dialog) {
                searchDialog = null
            }
        }

        searchDialog = dialog
        dialog.show()
    }

    private fun searchForPlaces(query: String) {
        if (isSearchInProgress) {
            return
        }

        val cacheKey = query
            .lowercase(Locale.ROOT)
            .trim()

        val cachedResults = searchCache[cacheKey]

        if (cachedResults != null) {
            binding.searchDestinationText.text = query

            if (cachedResults.isEmpty()) {
                showNoSearchResults(query)
            } else {
                showPlaceResults(
                    query = query,
                    places = cachedResults
                )
            }

            return
        }

        isSearchInProgress = true
        showSearchLoading(query)

        searchExecutor.execute {
            waitForSearchRateLimit()

            val searchResult = runCatching {
                requestPlaceSearch(query)
            }

            runOnUiThread {
                if (isFinishing || isDestroyed) {
                    return@runOnUiThread
                }

                isSearchInProgress = false
                finishSearchLoading(query)

                searchResult
                    .onSuccess { places ->
                        searchCache[cacheKey] = places

                        if (places.isEmpty()) {
                            showNoSearchResults(query)
                        } else {
                            showPlaceResults(
                                query = query,
                                places = places
                            )
                        }
                    }
                    .onFailure { throwable ->
                        showSearchError(
                            query = query,
                            errorMessage =
                                throwable.message
                                    ?: "Unknown search error"
                        )
                    }
            }
        }
    }

    private fun waitForSearchRateLimit() {
        val currentTimeMs =
            SystemClock.elapsedRealtime()

        val elapsedTimeMs =
            currentTimeMs - lastSearchRequestTimeMs

        val remainingDelayMs =
            SEARCH_REQUEST_INTERVAL_MS - elapsedTimeMs

        if (remainingDelayMs > 0L) {
            Thread.sleep(remainingDelayMs)
        }

        lastSearchRequestTimeMs =
            SystemClock.elapsedRealtime()
    }

    private fun requestPlaceSearch(
        query: String
    ): List<PlaceSearchResult> {
        val encodedQuery = URLEncoder.encode(
            query,
            StandardCharsets.UTF_8.name()
        )

        val searchUrl =
            "$NOMINATIM_SEARCH_URL" +
                    "?format=jsonv2" +
                    "&addressdetails=1" +
                    "&dedupe=1" +
                    "&limit=$SEARCH_RESULT_LIMIT" +
                    "&q=$encodedQuery"

        val connection =
            URL(searchUrl).openConnection()
                    as HttpURLConnection

        return try {
            connection.requestMethod = "GET"
            connection.instanceFollowRedirects = true

            connection.connectTimeout =
                SEARCH_CONNECT_TIMEOUT_MS

            connection.readTimeout =
                SEARCH_READ_TIMEOUT_MS

            connection.setRequestProperty(
                "Accept",
                "application/json"
            )

            connection.setRequestProperty(
                "Accept-Language",
                Locale.getDefault().toLanguageTag()
            )

            connection.setRequestProperty(
                "User-Agent",
                NOMINATIM_USER_AGENT
            )

            val responseCode =
                connection.responseCode

            if (responseCode !in 200..299) {
                val errorBody = connection.errorStream
                    ?.bufferedReader()
                    ?.use { reader ->
                        reader.readText()
                    }
                    .orEmpty()

                val readableError =
                    if (errorBody.isBlank()) {
                        "HTTP $responseCode"
                    } else {
                        "HTTP $responseCode: " +
                                errorBody.take(
                                    MAX_ERROR_MESSAGE_LENGTH
                                )
                    }

                throw IOException(
                    "Place search failed: $readableError"
                )
            }

            val responseBody =
                connection.inputStream
                    .bufferedReader()
                    .use { reader ->
                        reader.readText()
                    }

            parsePlaceSearchResponse(responseBody)
        } finally {
            connection.disconnect()
        }
    }

    private fun parsePlaceSearchResponse(
        responseBody: String
    ): List<PlaceSearchResult> {
        val jsonArray = JSONArray(responseBody)
        val results = mutableListOf<PlaceSearchResult>()

        for (index in 0 until jsonArray.length()) {
            val jsonPlace =
                jsonArray.optJSONObject(index)
                    ?: continue

            val displayName =
                jsonPlace.optString("display_name")
                    .trim()

            val latitude =
                jsonPlace.optString("lat")
                    .toDoubleOrNull()

            val longitude =
                jsonPlace.optString("lon")
                    .toDoubleOrNull()

            if (
                displayName.isBlank() ||
                latitude == null ||
                longitude == null
            ) {
                continue
            }

            results += PlaceSearchResult(
                displayName = displayName,
                latitude = latitude,
                longitude = longitude,
                category = jsonPlace
                    .optString("category")
                    .trim(),
                type = jsonPlace
                    .optString("type")
                    .trim()
            )
        }

        return results
    }

    private fun showSearchLoading(query: String) {
        binding.searchDestinationCard.isEnabled = false
        binding.chargingDestinationButton.isEnabled = false

        binding.searchDestinationText.text =
            "Searching..."

        binding.routeStatusTitle.text =
            "Searching places"

        binding.routeStatusMessage.text =
            "Looking for \"$query\" using OpenStreetMap data."
    }

    private fun finishSearchLoading(query: String) {
        binding.searchDestinationCard.isEnabled = true
        binding.chargingDestinationButton.isEnabled = true

        binding.searchDestinationText.text = query
    }

    private fun showPlaceResults(
        query: String,
        places: List<PlaceSearchResult>
    ) {
        resultsDialog?.dismiss()

        /*
         * Do not call setMessage() together with setItems().
         *
         * The list itself is the dialog content.
         */
        val resultLabels = places
            .mapIndexed { index, place ->
                createPlaceResultLabel(
                    index = index,
                    place = place
                )
            }
            .toTypedArray()

        binding.routeStatusTitle.text =
            "Choose a destination"

        binding.routeStatusMessage.text =
            "${places.size} real results found for \"$query\"."

        val dialog = MaterialAlertDialogBuilder(this)
            .setTitle(
                "Search results (${places.size})"
            )
            .setItems(resultLabels) { _, selectedIndex ->
                selectPlace(
                    query = query,
                    place = places[selectedIndex]
                )
            }
            .setNeutralButton("Search again") { _, _ ->
                openSearchDialog(query)
            }
            .setNegativeButton("Cancel", null)
            .create()

        dialog.setOnDismissListener {
            if (resultsDialog === dialog) {
                resultsDialog = null
            }
        }

        resultsDialog = dialog
        dialog.show()
    }

    private fun createPlaceResultLabel(
        index: Int,
        place: PlaceSearchResult
    ): String {
        val primaryName = place.displayName
            .substringBefore(",")
            .trim()
            .ifBlank {
                "Unnamed place"
            }

        val remainingAddress = place.displayName
            .substringAfter(
                delimiter = ",",
                missingDelimiterValue = ""
            )
            .trim()

        return buildString {
            append(index + 1)
            append(". ")
            append(primaryName)

            if (remainingAddress.isNotBlank()) {
                append("\n")
                append(remainingAddress)
            }
        }
    }

    private fun selectPlace(
        query: String,
        place: PlaceSearchResult
    ) {
        val shortPlaceName =
            place.displayName
                .substringBefore(",")
                .trim()
                .ifBlank {
                    query
                }

        binding.searchDestinationText.text =
            shortPlaceName

        binding.routeStatusTitle.text =
            "Destination selected"

        binding.routeStatusMessage.text =
            place.displayName

        moveCameraToPlace(place)

        Toast.makeText(
            this,
            "Destination selected. Route calculation is the next step.",
            Toast.LENGTH_LONG
        ).show()
    }

    private fun moveCameraToPlace(
        place: PlaceSearchResult
    ) {
        val map = mapLibreMap ?: return

        val destinationCamera =
            CameraPosition.Builder()
                .target(
                    LatLng(
                        place.latitude,
                        place.longitude
                    )
                )
                .zoom(SEARCH_RESULT_ZOOM)
                .bearing(0.0)
                .tilt(0.0)
                .build()

        map.animateCamera(
            CameraUpdateFactory.newCameraPosition(
                destinationCamera
            ),
            CAMERA_ANIMATION_DURATION_MS
        )
    }

    private fun showNoSearchResults(query: String) {
        binding.routeStatusTitle.text =
            "No place found"

        binding.routeStatusMessage.text =
            "No result matched \"$query\"."

        MaterialAlertDialogBuilder(this)
            .setTitle("No results")
            .setMessage(
                "No OpenStreetMap place matched " +
                        "\"$query\". Try a more specific " +
                        "place name or address."
            )
            .setPositiveButton("Search again") { _, _ ->
                openSearchDialog(query)
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun showSearchError(
        query: String,
        errorMessage: String
    ) {
        binding.routeStatusTitle.text =
            "Search unavailable"

        binding.routeStatusMessage.text =
            "The online place search could not be completed."

        MaterialAlertDialogBuilder(this)
            .setTitle("Place search failed")
            .setMessage(errorMessage)
            .setPositiveButton("Retry") { _, _ ->
                searchForPlaces(query)
            }
            .setNeutralButton("Edit search") { _, _ ->
                openSearchDialog(query)
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun showSavedDestinationUnavailable(
        destinationName: String
    ) {
        binding.routeStatusTitle.text =
            "No active route"

        binding.routeStatusMessage.text =
            "$destinationName destination is not saved yet."

        Toast.makeText(
            this,
            "$destinationName is not configured yet",
            Toast.LENGTH_SHORT
        ).show()
    }

    private fun showRecentDestinationsUnavailable() {
        binding.routeStatusTitle.text =
            "No active route"

        binding.routeStatusMessage.text =
            "There are no recent destinations yet."

        Toast.makeText(
            this,
            "No recent destinations",
            Toast.LENGTH_SHORT
        ).show()
    }

    private fun requestMap() {
        isMapReady = false
        showMapLoading()

        binding.main.removeCallbacks(mapLoadTimeout)

        binding.main.postDelayed(
            mapLoadTimeout,
            MAP_LOAD_TIMEOUT_MS
        )

        val availableMap = mapLibreMap

        if (availableMap == null) {
            binding.mapView.getMapAsync { map ->
                mapLibreMap = map
                loadMapStyle(map)
            }
        } else {
            loadMapStyle(availableMap)
        }
    }

    private fun loadMapStyle(map: MapLibreMap) {
        map.setStyle(OPEN_FREE_MAP_STYLE_URL) {
            isMapReady = true

            binding.main.removeCallbacks(
                mapLoadTimeout
            )

            moveCameraToCairo()
            showMapReady()
        }
    }

    private fun moveCameraToCairo() {
        val map = mapLibreMap ?: return

        val cairoCamera =
            CameraPosition.Builder()
                .target(CAIRO_COORDINATES)
                .zoom(CAIRO_ZOOM)
                .bearing(0.0)
                .tilt(0.0)
                .build()

        map.animateCamera(
            CameraUpdateFactory.newCameraPosition(
                cairoCamera
            ),
            CAMERA_ANIMATION_DURATION_MS
        )
    }

    private fun showMapLoading() {
        binding.mapStateCard.visibility = View.VISIBLE
        binding.loadingIndicator.visibility = View.VISIBLE
        binding.retryButton.visibility = View.GONE

        binding.mapStateTitle.setText(
            R.string.map_loading_title
        )

        binding.mapStateMessage.setText(
            R.string.map_loading_message
        )

        binding.statusText.setText(
            R.string.map_status_loading
        )

        binding.onlineIndicator.visibility =
            View.VISIBLE

        binding.mapControls.visibility =
            View.GONE
    }

    private fun showMapReady() {
        binding.mapStateCard.visibility = View.GONE

        binding.statusText.setText(
            R.string.map_status_ready
        )

        binding.onlineIndicator.visibility =
            View.VISIBLE

        binding.mapControls.visibility =
            View.VISIBLE
    }

    private fun showMapError() {
        binding.mapStateCard.visibility = View.VISIBLE
        binding.loadingIndicator.visibility = View.GONE
        binding.retryButton.visibility = View.VISIBLE

        binding.mapStateTitle.setText(
            R.string.map_error_title
        )

        binding.mapStateMessage.setText(
            R.string.map_error_message
        )

        binding.statusText.setText(
            R.string.map_status_error
        )

        binding.onlineIndicator.visibility =
            View.GONE

        binding.mapControls.visibility =
            View.GONE
    }

    private fun dpToPx(dp: Int): Int {
        return (
                dp * resources.displayMetrics.density
                ).toInt()
    }

    override fun onStart() {
        super.onStart()
        binding.mapView.onStart()
    }

    override fun onResume() {
        super.onResume()
        binding.mapView.onResume()
    }

    override fun onPause() {
        binding.mapView.onPause()
        super.onPause()
    }

    override fun onStop() {
        binding.mapView.onStop()
        super.onStop()
    }

    override fun onLowMemory() {
        super.onLowMemory()
        binding.mapView.onLowMemory()
    }

    override fun onSaveInstanceState(outState: Bundle) {
        binding.mapView.onSaveInstanceState(outState)
        super.onSaveInstanceState(outState)
    }

    override fun onDestroy() {
        searchDialog?.dismiss()
        searchDialog = null

        resultsDialog?.dismiss()
        resultsDialog = null

        searchExecutor.shutdownNow()

        binding.main.removeCallbacks(mapLoadTimeout)
        binding.mapView.onDestroy()

        super.onDestroy()
    }

    private data class PlaceSearchResult(
        val displayName: String,
        val latitude: Double,
        val longitude: Double,
        val category: String,
        val type: String
    )

    companion object {

        // OpenFreeMap provides real OpenStreetMap-based map data.
        private const val OPEN_FREE_MAP_STYLE_URL =
            "https://tiles.openfreemap.org/styles/liberty"

        // Public development geocoding endpoint.
        private const val NOMINATIM_SEARCH_URL =
            "https://nominatim.openstreetmap.org/search"

        // Identifies this application to the search provider.
        private const val NOMINATIM_USER_AGENT =
            "HyperNovaNavigation/1.0 " +
                    "(com.hypernova.navigation; " +
                    "https://github.com/HyperNova-ITI/Android-Apps)"

        // Cairo coordinates: latitude, longitude.
        private val CAIRO_COORDINATES =
            LatLng(30.0444, 31.2357)

        private const val CAIRO_ZOOM = 12.5
        private const val SEARCH_RESULT_ZOOM = 15.5

        private const val CAMERA_ANIMATION_DURATION_MS =
            1000

        private const val MAP_LOAD_TIMEOUT_MS =
            15000L

        private const val SEARCH_CONNECT_TIMEOUT_MS =
            10000

        private const val SEARCH_READ_TIMEOUT_MS =
            15000

        private const val SEARCH_REQUEST_INTERVAL_MS =
            1100L

        private const val SEARCH_RESULT_LIMIT =
            5

        private const val MAX_CACHED_SEARCHES =
            20

        private const val CACHE_LOAD_FACTOR =
            0.75f

        private const val MAX_ERROR_MESSAGE_LENGTH =
            250
    }
}