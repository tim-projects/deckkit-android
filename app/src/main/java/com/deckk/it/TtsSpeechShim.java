package com.deckk.it;

import android.content.Context;
import android.content.res.Resources;
import android.util.Log;
import android.webkit.WebView;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;

/**
 * Injects {@code res/raw/deckk_tts_shim.js} into the deckk.it WebView so a page that
 * drives its "read aloud" / speed reader with the Web Speech synthesis API talks to
 * the app's native engine (see {@link TtsEngine}) instead of an API that WebView does
 * not implement at all.
 */
final class TtsSpeechShim {
    private static final String TAG = "TtsSpeechShim";

    private static volatile String script;

    private TtsSpeechShim() {}

    static void inject(WebView webView, Context context) {
        if (webView == null) return;
        String js = script(context);
        if (js != null) webView.evaluateJavascript(js, null);
    }

    private static synchronized String script(Context context) {
        String cached = script;
        if (cached != null) return cached;
        StringBuilder sb = new StringBuilder();
        try (InputStream in = context.getResources().openRawResource(R.raw.deckk_tts_shim);
             BufferedReader reader = new BufferedReader(
                     new InputStreamReader(in, StandardCharsets.UTF_8))) {
            String line;
            while ((line = reader.readLine()) != null) sb.append(line).append('\n');
        } catch (IOException | Resources.NotFoundException e) {
            Log.e(TAG, "could not read the speech shim", e);
            return null;
        }
        script = sb.toString();
        return script;
    }
}
