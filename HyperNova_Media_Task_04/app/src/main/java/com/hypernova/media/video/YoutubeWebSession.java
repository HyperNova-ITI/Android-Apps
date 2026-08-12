package com.hypernova.media.video;

import android.annotation.SuppressLint;
import android.content.Context;
import android.view.View;
import android.view.ViewGroup;
import android.webkit.CookieManager;
import android.webkit.WebChromeClient;
import android.webkit.WebView;
import android.webkit.WebViewClient;

/** Persistent mobile-YouTube WebView, held by Application and created with application context. */
public final class YoutubeWebSession {
    public interface NavigationListener {
        void onYoutubeNavigationChanged(boolean canGoBack, boolean canReturnHome,
                String url, boolean playbackKnown, boolean playing);
    }

    public interface FullscreenListener {
        void onWebFullscreen(View view, WebChromeClient.CustomViewCallback callback);
        void onWebFullscreenHidden();
    }

    private WebView webView;
    private FullscreenListener fullscreenListener;
    private NavigationListener navigationListener;
    private boolean playbackKnown;
    private boolean playing;
    /** Set only for an explicit top-level Video selection, never for the in-pane Home action. */
    private boolean clearHistoryWhenYoutubeHomeFinishes;
    private static final String YOUTUBE_HOME_URL = "https://m.youtube.com/";

    @SuppressLint("SetJavaScriptEnabled")
    public WebView acquire(Context context) {
        if (webView != null) return webView;
        CookieManager cookies = CookieManager.getInstance();
        cookies.setAcceptCookie(true);
        webView = new WebView(context.getApplicationContext());
        cookies.setAcceptThirdPartyCookies(webView, true);
        webView.getSettings().setJavaScriptEnabled(true);
        webView.getSettings().setDomStorageEnabled(true);
        webView.getSettings().setDatabaseEnabled(true);
        webView.getSettings().setMediaPlaybackRequiresUserGesture(false);
        webView.getSettings().setUseWideViewPort(true);
        webView.getSettings().setLoadWithOverviewMode(true);
        webView.addJavascriptInterface(new PlaybackBridge(), "HyperNovaYoutube");
        webView.setWebViewClient(new WebViewClient() {
            @Override public boolean shouldOverrideUrlLoading(WebView view, String url) {
                view.loadUrl(url);
                return true;
            }
            @Override public void onPageStarted(WebView view, String url, android.graphics.Bitmap favicon) {
                playbackKnown = false;
                dispatchNavigation();
            }
            @Override public void onPageFinished(WebView view, String url) {
                view.evaluateJavascript(MEDIA_WATCHER, null);
                if (clearHistoryWhenYoutubeHomeFinishes && isYoutubeHome(url)) {
                    // A source selection starts a fresh YouTube page, but deliberately leaves
                    // cookies, DOM storage and the persistent WebView instance untouched.
                    view.clearHistory();
                    clearHistoryWhenYoutubeHomeFinishes = false;
                }
                dispatchNavigation();
            }
            @Override public void doUpdateVisitedHistory(WebView view, String url,
                    boolean isReload) {
                dispatchNavigation();
            }
        });
        webView.setWebChromeClient(new WebChromeClient() {
            @Override public void onShowCustomView(View view, CustomViewCallback callback) {
                if (fullscreenListener != null) fullscreenListener.onWebFullscreen(view, callback);
                else callback.onCustomViewHidden();
            }
            @Override public void onHideCustomView() {
                if (fullscreenListener != null) fullscreenListener.onWebFullscreenHidden();
            }
        });
        webView.loadUrl(YOUTUBE_HOME_URL);
        return webView;
    }

    public void attach(Context context, ViewGroup host, FullscreenListener listener) {
        WebView view = acquire(context);
        fullscreenListener = listener;
        ViewGroup parent = (ViewGroup) view.getParent();
        if (parent != null) parent.removeView(view);
        host.addView(view, new ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT));
        view.onResume();
        view.resumeTimers();
        dispatchNavigation();
    }

    /** Detach but do not destroy: URL, scroll position and cookies survive returning to Video. */
    public void detach(boolean pause) {
        if (webView == null) return;
        fullscreenListener = null;
        ViewGroup parent = (ViewGroup) webView.getParent();
        if (parent != null) parent.removeView(webView);
        if (pause) {
            webView.onPause();
            webView.pauseTimers();
        }
        CookieManager.getInstance().flush();
    }

    public void setNavigationListener(NavigationListener listener) {
        navigationListener = listener;
        dispatchNavigation();
    }

    public boolean canGoBack() { return webView != null && webView.canGoBack(); }

    /** True if a visible Back control has useful history or can safely escape an external page. */
    public boolean canNavigateBackOrHome() {
        return canGoBack() || webView != null && !isYoutubeHome(webView.getUrl());
    }

    /** Goes through existing history, with a safe in-session route back from history-less Google pages. */
    public void goBackOrHome() {
        if (webView == null) return;
        if (webView.canGoBack()) webView.goBack();
        else if (!isYoutubeHome(webView.getUrl())) goHome();
        dispatchNavigation();
    }

    /** Resets page only; cookies, DOM storage, cache and the WebView instance remain intact. */
    public void goHome() {
        if (webView == null) return;
        playbackKnown = false;
        webView.loadUrl(YOUTUBE_HOME_URL);
        dispatchNavigation();
    }

    public void openYoutubeHome(Context context) {
        acquire(context);
        playbackKnown = false;
        clearHistoryWhenYoutubeHomeFinishes = true;
        if (isYoutubeHome(webView.getUrl())) {
            // Avoid adding a duplicate Home entry during first selection after acquisition.
            webView.clearHistory();
            clearHistoryWhenYoutubeHomeFinishes = false;
            dispatchNavigation();
        } else {
            webView.loadUrl(YOUTUBE_HOME_URL);
            dispatchNavigation();
        }
    }

    private boolean isYoutubeHome(String url) {
        return url != null && (url.equals(YOUTUBE_HOME_URL) || url.equals("https://m.youtube.com"));
    }

    private void dispatchNavigation() {
        NavigationListener listener = navigationListener;
        if (listener == null) return;
        String url = webView == null ? "" : webView.getUrl();
        listener.onYoutubeNavigationChanged(canGoBack(), canNavigateBackOrHome(), url == null ? "" : url,
                playbackKnown, playing);
    }

    private final class PlaybackBridge {
        @android.webkit.JavascriptInterface
        public void mediaState(boolean known, boolean value) {
            playbackKnown = known;
            playing = value;
            dispatchNavigation();
        }
    }

    /** Reports only Web Media Session / HTML5 video state; no catalog or arbitrary DOM scraping. */
    private static final String MEDIA_WATCHER = "(function(){if(window.__hyperNovaMediaWatch)return;"
            + "window.__hyperNovaMediaWatch=true;var last='';setInterval(function(){try{"
            + "var ms=navigator.mediaSession;var vids=document.getElementsByTagName('video');"
            + "var v=vids.length?vids[0]:null;var known=!!v||!!(ms&&ms.metadata);"
            + "var playing=(ms&&ms.playbackState==='playing')||(v&&!v.paused);"
            + "var key=known+'|'+playing;if(key!==last){last=key;HyperNovaYoutube.mediaState(!!known,!!playing);}" 
            + "}catch(e){}},1000);})();";
}
