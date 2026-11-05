package com.deckk.it;

import android.content.Context;
import android.media.AudioAttributes;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.speech.tts.TextToSpeech;
import android.speech.tts.UtteranceProgressListener;
import android.speech.tts.Voice;
import android.util.Log;
import android.webkit.JavascriptInterface;
import android.webkit.WebView;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * App-scoped Android TextToSpeech engine that backs the Web Speech synthesis API.
 *
 * Android WebView does not implement {@code window.speechSynthesis} at all (MDN lists
 * every member of the API as unsupported in WebView, https://crbug.com/40417848), so a
 * page-side "read aloud" / speed reader is dead code inside this app. This class is
 * the native half of the bridge; {@code res/raw/deckk_tts_shim.js} is the Web Speech
 * half, so deckk.it keeps using the very same API it already uses.
 *
 * Deliberately app-scoped and not activity-scoped: MainActivity keeps its WebView alive
 * across activity recreation (see WebViewHolder), so an engine owned by an activity
 * would be shut down every time the user opened an external link and came back. It is
 * released only when the window finishes for good.
 *
 * Speech is single-flight by design. The shim owns the utterance queue and only ever
 * asks for one utterance at a time, so each utterance's rate/pitch/voice is applied
 * immediately before it instead of racing with Android's own TTS queue, where the last
 * set value would win for the whole queue.
 */
final class TtsEngine {
    private static final String TAG = "TtsEngine";

    /** Bridge object name; must match the shim in res/raw/deckk_tts_shim.js. */
    static final String BRIDGE_NAME = "deckkTtsBridge";

    /** Root object the engine calls into the page; must match the shim. */
    private static final String SHIM_GLOBAL = "window.__deckkTtsNative";

    private final Context appContext;
    private final Handler main = new Handler(Looper.getMainLooper());

    private TextToSpeech tts;
    private boolean connecting;
    private boolean ready;
    /** Utterance delivered before init finished; replayed once the engine is up. */
    private final List<Pending> initQueue = new ArrayList<>();
    /** Utterance currently in flight, kept while paused so it can be replayed. */
    private Pending current;
    /** True between a pause() and the resume() (or cancel) that ends it. */
    private boolean paused;
    /**
     * Android-side id of the attempt currently handed to the engine, and the attempt
     * it belongs to. Each attempt (including a resumed one) gets its own id, so a
     * stopped attempt can report its end long after it was replayed without that
     * late event being mistaken for the replay's.
     */
    private final Map<String, Pending> attempts = new HashMap<>();
    private int attemptSeq;
    /** WebView the callbacks are evaluated against; replaced on every activity attach. */
    private WebView target;

    private static volatile TtsEngine instance;

    /** One utterance exactly as the page described it. */
    private static final class Pending {
        final String jsId;
        final String text;
        final float rate;
        final float pitch;
        final float volume;
        final String lang;
        final String voiceURI;

        Pending(String jsId, String text, float rate, float pitch, float volume,
                String lang, String voiceURI) {
            this.jsId = jsId;
            this.text = text;
            this.rate = rate;
            this.pitch = pitch;
            this.volume = volume;
            this.lang = lang;
            this.voiceURI = voiceURI;
        }
    }

    private TtsEngine(Context appContext) {
        this.appContext = appContext.getApplicationContext();
    }

    /** Returns the app-scoped engine, starting it if needed. */
    static TtsEngine get(Context context) {
        TtsEngine e = instance;
        if (e == null) {
            synchronized (TtsEngine.class) {
                e = instance;
                if (e == null) {
                    e = new TtsEngine(context);
                    instance = e;
                }
            }
        }
        e.start();
        return e;
    }

    /**
     * Drops the engine. Call only when no WebView is left to talk into: a live page
     * keeps its utterance queue, and speech is expected to survive activity
     * recreation and the user browsing in BrowserActivity.
     */
    static synchronized void release() {
        TtsEngine e = instance;
        if (e == null) return;
        instance = null;
        e.shutDown();
    }

    /** Points the engine at the WebView it is currently speaking into. */
    void attach(WebView webView) {
        synchronized (this) {
            target = webView;
        }
        start();
    }

    private synchronized void start() {
        if (tts != null || connecting) return;
        connecting = true;
        tts = new TextToSpeech(appContext, status -> onInit(status));
    }

    private synchronized void shutDown() {
        if (tts != null) {
            tts.stop();
            tts.shutdown();
            tts = null;
        }
        connecting = false;
        ready = false;
        target = null;
        current = null;
        paused = false;
        attempts.clear();
        initQueue.clear();
    }

    private void onInit(int status) {
        main.post(() -> onInitMain(status));
    }

    private synchronized void onInitMain(int status) {
        connecting = false;
        if (tts == null) return;   // released while the engine was connecting
        if (status != TextToSpeech.SUCCESS) {
            Log.e(TAG, "TTS engine failed to connect, status=" + status);
            return;
        }
        ready = true;
        tts.setAudioAttributes(new AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_MEDIA)
                .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                .build());
        tts.setOnUtteranceProgressListener(new UtteranceProgressListener() {
            @Override
            public void onStart(String utteranceId) {
                // utteranceId is an attempt id; map it back before touching the page.
                Pending p = liveAttempt(utteranceId);
                if (p != null) emit(SHIM_GLOBAL + ".onStart(" + jsArg(p.jsId) + ")");
            }

            @Override
            public void onDone(String utteranceId) {
                main.post(() -> onDoneMain(utteranceId));
            }

            @Override
            public void onError(String utteranceId, int errorCode) {
                main.post(() -> onErrorMain(utteranceId, errorCode));
            }

            /** Legacy form, still delivered by some engines. */
            @Override
            public void onError(String utteranceId) {
                main.post(() -> onErrorMain(utteranceId, TextToSpeech.ERROR));
            }

            /**
             * Reached only for stops of ours (ignored: the utterance stays current
             * so resume() can replay it) and for engine interruptions.
             */
            @Override
            public void onStop(String utteranceId, boolean interrupted) {
                main.post(() -> onErrorMain(utteranceId, TextToSpeech.ERROR_SYNTHESIS));
            }

            /**
             * Only exists from API 26; on API 24/25 the platform's listener has no
             * such method, so this override is simply never called and the shim
             * synthesises word boundaries instead (see the shim's startSynthetic()).
             */
            @Override
            public void onRangeStart(String utteranceId, int start, int end, int frame) {
                Pending p = liveAttempt(utteranceId);
                if (p != null) {
                    emit(SHIM_GLOBAL + ".onRange(" + jsArg(p.jsId)
                            + "," + start + "," + end + ")");
                }
            }
        });
        if (!initQueue.isEmpty()) {
            // The page is waiting on the utterance it queued before the engine
            // connected; the shim is single-flight, so there is at most one.
            Pending p = initQueue.get(0);
            initQueue.clear();
            speakNow(p, 0);
        }
        voicesChanged();
    }

    @JavascriptInterface
    public void init() {
        start();
    }

    /** Speaks one utterance; the caller queues behind it when busy. */
    @JavascriptInterface
    public void speak(final String utteranceJson) {
        final Pending p;
        try {
            JSONObject o = new JSONObject(utteranceJson);
            p = new Pending(o.optString("id"), o.optString("text"),
                    (float) o.optDouble("rate", 1d), (float) o.optDouble("pitch", 1d),
                    (float) o.optDouble("volume", 1d), o.optString("lang"),
                    o.optString("voiceURI"));
        } catch (JSONException e) {
            Log.e(TAG, "unreadable utterance: " + utteranceJson, e);
            return;
        }
        if (p.jsId.isEmpty() || p.text.isEmpty()) return;
        main.post(() -> speakOnMain(p));
    }

    /**
     * Android has no pause(). The current utterance is stopped and kept, so
     * {@link #resume(int)} can replay the remainder starting at {@code offset}.
     */
    @JavascriptInterface
    public void pause() {
        main.post(this::pauseOnMain);
    }

    @JavascriptInterface
    public void resume(final int offset) {
        main.post(() -> resumeOnMain(offset));
    }

    @JavascriptInterface
    public void cancel() {
        main.post(this::cancelOnMain);
    }

    /** Voices as JSON; empty until the engine has connected. */
    @JavascriptInterface
    public synchronized String voicesJson() {
        if (tts == null || !ready) return "[]";
        try {
            Set<Voice> engineVoices = tts.getVoices();
            if (engineVoices == null || engineVoices.isEmpty()) {
                // Engines that publish no voice list still speak; expose the engine
                // default so a page's "no voices -> disable the reader" check passes.
                return new JSONArray().put(voiceJson(tts.getVoice())).toString();
            }
            JSONArray out = new JSONArray();
            for (Voice v : engineVoices) out.put(voiceJson(v));
            return out.toString();
        } catch (Exception e) {
            Log.e(TAG, "could not list TTS voices", e);
            return "[]";
        }
    }

    private static JSONObject voiceJson(Voice v) throws JSONException {
        JSONObject o = new JSONObject();
        o.put("name", v.getName());
        o.put("locale", localeTag(v.getLocale()));
        o.put("quality", v.getQuality());
        o.put("network", v.isNetworkConnectionRequired());
        return o;
    }

    private static String localeTag(Locale locale) {
        return locale == null ? "" : locale.toLanguageTag();
    }

    private void speakOnMain(Pending p) {
        synchronized (this) {
            if (tts == null) return;
            if (!ready) {
                initQueue.add(p);
                return;
            }
            speakNow(p, 0);
        }
    }

    private synchronized void pauseOnMain() {
        if (tts == null || current == null || paused) return;
        paused = true;
        tts.stop();
    }

    private synchronized void resumeOnMain(int offset) {
        if (tts == null || current == null || !paused) return;
        paused = false;
        speakNow(current, offset);
    }

    private synchronized void cancelOnMain() {
        if (tts == null) return;
        paused = false;
        current = null;
        attempts.clear();
        tts.stop();
        // The shim delivers the `end` events for everything it had queued.
    }

    private void onDoneMain(String attemptId) {
        Pending p = resolveAttempt(attemptId);
        if (p == null) return;
        current = null;
        paused = false;
        emit(SHIM_GLOBAL + ".onEnd(" + jsArg(p.jsId) + ")");
    }

    private void onErrorMain(String attemptId, int errorCode) {
        Pending p = resolveAttempt(attemptId);
        if (p == null) return;
        Log.e(TAG, "utterance " + p.jsId + " failed, errorCode=" + errorCode);
        current = null;
        paused = false;
        emit(SHIM_GLOBAL + ".onError(" + jsArg(p.jsId) + ","
                + jsArg("synthesis-failed") + ")");
    }

    /** The attempt a progress callback belongs to, or null if it must not be reported. */
    private synchronized Pending liveAttempt(String attemptId) {
        return attempts.get(attemptId);
    }

    /**
     * Like {@link #liveAttempt}, but also claims the attempt for a terminal event
     * (end/error) so a duplicate callback cannot report it a second time.
     *
     * Returns null for events the page must not hear about: a stop we asked for
     * (pause/cancel - the page already knows, or nothing is left) and a callback left
     * over from an attempt we moved past.
     */
    private synchronized Pending resolveAttempt(String attemptId) {
        // paused stays true until resume()/cancel(): every callback in between
        // belongs to the stop we just asked for.
        if (paused) return null;
        return attempts.remove(attemptId);
    }

    /** Applies one utterance's voice settings and speaks it from {@code offset}. */
    private void speakNow(Pending p, int offset) {
        String text = p.text;
        if (offset > 0 && offset < p.text.length()) text = p.text.substring(offset);
        String attemptId = p.jsId + "#" + (++attemptSeq);
        current = p;
        paused = false;
        applyVoice(p);
        Bundle params = new Bundle();
        params.putFloat(TextToSpeech.Engine.KEY_PARAM_VOLUME, p.volume);
        attempts.clear();
        attempts.put(attemptId, p);
        if (tts.speak(text, TextToSpeech.QUEUE_FLUSH, params, attemptId) != TextToSpeech.SUCCESS) {
            attempts.remove(attemptId);
            current = null;
            Log.e(TAG, "engine refused utterance " + p.jsId);
            emit(SHIM_GLOBAL + ".onError(" + jsArg(p.jsId) + ","
                    + jsArg("synthesis-failed") + ")");
        }
    }

    private void applyVoice(Pending p) {
        try {
            if (p.rate > 0f) tts.setSpeechRate(p.rate);
            if (p.pitch > 0f) tts.setPitch(p.pitch);
            if (!p.lang.isEmpty()) {
                Locale locale = Locale.forLanguageTag(p.lang);
                if (tts.isLanguageAvailable(locale) >= TextToSpeech.LANG_AVAILABLE) {
                    tts.setLanguage(locale);
                }
            }
            if (!p.voiceURI.isEmpty()) {
                Set<Voice> voices = tts.getVoices();
                if (voices != null) {
                    for (Voice v : voices) {
                        if (p.voiceURI.equals(v.getName())) {
                            tts.setVoice(v);
                            break;
                        }
                    }
                }
            }
        } catch (Exception e) {
            Log.e(TAG, "could not apply voice settings", e);
        }
    }

    private void voicesChanged() {
        emit(SHIM_GLOBAL + ".onVoices(" + jsArg(voicesJson()) + ")");
    }

    private void emit(String js) {
        final WebView webView;
        synchronized (this) {
            webView = target;
        }
        if (webView == null) return;
        main.post(() -> {
            try {
                webView.evaluateJavascript(js, null);
            } catch (Exception e) {
                Log.w(TAG, "callback into a torn-down WebView", e);
            }
        });
    }

    private static String jsArg(String value) {
        try {
            return JSONObject.quote(value == null ? "" : value);
        } catch (JSONException e) {
            return "\"\"";
        }
    }
}
