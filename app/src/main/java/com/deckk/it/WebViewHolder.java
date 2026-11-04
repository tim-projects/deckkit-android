package com.deckk.it;

import android.view.ViewGroup;
import android.view.ViewParent;
import android.webkit.WebView;

/**
 * Holds the single deckk.it WebView outside the Activity lifecycle.
 *
 * External links are opened in BrowserActivity on top of MainActivity, and Android
 * may destroy MainActivity while it is hidden. If the WebView lived only in the
 * activity, returning would re-fetch deckk.it and re-run its boot sequence
 * (login state, feed resync). Keeping the instance here means a new MainActivity
 * re-attaches the very same loaded page instead.
 *
 * The bridge/clients are refreshed on every re-attach (see MainActivity), so no
 * destroyed activity is referenced by the live page.
 */
final class WebViewHolder {
    private static WebView webView;

    private WebViewHolder() {}

    /** Returns the retained WebView, or null if none is cached. */
    static WebView get() {
        return webView;
    }

    static void set(WebView wv) {
        webView = wv;
    }

    /** Removes the retained WebView from its old parent and adds it to container. */
    static void attachTo(ViewGroup container) {
        if (webView == null || container == null) return;
        ViewParent parent = webView.getParent();
        if (parent instanceof ViewGroup) {
            ((ViewGroup) parent).removeView(webView);
        }
        container.addView(webView);
    }

    /** Drops the retained WebView and tears down its page. Only on a real finish. */
    static void destroy() {
        if (webView == null) return;
        ViewParent parent = webView.getParent();
        if (parent instanceof ViewGroup) {
            ((ViewGroup) parent).removeView(webView);
        }
        webView.destroy();
        webView = null;
    }
}
