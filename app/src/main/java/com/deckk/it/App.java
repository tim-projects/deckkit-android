package com.deckk.it;

import android.app.Application;

/**
 * Application subclass kept as a hook. AdFilter is NOT initialized here: the engine
 * is created lazily in BrowserActivity (its only consumer) so that a failure in the
 * Kotlin/native library can never crash the launcher process on startup.
 */
public class App extends Application {
    @Override
    public void onCreate() {
        super.onCreate();
    }
}
