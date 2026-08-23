package com.hypernova.navigation.web

import android.annotation.SuppressLint
import android.app.Activity
import android.app.Application
import android.content.res.Configuration
import android.graphics.Bitmap
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.ViewGroup
import android.webkit.ConsoleMessage
import android.webkit.JavascriptInterface
import android.webkit.WebChromeClient
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import com.hypernova.navigation.BuildConfig
import com.hypernova.navigation.model.GeoPoint
import com.hypernova.navigation.model.GoogleDestinationRecord
import com.hypernova.navigation.navigation.GoogleRouteResult
import com.hypernova.navigation.navigation.NavigationGateway
import com.hypernova.navigation.navigation.NavigationGatewayListener
import com.hypernova.navigation.navigation.NavigatorInitializationFailure
import com.hypernova.navigation.places.DestinationSearchGateway
import com.hypernova.navigation.places.GooglePlacesException
import java.util.concurrent.ConcurrentHashMap
import java.util.UUID
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import org.json.JSONObject

/**
 * One process-owned Google Maps JavaScript engine shared by the AIDL service
 * and visible Navigation activity. Creation is lazy so a Launcher status bind
 * does not start Chromium. Once created, it remains alive while Navigation is
 * off screen so NOVA route commands and fast task switching retain map state.
 */
@SuppressLint("SetJavaScriptEnabled")
class GoogleMapsWebGateway internal constructor(
    private val application: Application,
    private val apiKey: String,
    private val listener: NavigationGatewayListener,
    private val originProvider: NavigationOriginProvider = NavigationOriginProvider(application),
) : NavigationGateway, DestinationSearchGateway {
    private val mainHandler = Handler(Looper.getMainLooper())
    private val pending = ConcurrentHashMap<String, PendingBridgeRequest>()
    private val engineState = MutableStateFlow<EngineState>(EngineState.Idle)

    @Volatile
    private var webView: WebView? = null

    @Volatile
    private var loadInProgress = false

    override val isReady: Boolean
        get() = engineState.value is EngineState.Ready

    // The bare-AOSP guest has no Google Navigation SDK. We truthfully provide search and a real
    // route preview, but do not imitate turn-by-turn movement with a synthetic timer.
    override val supportsGuidance: Boolean = false

    private var lastSurfaceNotificationAtMillis = Long.MIN_VALUE

    override fun initialize(activity: Activity?) {
        if (isReady) {
            listener.onNavigatorReady()
            return
        }
        runOnMain {
            if (loadInProgress) return@runOnMain
            loadInProgress = true
            engineState.value = EngineState.Loading
            getOrCreateWebView().loadDataWithBaseURL(
                GoogleMapsPage.DOCUMENT_ORIGIN,
                GoogleMapsPage.render(
                    apiKey = apiKey,
                    isNightMode =
                        application.resources.configuration.uiMode and
                            Configuration.UI_MODE_NIGHT_MASK == Configuration.UI_MODE_NIGHT_YES,
                ),
                "text/html",
                Charsets.UTF_8.name(),
                null,
            )
        }
    }

    override fun attachSurface(container: ViewGroup) {
        runOnMain {
            val mapWebView = getOrCreateWebView()
            if (mapWebView.parent === container) {
                notifySurfaceAttached(mapWebView)
                return@runOnMain
            }
            (mapWebView.parent as? ViewGroup)?.removeView(mapWebView)
            container.removeAllViews()
            container.addView(
                mapWebView,
                ViewGroup.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.MATCH_PARENT,
                ),
            )
            notifySurfaceAttached(mapWebView)
        }
    }

    override fun detachSurface(container: ViewGroup) {
        runOnMain {
            webView?.let { mapWebView ->
                if (mapWebView.parent === container) container.removeView(mapWebView)
            }
        }
    }

    override fun setSurfaceInsets(topPixels: Int, bottomPixels: Int) {
        if (!isReady) return
        evaluate(
            "window.hypernovaSetInsets(${topPixels.coerceAtLeast(0)},${bottomPixels.coerceAtLeast(0)});",
        )
    }

    override suspend fun search(query: String): List<GoogleDestinationRecord> {
        awaitReadyForSearch()
        val origin = originProvider.current().point
        val response =
            execute(
                operation = OP_SEARCH,
                invocation = { requestId ->
                    "window.hypernovaSearch(" +
                        "${JSONObject.quote(requestId)}," +
                        "${JSONObject.quote(query)}," +
                        "${origin.latitude},${origin.longitude});"
                },
            )
        if (!response.ok) {
            throw GooglePlacesException.RequestFailed(
                IllegalStateException(response.message.ifBlank { "Google Places search failed." }),
            )
        }
        return try {
            GoogleMapsBridgeCodec.parseDestinations(response.payload)
        } catch (failure: Exception) {
            throw GooglePlacesException.RequestFailed(failure)
        }
    }

    override suspend fun setDestination(destination: GoogleDestinationRecord): GoogleRouteResult {
        if (!isReady) return GoogleRouteResult.NetworkError
        val origin = originProvider.current()
        val destinationJson =
            JSONObject()
                .put("placeId", destination.placeId)
                .put("title", destination.title)
                .toString()
        val response =
            execute(
                operation = OP_ROUTE,
                invocation = { requestId ->
                    "window.hypernovaRoute(" +
                        "${JSONObject.quote(requestId)}," +
                        "$destinationJson," +
                        "${origin.point.latitude},${origin.point.longitude});"
                },
            )
        if (!response.ok) {
            return when (response.errorCode) {
                ERROR_NO_ROUTE -> GoogleRouteResult.NoRoute
                ERROR_AUTHORIZATION -> GoogleRouteResult.AuthorizationError
                ERROR_CANCELLED -> GoogleRouteResult.Cancelled
                ERROR_LOCATION -> GoogleRouteResult.LocationUnavailable
                ERROR_NETWORK -> GoogleRouteResult.NetworkError
                else -> GoogleRouteResult.InternalError(
                    response.message.ifBlank { "Google route calculation failed." },
                )
            }
        }
        return try {
            GoogleRouteResult.Ready(
                route = GoogleMapsBridgeCodec.parseRoute(response.payload),
                usesDemoOrigin = origin.usesDemoOrigin,
            )
        } catch (failure: Exception) {
            GoogleRouteResult.InternalError(
                failure.message ?: "Google route geometry is unavailable.",
            )
        }
    }

    override fun startGuidance(): Boolean {
        return false
    }

    override fun cancelNavigation() {
        if (isReady) evaluate("window.hypernovaCancelRoute();")
    }

    private suspend fun awaitReadyForSearch() {
        when (
            val value =
                engineState.first {
                    it is EngineState.Ready || it is EngineState.Failed
                }
        ) {
            EngineState.Ready -> Unit
            is EngineState.Failed ->
                throw GooglePlacesException.RequestFailed(IllegalStateException(value.message))
            EngineState.Idle,
            EngineState.Loading -> error("Unreachable engine state")
        }
    }

    private suspend fun execute(
        operation: String,
        invocation: (String) -> String,
    ): BridgeResponse {
        val requestId = UUID.randomUUID().toString()
        val result = CompletableDeferred<BridgeResponse>()
        val request = PendingBridgeRequest(operation, result)
        pending[requestId] = request
        return try {
            withContext(Dispatchers.Main.immediate) {
                val mapWebView = webView
                    ?: error("Google Maps web engine was not initialized")
                mapWebView.evaluateJavascript(invocation(requestId), null)
            }
            result.await()
        } finally {
            pending.remove(requestId, request)
        }
    }

    private fun evaluate(script: String) {
        runOnMain { webView?.evaluateJavascript(script, null) }
    }

    private fun runOnMain(action: () -> Unit) {
        if (Looper.myLooper() == Looper.getMainLooper()) action() else mainHandler.post(action)
    }

    private fun handleReady() {
        runOnMain {
            loadInProgress = false
            engineState.value = EngineState.Ready
            listener.onNavigatorReady()
        }
    }

    private fun handleInitializationFailure(code: String, message: String) {
        val failure =
            when (code) {
                ERROR_AUTHORIZATION -> NavigatorInitializationFailure.NOT_AUTHORIZED
                ERROR_LOCATION -> NavigatorInitializationFailure.LOCATION_PERMISSION_MISSING
                ERROR_NETWORK -> NavigatorInitializationFailure.NETWORK
                else -> NavigatorInitializationFailure.INTERNAL
            }
        runOnMain {
            loadInProgress = false
            engineState.value = EngineState.Failed(message)
            pending.values.forEach {
                it.result.complete(BridgeResponse(false, "{}", code, message))
            }
            pending.clear()
            listener.onNavigatorInitializationFailed(failure)
        }
    }

    @SuppressLint("JavascriptInterface")
    private fun createWebView(): WebView =
        WebView(application).apply {
            setBackgroundColor(android.graphics.Color.rgb(7, 18, 29))
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
            WebView.setWebContentsDebuggingEnabled(BuildConfig.DEBUG)
            // There is only one Google renderer in the product. Keep it bound while its
            // Activity is briefly hidden so Launcher ↔ Navigation switches reattach the
            // existing map instead of paying another Chromium/Maps cold start.
            setRendererPriorityPolicy(WebView.RENDERER_PRIORITY_BOUND, false)
            addJavascriptInterface(Bridge(), BRIDGE_NAME)
            webViewClient =
                object : WebViewClient() {
                    override fun onPageStarted(view: WebView?, url: String?, favicon: Bitmap?) {
                        Log.i(TAG, "Google Maps web engine page loading")
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
                        if (request?.isForMainFrame == true) {
                            handleInitializationFailure(
                                ERROR_NETWORK,
                                "Google Maps page could not load.",
                            )
                        }
                    }

                    override fun onReceivedHttpError(
                        view: WebView?,
                        request: WebResourceRequest?,
                        errorResponse: WebResourceResponse?,
                    ) {
                        val host = request?.url?.host.orEmpty()
                        if (
                            host == "maps.googleapis.com" &&
                            (errorResponse?.statusCode ?: 0) >= 400
                        ) {
                            handleInitializationFailure(
                                ERROR_NETWORK,
                                "Google Maps JavaScript could not load.",
                            )
                        }
                    }
                }
            webChromeClient =
                object : WebChromeClient() {
                    override fun onConsoleMessage(consoleMessage: ConsoleMessage?): Boolean {
                        val value = consoleMessage ?: return false
                        Log.d(TAG, "JS ${value.messageLevel()}: ${value.message()}")
                        return true
                    }
                }
        }

    private fun getOrCreateWebView(): WebView {
        check(Looper.myLooper() == Looper.getMainLooper()) {
            "Google Maps WebView must be created on the main thread"
        }
        return webView ?: createWebView().also { webView = it }
    }

    private fun notifySurfaceAttached(mapWebView: WebView) {
        val now = android.os.SystemClock.uptimeMillis()
        if (now - lastSurfaceNotificationAtMillis < SURFACE_NOTIFICATION_DEBOUNCE_MILLIS) return
        lastSurfaceNotificationAtMillis = now
        mapWebView.post {
            if (isReady && mapWebView.parent != null) {
                mapWebView.evaluateJavascript("window.hypernovaSurfaceAttached();", null)
            }
        }
    }

    private inner class Bridge {
        @JavascriptInterface
        fun onReady() = handleReady()

        @JavascriptInterface
        fun onInitializationFailed(code: String, message: String) {
            handleInitializationFailure(code, message)
        }

        @JavascriptInterface
        fun onDestinationRequested(payload: String) {
            val destination =
                try {
                    GoogleMapsBridgeCodec.parseMapDestination(payload)
                } catch (failure: Exception) {
                    Log.w(TAG, "Rejected invalid map destination", failure)
                    return
                }
            runOnMain { listener.onMapDestinationRequested(destination) }
        }

        @JavascriptInterface
        fun onResponse(
            requestId: String,
            operation: String,
            ok: Boolean,
            payload: String,
            errorCode: String,
            message: String,
        ) {
            val expected = pending[requestId] ?: return
            if (operation != expected.operation) {
                expected.result.complete(
                    BridgeResponse(false, "{}", ERROR_INTERNAL, "Unexpected Google Maps response."),
                )
                return
            }
            expected.result.complete(BridgeResponse(ok, payload, errorCode, message))
        }
    }

    private sealed interface EngineState {
        data object Idle : EngineState
        data object Loading : EngineState
        data object Ready : EngineState
        data class Failed(val message: String) : EngineState
    }

    private data class BridgeResponse(
        val ok: Boolean,
        val payload: String,
        val errorCode: String,
        val message: String,
    )

    private data class PendingBridgeRequest(
        val operation: String,
        val result: CompletableDeferred<BridgeResponse>,
    )

    private companion object {
        const val TAG = "HN-GoogleMapsWeb"
        const val BRIDGE_NAME = "HyperNovaBridge"
        const val SURFACE_NOTIFICATION_DEBOUNCE_MILLIS = 350L
        const val OP_SEARCH = "search"
        const val OP_ROUTE = "route"
        const val ERROR_NO_ROUTE = "NO_ROUTE"
        const val ERROR_AUTHORIZATION = "AUTHORIZATION"
        const val ERROR_NETWORK = "NETWORK"
        const val ERROR_LOCATION = "LOCATION"
        const val ERROR_CANCELLED = "CANCELLED"
        const val ERROR_INTERNAL = "INTERNAL"
    }
}
