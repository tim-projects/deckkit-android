package com.deckk.it;

import android.app.Application;
import android.util.Log;
import io.github.edsuns.adfilter.AdFilter;

public class App extends Application {
    private static final String TAG = "App";

    @Override
    public void onCreate() {
        super.onCreate();
        // AdFilter must be created before any WebViewClient calls AdFilter.get().
        try {
            AdFilter.Companion.create(this);
        } catch (Throwable t) {
            Log.e(TAG, "AdFilter init failed", t);
        }
    }
}
