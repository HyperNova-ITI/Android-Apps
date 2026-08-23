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
    val view: WebView = createWebView(context)
    private var ready = false
    private var destroyed = false
    private var pendingDestination: String? = null
    private var renderedDestination: String? = null

    init {
        view.visibility = android.view.View.INVISIBLE
        view.loadDataWithBaseURL(
            LauncherGoogleMapsPage.DOCUMENT_ORIGIN,
            LauncherGoogleMapsPage.render(apiKey, isNightMode),
            "text/html",
            "UTF-8",
            null,
        )
    }

    fun setDestination(routeId: String, destination: String?) {
        val query = destination?.trim()?.takeIf { routeId.isNotBlank() && it.isNotBlank() }
        pendingDestination = query
        renderPending()
    }

    fun refresh() {
        if (ready && renderedDestination == null && pendingDestination != null) renderPending()
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
        val destination = pendingDestination
        if (destination == renderedDestination) return
        renderedDestination = destination
        val script =
            if (destination == null) {
                "window.hypernovaShowIdle();"
            } else {
                "window.hypernovaShowDestination(${JSONObject.quote(destination)});"
            }
        view.evaluateJavascript(script, null)
    }

    @SuppressLint("SetJavaScriptEnabled", "JavascriptInterface")
    private fun createWebView(context: Context): WebView =
        WebView(context).apply {
            setBackgroundColor(android.graphics.Color.TRANSPARENT)
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
                view.visibility = android.view.View.VISIBLE
                onAvailabilityChanged(true)
                renderPending()
            }
        }

        @JavascriptInterface
        fun onInitializationFailed(message: String) = fail(message)

        @JavascriptInterface
        fun onRenderFailed(message: String) {
            Log.w(TAG, "Could not render Google destination: $message")
            renderedDestination = null
        }

        @JavascriptInterface
        fun openNavigation() = mainHandler.post(onOpenNavigation)
    }

    private companion object {
        const val TAG = "HN-LauncherGoogleMap"
        const val BRIDGE_NAME = "HyperNovaLauncherBridge"
    }
}
