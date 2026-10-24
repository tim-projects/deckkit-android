package com.deckk.it;

import android.content.Intent;
import android.content.res.Configuration;
import android.graphics.Bitmap;
import android.graphics.Color;
import android.net.Uri;
import android.os.Bundle;
import android.util.Log;
import android.view.View;
import android.webkit.CookieManager;
import android.webkit.SslErrorHandler;
import android.webkit.WebChromeClient;
import android.webkit.WebResourceRequest;
import android.webkit.WebResourceResponse;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.net.http.SslError;
import android.widget.ProgressBar;
import androidx.appcompat.app.AppCompatActivity;
import androidx.webkit.WebSettingsCompat;
import androidx.webkit.WebViewFeature;

public class BrowserActivity extends AppCompatActivity {
    private static final String TAG = "BrowserActivity";

    private WebView webView;
    private ProgressBar progressBar;
    private AppSettings appSettings;
    // Host of the top-level page being viewed (updated on main-frame requests).
    private String pageHost;

    // Sites that declare user-scalable=no still block pinch zoom; override their
    // viewport meta tag so two-finger zoom works.
    private static final String INJECT_ZOOM_JS =
        "(function() {" +
        "  try {" +
        "    var content = 'width=device-width, initial-scale=1.0, maximum-scale=5.0, user-scalable=yes';" +
        "    var meta = document.querySelector('meta[name=\"viewport\"]');" +
        "    if (meta) { meta.setAttribute('content', content); }" +
        "    else {" +
        "      meta = document.createElement('meta');" +
        "      meta.name = 'viewport';" +
        "      meta.setAttribute('content', content);" +
        "      (document.head || document.documentElement).appendChild(meta);" +
        "    }" +
        "  } catch(e) {}" +
        "})();";

    private static final String INJECT_DARK_MODE_JS =
        "(function() {" +
        "  try {" +
        "    var meta = document.querySelector('meta[name=\"color-scheme\"]');" +
        "    if (meta) { meta.parentNode.removeChild(meta); }" +
        "    var id = '__webview-darkmode-fix__';" +
        "    if (!document.getElementById(id)) {" +
        "      var style = document.createElement('style');" +
        "      style.id = id;" +
        "      style.textContent = 'body { background-color: #121212 !important; color: #e0e0e0 !important; } html { background-color: #121212 !important; }';" +
        "      (document.head || document.documentElement).appendChild(style);" +
        "    }" +
        "  } catch(e) {}" +
        "})();";

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_browser);
        webView = findViewById(R.id.webview);
        progressBar = findViewById(R.id.progressBar);
        webView.setBackgroundColor(Color.TRANSPARENT);

        String url = getIntent().getStringExtra("url");
        if (url == null) {
            Log.e(TAG, "No URL provided to BrowserActivity");
            finish();
            return;
        }
        appSettings = new AppSettings(this);

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

        applyDarkMode(webSettings);
        applyZoom(webSettings);

        webView.setWebViewClient(new WebViewClient() {
            @Override
            public void onPageStarted(WebView view, String url, Bitmap favicon) {
                view.evaluateJavascript(INJECT_ZOOM_JS, null);
                super.onPageStarted(view, url, favicon);
            }

            @Override
            public void onPageFinished(WebView view, String url) {
                super.onPageFinished(view, url);
                // Inject JavaScript to override color-scheme meta tag
                // This allows BBC's dark mode to work properly with force dark
                view.evaluateJavascript(INJECT_DARK_MODE_JS, null);
                view.evaluateJavascript(INJECT_ZOOM_JS, null);
            }

            @Override
            public boolean shouldOverrideUrlLoading(WebView view, String url) {
                Log.d(TAG, "BrowserActivity loading: " + url);
                view.evaluateJavascript(INJECT_DARK_MODE_JS, null);
                applyDarkMode(webView.getSettings());
                applyZoom(webView.getSettings());
                return false;
            }

            @Override
            public boolean shouldOverrideUrlLoading(WebView view, WebResourceRequest request) {
                String url = request.getUrl().toString();
                Log.d(TAG, "BrowserActivity loading: " + url);
                view.evaluateJavascript(INJECT_DARK_MODE_JS, null);
                applyDarkMode(webView.getSettings());
                applyZoom(webView.getSettings());
                return false;
            }

            @Override
            public WebResourceResponse shouldInterceptRequest(WebView view, WebResourceRequest request) {
                if (request.isForMainFrame()) {
                    pageHost = request.getUrl().getHost();
                }
                // "Ad Blocker" = block third-party requests on external sites.
                // Main frames and first-party requests are always allowed.
                if (appSettings != null && appSettings.isAdBlockEnabled()
                        && !request.isForMainFrame() && pageHost != null) {
                    String host = request.getUrl().getHost();
                    if (host != null && !host.equals(pageHost) && !host.endsWith("." + pageHost)) {
                        return new WebResourceResponse("text/plain", "utf-8", null);
                    }
                }
                return super.shouldInterceptRequest(view, request);
            }

            @Override
            public void onReceivedSslError(WebView view, SslErrorHandler handler, SslError error) {
                handler.proceed();
            }
        });

        webView.setWebChromeClient(new WebChromeClient() {
            @Override
            public void onProgressChanged(WebView view, int newProgress) {
                if (newProgress == 100) progressBar.setVisibility(View.GONE);
                else { progressBar.setVisibility(View.VISIBLE); progressBar.setProgress(newProgress); }
            }
        });

        webView.restoreState(savedInstanceState);
        webView.loadUrl(url);
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        setIntent(intent);
        String url = intent.getStringExtra("url");
        if (url != null && webView != null) {
            applyDarkMode(webView.getSettings());
            applyZoom(webView.getSettings());
            webView.loadUrl(url);
        }
    }

    private void applyZoom(WebSettings webSettings) {
        boolean zoom = appSettings != null && appSettings.isZoomEnabled();
        webSettings.setSupportZoom(zoom);
        webSettings.setBuiltInZoomControls(zoom);
        webSettings.setDisplayZoomControls(false);
    }

    private boolean isNightMode() {
        int nightModeFlags = getResources().getConfiguration().uiMode
                & Configuration.UI_MODE_NIGHT_MASK;
        return nightModeFlags == Configuration.UI_MODE_NIGHT_YES;
    }

    private void applyDarkMode(WebSettings webSettings) {
        boolean night = isNightMode();
        if (WebViewFeature.isFeatureSupported(WebViewFeature.FORCE_DARK)) {
            if (night) {
                webView.setForceDarkAllowed(true);
                WebSettingsCompat.setForceDark(webSettings, WebSettingsCompat.FORCE_DARK_ON);
                if (WebViewFeature.isFeatureSupported(WebViewFeature.ALGORITHMIC_DARKENING)) {
                    WebSettingsCompat.setAlgorithmicDarkeningAllowed(webSettings, true);
                }
            } else {
                WebSettingsCompat.setForceDark(webSettings, WebSettingsCompat.FORCE_DARK_OFF);
                if (WebViewFeature.isFeatureSupported(WebViewFeature.ALGORITHMIC_DARKENING)) {
                    WebSettingsCompat.setAlgorithmicDarkeningAllowed(webSettings, false);
                }
            }
        }
        if (WebViewFeature.isFeatureSupported(WebViewFeature.FORCE_DARK_STRATEGY)) {
            if (night) {
                WebSettingsCompat.setForceDarkStrategy(webSettings,
                        WebSettingsCompat.DARK_STRATEGY_USER_AGENT_DARKENING_ONLY);
            } else {
                WebSettingsCompat.setForceDarkStrategy(webSettings,
                        WebSettingsCompat.DARK_STRATEGY_WEB_THEME_DARKENING_ONLY);
            }
        }
    }

        @Override
    public void onConfigurationChanged(Configuration newConfig) {
        super.onConfigurationChanged(newConfig);
        if (webView != null) {
            applyDarkMode(webView.getSettings());
            webView.setBackgroundColor(Color.TRANSPARENT);
        }
    }

    @Override
    public void onBackPressed() {
        if (webView.canGoBack()) webView.goBack();
        else super.onBackPressed();
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
}
