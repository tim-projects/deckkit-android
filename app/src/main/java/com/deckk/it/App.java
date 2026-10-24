package com.deckk.it;

import android.app.Application;

/**
 * Plain Application subclass. Ad blocking is done without native libraries:
 * BrowserActivity blocks third-party requests for external sites, gated on the
 * "Ad Blocker" preference (default ON).
 */
public class App extends Application {
}
