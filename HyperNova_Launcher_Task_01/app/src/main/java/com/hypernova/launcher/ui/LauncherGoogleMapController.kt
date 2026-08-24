package com.hypernova.launcher.ui

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Bitmap
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.webkit.JavascriptInterface
import android.webkit.WebChromeClient
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import com.hypernova.launcher.core.navigation.NavigationPreviewPoint
import org.json.JSONArray
import org.json.JSONObject

/** Small Google map renderer for HOME; it never sends a navigation command. */
class LauncherGoogleMapController(
    context: Context,
    apiKey: String,
    isNightMode: Boolean,
    private val onOpenNavigation: () -> Unit,
    private val onAvailabilityChanged: (Boolean) -> Unit,
) {
    private val mainHandler = Handler(Looper.getMainLooper())
    val view: WebView = createWebView(context, isNightMode)
    private var ready = false
    private var destroyed = false
    private var pendingRouteKey = ""
    private var pendingRoutePoints: List<NavigationPreviewPoint> = emptyList()
    private var renderedRouteKey: String? = null

    init {
        // Keep the WebView laid out and drawable while Google Maps initializes. On the NXP
        // Chromium build an INVISIBLE map may never raster its first tiles, which means the
        // tilesloaded callback never fires and the widget remains permanently hidden. The view
        // already has an opaque cockpit-colored backing, so showing it immediately is clean.
        view.visibility = android.view.View.VISIBLE
        view.loadDataWithBaseURL(
            LauncherGoogleMapsPage.DOCUMENT_ORIGIN,
            LauncherGoogleMapsPage.render(apiKey, isNightMode),
            "text/html",
            "UTF-8",
            null,
        )
    }

    /**
     * Render Navigation's already-computed, bounded route geometry.
     *
     * HOME must never repeat a Places search or Routes request just to draw its widget. Navigation
     * remains the sole route authority and this small raster map only mirrors the resulting path.
     */
    fun setRoute(
        routeId: String,
        routeVersion: Long,
        routePoints: List<NavigationPreviewPoint>,
    ) {
        pendingRouteKey =
            routeId
                .takeIf { routePoints.size >= 2 }
                ?.let { "$it@$routeVersion" }
                .orEmpty()
        pendingRoutePoints = routePoints.takeIf { pendingRouteKey.isNotBlank() }.orEmpty()
        renderPending()
    }

    fun refresh() {
        if (ready && renderedRouteKey != pendingRouteKey) renderPending()
    }

    /** Release the launcher's Chromium surface before another app creates its own WebView. */
    fun pauseSurface() {
        if (destroyed) return
        view.visibility = android.view.View.INVISIBLE
        view.onPause()
    }

    /** Restore the HOME preview after the external app's surface has been removed. */
    fun resumeSurface() {
        if (destroyed) return
        view.onResume()
        view.visibility =
            if (ready) android.view.View.VISIBLE else android.view.View.INVISIBLE
        refresh()
    }

    fun destroy() {
        destroyed = true
        ready = false
        onAvailabilityChanged(false)
        view.removeJavascriptInterface(BRIDGE_NAME)
        view.stopLoading()
        view.destroy()
    }

    private fun renderPending() {
        if (!ready || destroyed) return
        if (pendingRouteKey == renderedRouteKey) return
        renderedRouteKey = pendingRouteKey
        val script =
            if (pendingRoutePoints.size < 2) {
                "window.hypernovaShowIdle();"
            } else {
                val points =
                    JSONArray().apply {
                        pendingRoutePoints.forEach { point ->
                            put(
                                JSONObject()
                                    .put("lat", point.latitude)
                                    .put("lng", point.longitude),
                            )
                        }
                    }
                "window.hypernovaShowRoute($points);"
            }
        view.evaluateJavascript(script, null)
    }

    @SuppressLint("SetJavaScriptEnabled", "JavascriptInterface")
    private fun createWebView(context: Context, isNightMode: Boolean): WebView =
        WebView(context).apply {
            // An opaque backing prevents the NXP compositor exposing a gray/black frame while
            // Chromium swaps raster tiles during Launcher <-> Navigation transitions.
            setBackgroundColor(
                android.graphics.Color.parseColor(if (isNightMode) "#07121d" else "#edf5f7"),
            )
            settings.apply {
                javaScriptEnabled = true
                domStorageEnabled = true
                allowFileAccess = false
                allowContentAccess = false
                javaScriptCanOpenWindowsAutomatically = false
                setSupportMultipleWindows(false)
                mixedContentMode = WebSettings.MIXED_CONTENT_NEVER_ALLOW
                cacheMode = WebSettings.LOAD_DEFAULT
            }
            setRendererPriorityPolicy(WebView.RENDERER_PRIORITY_BOUND, false)
            addJavascriptInterface(Bridge(), BRIDGE_NAME)
            webViewClient =
                object : WebViewClient() {
                    override fun onPageStarted(view: WebView?, url: String?, favicon: Bitmap?) {
                        Log.i(TAG, "Launcher Google map loading")
                    }

                    override fun shouldOverrideUrlLoading(
                        view: WebView?,
                        request: WebResourceRequest?,
                    ): Boolean = true

                    override fun onReceivedError(
                        view: WebView?,
                        request: WebResourceRequest?,
                        error: WebResourceError?,
                    ) {
                        if (request?.isForMainFrame == true) fail("Google map page could not load")
                    }
                }
            webChromeClient =
                object : WebChromeClient() {
                    override fun onConsoleMessage(message: android.webkit.ConsoleMessage?): Boolean {
                        message?.let { Log.d(TAG, "JS ${it.messageLevel()}: ${it.message()}") }
                        return true
                    }
                }
        }

    private fun fail(message: String) {
        mainHandler.post {
            if (destroyed) return@post
            Log.w(TAG, message)
            ready = false
            view.visibility = android.view.View.INVISIBLE
            onAvailabilityChanged(false)
        }
    }

    private inner class Bridge {
        @JavascriptInterface
        fun onReady() {
            mainHandler.post {
                if (destroyed) return@post
                ready = true
                Log.i(TAG, "Launcher Google map ready")
                view.visibility = android.view.View.VISIBLE
                onAvailabilityChanged(true)
                renderPending()
            }
        }

        @JavascriptInterface
        fun onInitializationFailed(message: String) = fail(message)

        @JavascriptInterface
        fun openNavigation() = mainHandler.post(onOpenNavigation)
    }

    private companion object {
        const val TAG = "HN-LauncherGoogleMap"
        const val BRIDGE_NAME = "HyperNovaLauncherBridge"
    }
}
