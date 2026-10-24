package com.deckk.it;

import android.content.Context;
import android.content.SharedPreferences;
import android.webkit.JavascriptInterface;

/**
 * App-only settings, stored locally and exposed to the deckk.it settings page
 * through a JavaScript bridge that only exists inside this app.
 */
public class AppSettings {
    public static final String BRIDGE_NAME = "deckkAppSettings";
    private static final String PREFS_NAME = "app_settings";
    private static final String KEY_ADBLOCK = "adBlockEnabled";
    private static final String KEY_ZOOM = "zoomEnabled";

    private final SharedPreferences prefs;

    public AppSettings(Context context) {
        prefs = context.getApplicationContext()
                .getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
    }

    // "Ad Blocker" blocks third-party requests on external sites.
    public boolean isAdBlockEnabled() {
        return prefs.getBoolean(KEY_ADBLOCK, true);
    }

    public boolean isZoomEnabled() {
        return prefs.getBoolean(KEY_ZOOM, true);
    }

    public class Bridge {
        @JavascriptInterface
        public boolean getAdBlockEnabled() {
            return isAdBlockEnabled();
        }

        @JavascriptInterface
        public void setAdBlockEnabled(boolean value) {
            prefs.edit().putBoolean(KEY_ADBLOCK, value).apply();
        }

        @JavascriptInterface
        public boolean getZoomEnabled() {
            return isZoomEnabled();
        }

        @JavascriptInterface
        public void setZoomEnabled(boolean value) {
            prefs.edit().putBoolean(KEY_ZOOM, value).apply();
        }
    }
}
