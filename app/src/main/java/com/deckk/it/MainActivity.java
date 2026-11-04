package com.deckk.it;

import android.app.DownloadManager;
import android.content.ContentValues;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.res.Configuration;
import android.graphics.Bitmap;
import android.graphics.Color;
import android.media.MediaScannerConnection;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Environment;
import android.provider.MediaStore;
import android.util.Base64;
import android.util.Log;
import android.view.ActionMode;
import android.view.Menu;
import android.view.MenuItem;
import android.view.View;
import android.webkit.CookieManager;
import android.webkit.DownloadListener;
import android.webkit.JavascriptInterface;
import android.webkit.SslErrorHandler;
import android.webkit.WebChromeClient;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.net.http.SslError;
import android.widget.ProgressBar;
import android.widget.Toast;
import androidx.appcompat.app.AppCompatActivity;
import androidx.browser.customtabs.CustomTabsIntent;
import androidx.core.content.ContextCompat;
import androidx.webkit.WebSettingsCompat;
import androidx.webkit.WebViewFeature;
import java.io.File;
import java.io.FileOutputStream;
import java.io.FileWriter;
import java.io.IOException;
import java.io.OutputStream;
import java.net.URLDecoder;
import java.util.Locale;
import org.json.JSONArray;
import org.json.JSONObject;

public class MainActivity extends AppCompatActivity {
    private WebView webView;
    private ProgressBar progressBar;
    private static final String BASE_URL = BuildConfig.BASE_URL;
    private static final boolean DEBUG = BuildConfig.DEBUG;
    private static final int MENU_TRANSLATE = 1001;
    private static final int REQ_WRITE_STORAGE = 2001;
    private static final String BRIDGE_DOWNLOAD_NAME = "deckkAppDownload";
    private volatile boolean openUrlsInNewTabEnabled = true;
    private AppSettings appSettings;
    private String pendingDownloadUrl;
    private String pendingDownloadFileName;
    private String pendingDownloadMimeType;
    // A download the page handed us as an inline data: URL, waiting for storage
    // permission on API < 29 (where the public Downloads directory needs it).
    private String pendingNativeDataUrl;
    private String pendingNativeFileName;

    /**
     * deckk.it's backup builds its file in the browser: a Blob, an object URL and an
     * &lt;a download&gt; that is clicked and whose object URL is revoked right afterwards.
     * Android's DownloadManager only accepts http(s) URIs, so following the blob: URL
     * there would throw (and crash the app). Intercept those clicks here instead, read
     * the blob while it is still alive, and pass the bytes to the native bridge.
     * Injected per page load; the flag keeps re-injection (SPA route change, history
     * restore) from wrapping things twice.
     */
    private static final String BLOB_SHIM_JS =
        "(function(){" +
        "  if (window.__deckkBlobShim || typeof deckkAppDownload === 'undefined') return;" +
        "  window.__deckkBlobShim = true;" +
        "  var bridge = deckkAppDownload;" +
        "  document.addEventListener('click', function(e){" +
        "    var a = e.target && e.target.closest ? e.target.closest('a[download]') : null;" +
        "    if (!a) return;" +
        "    var href = a.getAttribute('href') || '';" +
        "    if (href.indexOf('blob:') !== 0) return;" +
        "    var fileName = a.getAttribute('download') || 'download';" +
        "    e.preventDefault();" +
        "    fetch(href).then(function(r){ return r.blob(); }).then(function(b){" +
        "      var reader = new FileReader();" +
        "      reader.onload = function(){ bridge.saveFile(fileName, reader.result); };" +
        "      reader.onerror = function(){ bridge.saveFailed(fileName); };" +
        "      reader.readAsDataURL(b);" +
        "    }).catch(function(){ bridge.saveFailed(fileName); });" +
        "  }, true);" +
        "})();";

    /**
     * JS bridge the page uses to hand a file it produced itself (blob/data download)
     * to the app. Runs on a WebView thread, so it must post UI work back to the main thread.
     */
    private class DownloadBridge {
        @JavascriptInterface
        public void saveFile(String fileName, String dataUrl) {
            handleNativeSave(fileName, dataUrl);
        }

        @JavascriptInterface
        public void saveFailed(String fileName) {
            reportSaveFailed(fileName);
        }
    }

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
        progressBar = findViewById(R.id.progressBar);
        appSettings = new AppSettings(this);

        // The deckk.it page must outlive this activity: external links are opened on
        // top of us in BrowserActivity, and Android is free to destroy this (stopped)
        // activity while it stays hidden. The WebView is retained app-scoped and
        // re-attached here, so returning to this window shows the very same loaded
        // page instead of a re-download and a re-boot of the site.
        webView = WebViewHolder.get();
        boolean retained = webView != null;
        if (!retained) {
            webView = new WebView(this);
            WebViewHolder.set(webView);
        }
        WebViewHolder.attachTo(findViewById(R.id.webview_container));

        // App-only settings bridge: present in the app, absent in a desktop browser,
        // so the deckk.it settings page can show these toggles only here.
        // A retained WebView must be rebound to *this* activity instance, or its page
        // would keep calling the old (destroyed) one's bridges; drop them first.
        webView.removeJavascriptInterface(AppSettings.BRIDGE_NAME);
        webView.removeJavascriptInterface(BRIDGE_DOWNLOAD_NAME);
        webView.addJavascriptInterface(appSettings.new Bridge(), AppSettings.BRIDGE_NAME);
        webView.addJavascriptInterface(new DownloadBridge(), BRIDGE_DOWNLOAD_NAME);

        // Use getDataString() (not getData().toString()) so the exact URL is preserved,
        // including OAuth query/fragment params. Uri.toString() re-encodes the URL and
        // corrupts values such as the Google OAuth state/code (base64url uses +, /, =),
        // which makes the auth handler reject the callback with 400 "malformed".
        String incomingData = getIntent().getDataString();
        String urlToLoad = (incomingData != null && !incomingData.isEmpty()) ? incomingData : BASE_URL;
        // restoreState() only helps after the whole app process was killed: the WebView
        // object is gone and just the back/forward list survives, so the restored current
        // entry re-fetches. When the WebView was retained (activity destroyed, process
        // alive) restoring would swap the live page out for that same re-fetch — which is
        // precisely the re-boot we are avoiding — so the state bundle is ignored.
        if (!retained) {
            webView.restoreState(savedInstanceState);
        }

        configureWebView(urlToLoad);

        if (webView.getUrl() == null || webView.getUrl().isEmpty()) {
            webView.loadUrl(urlToLoad);
        }
    }

    /**
     * Binds settings, clients and listeners to the deckk.it WebView. Runs on every
     * attach (first creation included) so a re-attached WebView can never call back
     * into the previous (destroyed) activity instance.
     */
    private void configureWebView(String urlToLoad) {
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
        webView.setLongClickable(true);
        webView.setHapticFeedbackEnabled(true);
        webView.setOnLongClickListener(new View.OnLongClickListener() {
            @Override
            public boolean onLongClick(View v) {
                WebView.HitTestResult result = webView.getHitTestResult();
                if (result == null) return false;
                String url = result.getExtra();
                int type = result.getType();
                boolean isLink = (type == WebView.HitTestResult.SRC_ANCHOR_TYPE
                        || type == WebView.HitTestResult.SRC_IMAGE_ANCHOR_TYPE)
                        && url != null && (url.startsWith("http://") || url.startsWith("https://"));
                if (!isLink) return false;
                new android.app.AlertDialog.Builder(MainActivity.this)
                    .setTitle(url)
                    .setItems(new String[]{"Copy link", "Share link"}, (d, which) -> {
                        if (which == 0) {
                            android.content.ClipboardManager cm = (android.content.ClipboardManager) getSystemService(android.content.Context.CLIPBOARD_SERVICE);
                            cm.setPrimaryClip(android.content.ClipData.newPlainText("link", url));
                            android.widget.Toast.makeText(MainActivity.this, "Link copied", android.widget.Toast.LENGTH_SHORT).show();
                        } else {
                            android.content.Intent share = new android.content.Intent(android.content.Intent.ACTION_SEND);
                            share.setType("text/plain");
                            share.putExtra(android.content.Intent.EXTRA_TEXT, url);
                            startActivity(android.content.Intent.createChooser(share, "Share link"));
                        }
                    })
                    .show();
                return true;
            }
        });

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
                    } else if (openUrlsInNewTabEnabled) {
                        log("Opening in BrowserActivity: " + url);
                        openInBrowserActivity(url);
                        return true;
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
                    if (openUrlsInNewTabEnabled) {
                        log("Opening non-standard in BrowserActivity: " + url);
                        openInBrowserActivity(url);
                        return true;
                    }
                    openInCustomTab(url);
                    return true;
                }
            }

            @Override
            public void onReceivedSslError(WebView view, SslErrorHandler handler, SslError error) {
                handler.proceed();
            }

            @Override
            public void onPageStarted(WebView view, String url, Bitmap favicon) {
                super.onPageStarted(view, url, favicon);
                injectBlobDownloadShim();
            }

            @Override
            public void onPageFinished(WebView view, String url) {
                super.onPageFinished(view, url);
                injectBlobDownloadShim();
                readNewWindowPreference();
            }
        });

        webView.setWebChromeClient(new WebChromeClient() {
            @Override
            public boolean onCreateWindow(WebView view, boolean isDialog, boolean isUserGesture, android.os.Message resultMsg) {
                log("onCreateWindow called, isDialog: " + isDialog);
                if (openUrlsInNewTabEnabled && isUserGesture) {
                    WebView.HitTestResult result = view.getHitTestResult();
                    String url = result.getExtra();
                    if (url != null && (url.startsWith("http://") || url.startsWith("https://"))) {
                        log("Opening in BrowserActivity: " + url);
                        openInBrowserActivity(url);
                        return true;
                    }
                }
                return false;
            }

            @Override
            public void onProgressChanged(WebView view, int newProgress) {
                if (newProgress == 100) progressBar.setVisibility(View.GONE);
                else { progressBar.setVisibility(View.VISIBLE); progressBar.setProgress(newProgress); }
            }
        });

        webView.setDownloadListener(new DownloadListener() {
            @Override
            public void onDownloadStart(String url, String userAgent,
                                        String contentDisposition, String mimeType, long contentLength) {
                handleDownload(url, contentDisposition, mimeType);
            }
        });

        // Theme and zoom follow the page that is actually showing, falling back to the
        // URL this activity wants to load on a cold start.
        String currentUrl = (webView.getUrl() != null && !webView.getUrl().isEmpty())
                ? webView.getUrl() : urlToLoad;
        applyDarkModeForUrl(webView.getSettings(), currentUrl);
        applyZoomForUrl(currentUrl);
        if (webView.getUrl() != null && !webView.getUrl().isEmpty()) {
            // A re-attached page never fires onPageFinished, so sync the new-window
            // preference now instead of waiting for the next navigation.
            readNewWindowPreference();
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
            if (isInternalUrl(data)) {
                applyDarkModeForUrl(webView.getSettings(), data);
                applyZoomForUrl(data);
                webView.loadUrl(data);
            } else {
                // Never navigate the main deckk.it window away from its page: external
                // links always land in their own (disposable) BrowserActivity.
                if (openUrlsInNewTabEnabled) {
                    openInBrowserActivity(data);
                } else {
                    openInCustomTab(data);
                }
            }
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
            applyZoomForUrl(webView.getUrl());
        }
    }

    @Override
    protected void onDestroy() {
        // Keep the deckk.it WebView alive while the activity is merely being destroyed
        // (memory reclaim, unhandled configuration change) so the next MainActivity
        // re-attaches the same loaded page; drop it only when the window is closed for good.
        if (isFinishing()) {
            WebViewHolder.destroy();
        }
        super.onDestroy();
    }

    /** Opens an external link in its own disposable window. */
    private void openInBrowserActivity(String url) {
        Intent intent = new Intent(MainActivity.this, BrowserActivity.class);
        intent.putExtra("url", url);
        startActivity(intent);
    }

    @Override
    protected void onSaveInstanceState(Bundle outState) {
        webView.saveState(outState);
        super.onSaveInstanceState(outState);
    }

    // No onRestoreInstanceState here on purpose: restoreState() runs in onCreate.
    // Restoring twice (here and there) duplicates the back/forward history entries.

    private void readNewWindowPreference() {
        if (webView == null) return;
        webView.evaluateJavascript(
            "(function() {" +
            "  try {" +
            "    var app = window.rssApp || window.parent.rssApp;" +
            "    if (app && typeof app.openUrlsInNewTabEnabled !== 'undefined') {" +
            "      return app.openUrlsInNewTabEnabled ? 'true' : 'false';" +
            "    }" +
            "  } catch(e) {}" +
            "  return 'true';" +
            "})()",
            value -> {
                boolean enabled = !"false".equals(value);
                openUrlsInNewTabEnabled = enabled;
                log("openUrlsInNewTabEnabled = " + enabled);
            }
        );
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
        applyZoomForUrl(url);
        webView.loadUrl(url);
    }

    private void downloadManagerEnqueue(DownloadManager.Request request) {
        DownloadManager dm = (DownloadManager) getSystemService(DOWNLOAD_SERVICE);
        long id = dm.enqueue(request);
        log("Download enqueued, id=" + id);
    }

    private String deriveFileName(String url, String contentDisposition) {
        String name = null;
        if (contentDisposition != null) {
            String[] parts = contentDisposition.split("filename=", 2);
            if (parts.length == 2) {
                name = parts[1].trim().replace("\"", "");
            }
        }
        if (name == null || name.isEmpty()) {
            try {
                name = Uri.parse(url).getLastPathSegment();
            } catch (Exception ignored) {
            }
        }
        if (name == null || name.isEmpty() || !name.endsWith(".json")) {
            name = "backup.json";
        }
        return name;
    }

    private void handleDownload(String url, String contentDisposition, String mimeType) {
        String fileName = deriveFileName(url, contentDisposition);
        log("handleDownload: " + url + " -> " + fileName);
        if (url == null || url.isEmpty()) return;
        // DownloadManager.Request only accepts http(s); anything else would throw and take
        // the app down, so every scheme that carries its bytes differently is routed here.
        if (url.startsWith("data:")) {
            handleNativeSave(fileName, url);
            return;
        }
        if (url.startsWith("blob:")) {
            // Normally BLOB_SHIM_JS intercepts these clicks in-page before the object URL is
            // revoked. Landing here means the shim never ran (no bridge, page loaded in some
            // other context); the URL is most likely dead already, but try before giving up.
            log("blob: download reached the DownloadListener, shim missed it: " + url);
            webView.evaluateJavascript(blobFetchJs(url, fileName), null);
            return;
        }
        if (url.startsWith("http://") || url.startsWith("https://")) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q
                    || ContextCompat.checkSelfPermission(this,
                            android.Manifest.permission.WRITE_EXTERNAL_STORAGE)
                            == PackageManager.PERMISSION_GRANTED) {
                doDownload(url, fileName, mimeType);
            } else {
                pendingDownloadUrl = url;
                pendingDownloadFileName = fileName;
                pendingDownloadMimeType = mimeType;
                requestPermissions(
                        new String[]{android.Manifest.permission.WRITE_EXTERNAL_STORAGE},
                        REQ_WRITE_STORAGE);
            }
            return;
        }
        log("Ignoring download with unsupported scheme: " + url);
        reportSaveFailed(fileName);
    }

    private void doDownload(String url, String fileName, String mimeType) {
        DownloadManager.Request request = new DownloadManager.Request(Uri.parse(url))
                .setTitle(fileName)
                .setDescription(getString(R.string.download_description))
                .setNotificationVisibility(
                        DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)
                .setAllowedOverMetered(true)
                .setAllowedOverRoaming(true);
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) {
            request.setDestinationInExternalPublicDir(
                    Environment.DIRECTORY_DOWNLOADS, fileName);
        }
        downloadManagerEnqueue(request);
    }

    private void injectBlobDownloadShim() {
        if (webView == null) return;
        webView.evaluateJavascript(BLOB_SHIM_JS, null);
    }

    /** JS that reads a (possibly already revoked) blob URL and reports the result natively. */
    private String blobFetchJs(String blobUrl, String fileName) {
        return "(function(){var u=" + JSONObject.quote(blobUrl) + ",n=" + JSONObject.quote(fileName) + ";" +
                "fetch(u).then(function(r){return r.blob();}).then(function(b){" +
                "var fr=new FileReader();fr.onload=function(){deckkAppDownload.saveFile(n,fr.result);};" +
                "fr.onerror=function(){deckkAppDownload.saveFailed(n);};fr.readAsDataURL(b);})" +
                ".catch(function(){deckkAppDownload.saveFailed(n);});})();";
    }

    /**
     * Saves bytes the page produced locally (data: URL from the blob shim) into the public
     * Downloads folder. On API < 29 the public directory still needs WRITE_EXTERNAL_STORAGE.
     */
    private void handleNativeSave(String fileName, String dataUrl) {
        if (dataUrl == null || !dataUrl.startsWith("data:")) {
            reportSaveFailed(fileName);
            return;
        }
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q
                && ContextCompat.checkSelfPermission(this,
                        android.Manifest.permission.WRITE_EXTERNAL_STORAGE)
                        != PackageManager.PERMISSION_GRANTED) {
            // Called from the JS bridge thread, so the permission dialog must be posted.
            runOnUiThread(() -> {
                pendingNativeDataUrl = dataUrl;
                pendingNativeFileName = fileName;
                pendingDownloadUrl = null;
                requestPermissions(
                        new String[]{android.Manifest.permission.WRITE_EXTERNAL_STORAGE},
                        REQ_WRITE_STORAGE);
            });
            return;
        }
        saveDataUrlDownload(fileName, dataUrl);
    }

    private void saveDataUrlDownload(String fileName, String dataUrl) {
        int comma = dataUrl.indexOf(',');
        if (comma < 0) {
            reportSaveFailed(fileName);
            return;
        }
        String header = dataUrl.substring(0, comma);   // data:<mime>[;base64]
        String payload = dataUrl.substring(comma + 1);
        byte[] data;
        try {
            if (header.contains(";base64")) {
                data = Base64.decode(payload, Base64.DEFAULT);
            } else {
                data = URLDecoder.decode(payload, "UTF-8").getBytes("UTF-8");
            }
        } catch (Exception e) {
            log("Could not decode downloaded data URL: " + e);
            reportSaveFailed(fileName);
            return;
        }
        String mimeType = "application/octet-stream";
        int colon = header.indexOf(':');
        int end = header.indexOf(';');
        if (colon >= 0) {
            mimeType = header.substring(colon + 1, end < 0 ? header.length() : end).trim();
        }
        if (mimeType.isEmpty()) mimeType = "application/octet-stream";
        saveBytesToDownloads(fileName, mimeType, data);
    }

    private void saveBytesToDownloads(String fileName, String mimeType, byte[] data) {
        String name = sanitizeFileName(fileName);
        Uri target = null;
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                ContentValues values = new ContentValues();
                values.put(MediaStore.Downloads.DISPLAY_NAME, name);
                values.put(MediaStore.Downloads.MIME_TYPE, mimeType);
                values.put(MediaStore.Downloads.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS);
                values.put(MediaStore.Downloads.IS_PENDING, 1);
                target = getContentResolver().insert(
                        MediaStore.Downloads.EXTERNAL_CONTENT_URI, values);
                if (target == null) throw new IOException("MediaStore insert returned null");
                try (OutputStream out = getContentResolver().openOutputStream(target)) {
                    if (out == null) throw new IOException("openOutputStream returned null");
                    out.write(data);
                }
                values.clear();
                values.put(MediaStore.Downloads.IS_PENDING, 0);
                getContentResolver().update(target, values, null, null);
            } else {
                File dir = Environment.getExternalStoragePublicDirectory(
                        Environment.DIRECTORY_DOWNLOADS);
                File file = new File(dir, name);
                try (FileOutputStream out = new FileOutputStream(file)) {
                    out.write(data);
                }
                // Legacy files are not indexed automatically, so the Downloads app would
                // not see the backup until a reboot.
                MediaScannerConnection.scanFile(this, new String[]{file.getAbsolutePath()},
                        new String[]{mimeType}, null);
            }
            log("Saved download: " + name);
            reportSaved(name);
        } catch (Exception e) {
            log("Saving download failed: " + e);
            if (target != null) getContentResolver().delete(target, null, null);
            reportSaveFailed(name);
        }
    }

    private String sanitizeFileName(String name) {
        if (name == null || name.trim().isEmpty()) return "download.bin";
        String sanitized = name.trim().replaceAll("[\\\\/:*?\"<>|]", "_");
        if (sanitized.length() > 120) {
            sanitized = sanitized.substring(sanitized.length() - 120);
        }
        return sanitized;
    }

    private void reportSaved(String fileName) {
        runOnUiThread(() -> Toast.makeText(this,
                getString(R.string.download_saved, fileName), Toast.LENGTH_LONG).show());
    }

    private void reportSaveFailed(String fileName) {
        runOnUiThread(() -> Toast.makeText(this,
                getString(R.string.download_failed, fileName), Toast.LENGTH_LONG).show());
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode == REQ_WRITE_STORAGE
                && grantResults.length > 0
                && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
            if (pendingDownloadUrl != null) {
                doDownload(pendingDownloadUrl, pendingDownloadFileName, pendingDownloadMimeType);
                pendingDownloadUrl = null;
                pendingDownloadFileName = null;
                pendingDownloadMimeType = null;
            } else if (pendingNativeDataUrl != null) {
                String name = pendingNativeFileName;
                String dataUrl = pendingNativeDataUrl;
                pendingNativeFileName = null;
                pendingNativeDataUrl = null;
                saveDataUrlDownload(name, dataUrl);
            }
        } else if (requestCode == REQ_WRITE_STORAGE
                && (grantResults.length == 0 || grantResults[0] != PackageManager.PERMISSION_GRANTED)) {
            pendingNativeDataUrl = null;
            pendingNativeFileName = null;
            Toast.makeText(this,
                    "Storage permission required to save backups. Please enable it in Settings.",
                    Toast.LENGTH_LONG).show();
        }
    }

    /**
     * Two-finger pinch zoom is enabled only on external websites; deckk.it keeps
     * its own (non-scalable) layout.
     */
    private void applyZoomForUrl(String url) {
        if (webView == null || appSettings == null) return;
        boolean zoom = appSettings.isZoomEnabled() && !isInternalUrl(url);
        WebSettings settings = webView.getSettings();
        settings.setSupportZoom(zoom);
        settings.setBuiltInZoomControls(zoom);
        settings.setDisplayZoomControls(false);
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
