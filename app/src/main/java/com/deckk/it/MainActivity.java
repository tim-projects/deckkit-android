package com.deckk.it;

import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.res.Configuration;
import android.graphics.Color;
import android.net.Uri;
import android.os.Bundle;
import android.util.Log;
import android.view.ActionMode;
import android.view.Menu;
import android.view.MenuItem;
import android.view.View;
import android.webkit.CookieManager;
import android.webkit.SslErrorHandler;
import android.webkit.WebChromeClient;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.net.http.SslError;
import android.widget.ProgressBar;
import androidx.appcompat.app.AppCompatActivity;
import androidx.browser.customtabs.CustomTabsIntent;
import androidx.webkit.WebSettingsCompat;
import androidx.webkit.WebViewFeature;
import java.io.File;
import java.io.FileWriter;
import java.io.IOException;
import java.util.Locale;
import org.json.JSONArray;

public class MainActivity extends AppCompatActivity {
    private WebView webView;
    private ProgressBar progressBar;
    private static final String BASE_URL = BuildConfig.BASE_URL;
    private static final boolean DEBUG = BuildConfig.DEBUG;
    private static final int MENU_TRANSLATE = 1001;

    private void log(String msg) {
        Log.d("MainActivity", msg);
        if (DEBUG) {
            try {
                File logFile = new File(getExternalFilesDir(null), "app.log");
                FileWriter fw = new FileWriter(logFile, true);
                fw.write(System.currentTimeMillis() + ": " + msg + "\n");
                fw.close();
            } catch (IOException e) {}
        }
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);
        webView = findViewById(R.id.webview);
        progressBar = findViewById(R.id.progressBar);

        CookieManager cookieManager = CookieManager.getInstance();
        cookieManager.setAcceptCookie(true);
        cookieManager.setAcceptThirdPartyCookies(webView, true);

        WebSettings webSettings = webView.getSettings();
        webSettings.setJavaScriptEnabled(true);
        webSettings.setDomStorageEnabled(true);
        webSettings.setDatabaseEnabled(true);
        webSettings.setAllowContentAccess(true);
        webSettings.setAllowFileAccess(true);
        webSettings.setMixedContentMode(WebSettings.MIXED_CONTENT_ALWAYS_ALLOW);
        webSettings.setMediaPlaybackRequiresUserGesture(false);
        webSettings.setUserAgentString("Mozilla/5.0 (Linux; Android 14; Pixel 8) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Mobile Safari/537.36");
        webSettings.setCacheMode(WebSettings.LOAD_CACHE_ELSE_NETWORK);

        // Avoid a white flash before the page paints: let the themed window background show through.
        webView.setBackgroundColor(Color.TRANSPARENT);

        // Determine the URL to load up front so it is available before the dark-mode call below.
        String incomingData = getIntent().getDataString();
        String urlToLoad = (incomingData != null && !incomingData.isEmpty()) ? incomingData : BASE_URL;
        applyDarkModeForUrl(webSettings, urlToLoad);

        webView.setWebViewClient(new WebViewClient() {
            @Override
            public boolean shouldOverrideUrlLoading(WebView view, String url) {
                log("shouldOverrideUrlLoading: " + url);
                if (url.startsWith("http://") || url.startsWith("https://")) {
                    Uri uri = Uri.parse(url);
                    String host = uri.getHost();
                    if (host != null && (host.equals(Uri.parse(BASE_URL).getHost()) ||
                        host.contains("auth.deckk.it") ||
                        host.contains("googleapis.com") ||
                        host.contains("google.com") ||
                        host.contains("firebaseapp.com") ||
                        host.contains("firebase.com") ||
                        host.contains("accounts.google.com") ||
                        host.contains("oauth2.googleapis.com") ||
                        host.contains("ssl.gstatic.com") ||
                        host.contains("gstatic.com"))) {
                        log("Loading in WebView: " + url);
                        applyDarkModeForUrl(webView.getSettings(), url);
                        return false;
                    } else {
                        log("Opening in Custom Tab: " + url);
                        openInCustomTab(url);
                        return true;
                    }
                } else if (url.startsWith("intent://")) {
                    try {
                        Intent intent = Intent.parseUri(url, Intent.URI_INTENT_SCHEME);
                        Intent chooser = Intent.createChooser(intent, "Open with");
                        startActivity(chooser);
                    } catch (Exception e) {}
                    return true;
                } else if (url.startsWith("firebaseauth://") || url.startsWith("chrome://")) {
                    return false;
                } else {
                    openInCustomTab(url);
                    return true;
                }
            }

            @Override
            public void onReceivedSslError(WebView view, SslErrorHandler handler, SslError error) {
                handler.proceed();
            }
        });

        webView.setWebChromeClient(new WebChromeClient() {
            @Override
            public boolean onCreateWindow(WebView view, boolean isDialog, boolean isUserGesture, android.os.Message resultMsg) {
                log("onCreateWindow called, isDialog: " + isDialog);
                // Return false to prevent popup creation - let navigation happen in main WebView
                // This ensures localStorage/IndexedDB is shared for Firebase Auth
                return false;
            }

            @Override
            public void onProgressChanged(WebView view, int newProgress) {
                if (newProgress == 100) progressBar.setVisibility(View.GONE);
                else { progressBar.setVisibility(View.VISIBLE); progressBar.setProgress(newProgress); }
            }
        });

        webView.restoreState(savedInstanceState);
        // Use getDataString() (not getData().toString()) so the exact URL is preserved,
        // including OAuth query/fragment params. Uri.toString() re-encodes the URL and
        // corrupts values such as the Google OAuth state/code (base64url uses +, /, =),
        // which makes the auth handler reject the callback with 400 "malformed".
        if (webView.getUrl() == null || webView.getUrl().isEmpty()) {
            webView.loadUrl(urlToLoad);
        }
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        setIntent(intent);
        // App was already running when opened as the default browser for a new link.
        // Use getDataString() to preserve the exact OAuth callback URL (query/fragment);
        // Uri.toString() would re-encode it and corrupt state/code, so the auth handler
        // returns 400 "malformed" instead of completing sign-in.
        String data = intent.getDataString();
        if (data != null && !data.isEmpty() && webView != null) {
            applyDarkModeForUrl(webView.getSettings(), data);
            webView.loadUrl(data);
        }
    }

    private boolean isNightMode() {
        int nightModeFlags = getResources().getConfiguration().uiMode
                & Configuration.UI_MODE_NIGHT_MASK;
        return nightModeFlags == Configuration.UI_MODE_NIGHT_YES;
    }

    private void applyDarkModeForUrl(WebSettings webSettings, String url) {
        boolean internal = isInternalUrl(url);
        boolean night = isNightMode();
        if (WebViewFeature.isFeatureSupported(WebViewFeature.FORCE_DARK)) {
            if (internal && night) {
                WebSettingsCompat.setForceDark(webSettings, WebSettingsCompat.FORCE_DARK_AUTO);
            } else {
                WebSettingsCompat.setForceDark(webSettings, WebSettingsCompat.FORCE_DARK_OFF);
            }
        }
        if (WebViewFeature.isFeatureSupported(WebViewFeature.FORCE_DARK_STRATEGY)) {
            WebSettingsCompat.setForceDarkStrategy(webSettings,
                    WebSettingsCompat.DARK_STRATEGY_WEB_THEME_DARKENING_ONLY);
        }
    }

    private boolean isInternalUrl(String url) {
        if (url == null) return false;
        try {
            Uri uri = Uri.parse(url);
            String host = uri.getHost();
            if (host == null) return false;
            String baseHost = Uri.parse(BASE_URL).getHost();
            if (host.equals(baseHost)) return true;
            if (host.contains("auth.deckk.it") ||
                host.contains("googleapis.com") ||
                host.contains("google.com") ||
                host.contains("firebaseapp.com") ||
                host.contains("firebase.com") ||
                host.contains("accounts.google.com") ||
                host.contains("oauth2.googleapis.com") ||
                host.contains("ssl.gstatic.com") ||
                host.contains("gstatic.com")) {
                return true;
            }
        } catch (Exception e) {}
        return false;
    }

    @Override
    public void onConfigurationChanged(Configuration newConfig) {
        super.onConfigurationChanged(newConfig);
        // Re-evaluate dark mode without reloading the page when the system theme changes at runtime.
        if (webView != null) {
            applyDarkModeForUrl(webView.getSettings(), webView.getUrl());
        }
    }

    @Override
    protected void onSaveInstanceState(Bundle outState) {
        webView.saveState(outState);
        super.onSaveInstanceState(outState);
    }

    @Override
    protected void onRestoreInstanceState(Bundle savedInstanceState) {
        super.onRestoreInstanceState(savedInstanceState);
        webView.restoreState(savedInstanceState);
    }

    private void translateSelection(ActionMode mode) {
        webView.evaluateJavascript("window.getSelection().toString()", raw -> {
            // raw is a JSON-encoded string (quotes + escapes). Decode via JSONArray trick.
            String selected = "";
            try { selected = new JSONArray("[" + raw + "]").getString(0); } catch (Exception ignored) {}
            if (mode != null) mode.finish();
            launchTranslate(selected);
        });
    }

    private void launchTranslate(String text) {
        if (text == null) return;
        text = text.trim();
        if (text.isEmpty()) return;
        String target = Locale.getDefault().getLanguage(); // device default language
        Intent appIntent = new Intent(Intent.ACTION_SEND);
        appIntent.setPackage("com.google.android.apps.translate");
        appIntent.setType("text/plain");
        appIntent.putExtra(Intent.EXTRA_TEXT, text);
        appIntent.putExtra("key_text_input", text);
        appIntent.putExtra("key_text_output", "");
        appIntent.putExtra("key_language_from", "auto");   // source = auto detect
        appIntent.putExtra("key_language_to", target);
        try {
            if (appIntent.resolveActivity(getPackageManager()) != null) {
                startActivity(appIntent);
                return;
            }
        } catch (Exception ignored) {}
        // Fallback: web translate page in a Custom Tab (already dark-mode aware).
        String url = "https://translate.google.com/translate?sl=auto&tl="
                + target + "&text=" + Uri.encode(text);
        openInCustomTab(url);
    }

    private void openInCustomTab(String url) {
        // Only delegate to a Custom Tab if a *different* app can handle it.
        // If this app is the default browser (no separate browser installed), handing the
        // URL back via ACTION_VIEW would just resolve to this app again and crash/loop.
        String provider = null;
        try {
            provider = androidx.browser.customtabs.CustomTabsClient.getPackageName(this, null);
        } catch (Exception ignored) {}
        if (provider != null && !provider.equals(getPackageName())) {
            try {
                CustomTabsIntent.Builder builder = new CustomTabsIntent.Builder();
                // Follow the system light/dark theme instead of forcing a light toolbar.
                builder.setColorScheme(isNightMode()
                        ? CustomTabsIntent.COLOR_SCHEME_DARK
                        : CustomTabsIntent.COLOR_SCHEME_LIGHT);
                CustomTabsIntent customTabsIntent = builder.build();
                customTabsIntent.launchUrl(this, Uri.parse(url));
                return;
            } catch (Exception e) {
                log("Custom Tabs launch failed: " + e.getMessage());
            }
        }
        // No external browser available (or launch failed) -> open within this WebView.
        applyDarkModeForUrl(webView.getSettings(), url);
        webView.loadUrl(url);
    }

    // The WebView's text-selection contextual action bar is an Activity-level ActionMode,
    // so we add our Translate item here as each selection CAB starts. (WebView has no
    // setCustomSelectionActionModeCallback of its own; that method lives on TextView.)
    @Override
    public void onActionModeStarted(ActionMode mode) {
        super.onActionModeStarted(mode);
        Menu menu = mode.getMenu();
        // On API 24+ the system reuses the same Menu across selection sessions, so adding
        // unconditionally would create a duplicate item (which then overflows/drops). Only
        // add once, and only when there is an actual text selection (not just a cursor tap).
        if (menu.findItem(MENU_TRANSLATE) != null) return;
        if (mode.getTitle() != null || mode.getSubtitle() != null) return; // e.g. a text field CAB, not a selection
        MenuItem translateItem = menu.add(Menu.NONE, MENU_TRANSLATE, Menu.NONE, R.string.action_translate);
        translateItem.setShowAsAction(MenuItem.SHOW_AS_ACTION_IF_ROOM);
        translateItem.setOnMenuItemClickListener(item -> {
            translateSelection(mode);
            return true;
        });
    }

    @Override
    public void onBackPressed() {
        if (webView.canGoBack()) webView.goBack();
        else super.onBackPressed();
    }
}
