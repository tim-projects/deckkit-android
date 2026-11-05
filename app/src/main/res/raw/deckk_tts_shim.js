/**
 * Web Speech synthesis shim for the deckk.it Android app.
 *
 * Android WebView does not implement window.speechSynthesis at all (MDN lists every
 * member as unsupported in WebView: https://crbug.com/40417848), so a page that
 * drives a "read aloud" / speed reader through the Web Speech API sees an undefined
 * API inside this app. This script installs a speechSynthesis implementation backed
 * by the app's native TextToSpeech engine through the window.deckkTtsBridge object
 * that MainActivity adds. The bridge is absent in a desktop browser, where the page
 * simply keeps using the real API.
 *
 * Implemented surface: utterance construction, a queue with single-flight playback,
 * cancel/pause/resume, voices, and the start/end/error/boundary events. Playback
 * always goes through the bridge one utterance at a time so per-utterance
 * rate/pitch/voice are applied right before that utterance instead of racing with
 * Android's own TTS queue.
 *
 * Word boundaries come from the engine's UtteranceProgressListener.onRangeStart(),
 * which only exists on API 26+. On API 24/25 (and on older engines that report no
 * ranges) they are synthesised here instead: the utterance is split into words, each
 * word's start is announced on a timer that is re-anchored on the real clock as it
 * goes, and the pace learned from previous utterances seeds the first one. Real
 * ranges win whenever the engine reports them.
 */
(function () {
  'use strict';

  // The app registers the bridge before the first page load, but a synchronous
  // inline script can still run first, so retry briefly instead of giving up.
  var BRIDGE_WAIT_ATTEMPTS = 20;

  // Rough English pace at rate 1 (~15 chars/s), used until a real measurement exists.
  var DEFAULT_MS_PER_CHAR = 65;
  // A page's numeric rate is only a rough guide to how fast the engine will speak:
  // engines typically clamp or ignore values far from 1, so limit how much the
  // expected duration is stretched or compressed by it. An estimate that is badly
  // wrong is the one thing the schedule cannot recover from on its own.
  var MAX_RATE_FACTOR = 1.75;
  // Only re-anchor the synthetic schedule on the real clock once this much of the
  // utterance has genuinely elapsed: dividing a few milliseconds by a small fraction
  // amplifies noise and bursts the remaining boundaries.
  var ANCHOR_FRACTION = 0.15;
  var ANCHOR_MS = 100;
  var MIN_TIMER_MS = 20;

  var now = (typeof performance !== 'undefined' && performance.now)
    ? function () { return performance.now(); }
    : function () { return Date.now(); };

  var seq = 0;            // utterance ids the native side correlates events by
  var queue = [];         // utterances waiting behind the current one
  var current = null;      // utterance the engine is speaking (kept while paused)
  var voices = [];
  var paused = false;
  var pauseOffset = 0;    // char index the current playback should restart from

  var rangeState = null; // null = not tested yet, true = engine reports ranges,
                         // false = synthesise boundaries
  var rates = {};        // "voiceURI|lang" -> { chars, ms, rate } of a completed utterance

  var SYNTH;              // filled in below; callbacks reference it lazily
  var bridge = null;      // the app's native bridge, once it shows up

  function uid() { return 'u' + (++seq); }

  // Enough of EventTarget: pages use either `on<type>` or addEventListener.
  function addEventListener(type, fn) {
    (this.listeners[type] = this.listeners[type] || []).push(fn);
  }

  function removeEventListener(type, fn) {
    var l = this.listeners[type] || [];
    var i = l.indexOf(fn);
    if (i >= 0) l.splice(i, 1);
  }

  function fire(obj, type, ev) {
    ev = ev || {};
    ev.type = type;
    ev.target = obj;
    var on = obj['on' + type];
    if (typeof on === 'function') { try { on.call(obj, ev); } catch (e) { /* page's handler */ } }
    var l = (obj.listeners[type] || []).slice();
    for (var i = 0; i < l.length; i++) {
      try { l[i].call(obj, ev); } catch (e) { /* page's handler */ }
    }
  }

  function Utterance(text) {
    this.listeners = {};
    this.text = (text === undefined || text === null) ? '' : String(text);
    this.lang = '';
    this.voice = null;
    this.voiceURI = '';
    this.volume = 1;
    this.rate = 1;
    this.pitch = 1;
    this._deckkId = uid();
  }

  Utterance.prototype.addEventListener = addEventListener;
  Utterance.prototype.removeEventListener = removeEventListener;

  function Voice(spec) {
    this.name = spec.name;
    this.lang = spec.locale;
    this.voiceURI = spec.name;
    this.localService = !spec.network;
    this.default = !!spec['default'];
  }

  /** Word positions in the original text, with a weight approximating its duration. */
  function wordSchedule(text) {
    var words = [];
    var re = /[^\s]+/g, m, total = 0;
    while ((m = re.exec(text))) {
      var word = m[0];
      var last = word.charAt(word.length - 1);
      // Sentence-final punctuation is where a voice spends extra time.
      var weight = word.length + (/[.,;:!?]/.test(last) ? 6 : /[)\]"']/.test(last) ? 2 : 0);
      words.push({ charIndex: m.index, charLength: word.length, weight: weight,
                   startsAt: total });
      total += weight;
    }
    return { words: words, total: total };
  }

  function rateKey(u) {
    return (u.voiceURI || '') + '|' + u.lang;
  }

  /** How much a page's rate is trusted, capped in both directions. */
  function paceOf(rate) {
    var r = Math.max(rate, 0.1);
    return Math.min(MAX_RATE_FACTOR, Math.max(1 / MAX_RATE_FACTOR, r));
  }

  function measuredMsPerChar(u) {
    var m = rates[rateKey(u)];
    if (!m || m.chars <= 0) return null;
    // Earlier measurements were taken at whatever rate they were spoken at.
    return (m.ms / m.chars) * (paceOf(num(m.rate, 1)) / paceOf(num(u.rate, 1)));
  }

  function startSynthetic(u) {
    var s = wordSchedule(u.text);
    if (!s.words.length) return;
    var known = measuredMsPerChar(u);
    var msPerChar = known || DEFAULT_MS_PER_CHAR / paceOf(num(u.rate, 1));
    u._syn = {
      words: s.words,
      total: s.total,
      duration: Math.max(250, s.total * msPerChar),
      index: 0,
      // playbackStart is moved by resume() so the elapsed time survives a pause
      playbackStart: now(),
      elapsed: 0,
      timer: null,
      stopped: false
    };
    announceNext(u);
  }

  function stopSynthetic(u) {
    if (!u._syn) return;
    u._syn.stopped = true;
    if (u._syn.timer !== null) { clearTimeout(u._syn.timer); u._syn.timer = null; }
    return u._syn;
  }

  function announceNext(u) {
    var s = u._syn;
    if (!s || s.stopped || paused) return;
    if (s.index >= s.words.length) { s.timer = null; return; }
    var word = s.words[s.index];
    s.index++;
    var fraction = s.total ? word.startsAt / s.total : 0;
    var real = now() - s.playbackStart;
    if (fraction > ANCHOR_FRACTION && real > ANCHOR_MS) {
      // Re-anchor: what we have really heard says this is how long the utterance is.
      s.duration = real / fraction;
    }
    fire(u, 'boundary', { charIndex: word.charIndex, charLength: word.charLength });
    // Resume() replays the native engine from here, so it must match this word.
    pauseOffset = word.charIndex;
    if (s.index >= s.words.length) { s.timer = null; return; }
    var nextFraction = s.words[s.index].startsAt / s.total;
    var delay = s.playbackStart + nextFraction * s.duration - now();
    s.timer = setTimeout(function () { announceNext(u); }, Math.max(MIN_TIMER_MS, delay));
  }

  function rememberPace(u, ms) {
    var m = rates[rateKey(u)];
    // Keep the longer sample: a short utterance is a noisy measurement.
    if (m && m.chars > u._syn.total) return;
    rates[rateKey(u)] = { chars: u._syn.total, ms: ms, rate: num(u.rate, 1) };
  }

  /** A page may hand us anything; only a positive number makes sense here. */
  function num(value, fallback) {
    var n = typeof value === 'number' ? value : parseFloat(value);
    return (isFinite(n) && n > 0) ? n : fallback;
  }

  function start(u) {
    current = u;
    bridge.speak(JSON.stringify({
      id: u._deckkId,
      text: u.text,
      lang: u.lang || '',
      voiceURI: u.voiceURI || (u.voice && u.voice.voiceURI) || '',
      rate: num(u.rate, 1),
      pitch: num(u.pitch, 1),
      volume: num(u.volume, 1)
    }));
  }

  function next() {
    var u = queue.shift();
    if (u) start(u);
  }

  /** Ends one utterance and starts whatever the page queued behind it. */
  function finish(u, type, ev) {
    if (current === u) current = null;
    stopSynthetic(u);
    fire(u, type, ev);
    // A site handler may have queued (or started) something in onend.
    if (!current && !paused) next();
  }

  function speak(u) {
    if (!u || !u.text) return;
    if (paused) { queue.push(u); return; }
    if (current) { queue.push(u); return; }
    start(u);
  }

  function cancel() {
    var affected = [];
    if (current) affected.push(current);
    while (queue.length) affected.push(queue.shift());
    current = null;
    paused = false;
    pauseOffset = 0;
    bridge.cancel();
    // Android drops everything it was speaking, so the page must be told each of
    // its utterances finished even though it never saw them start.
    for (var i = 0; i < affected.length; i++) {
      stopSynthetic(affected[i]);
      fire(affected[i], 'end');
    }
  }

  function pause() {
    if (paused || !current) return;
    paused = true;
    var s = current._syn;
    if (s && !s.stopped) {
      // Freeze the schedule where it is; resume() rewinds the clock to here.
      s.elapsed = now() - s.playbackStart;
      s.stopped = true;
      if (s.timer !== null) { clearTimeout(s.timer); s.timer = null; }
    }
    // Android has no pause(); the engine stops the utterance and the shim asks for
    // it to be replayed from the last boundary on resume.
    bridge.pause();
    fire(SYNTH, 'pause');
  }

  function resume() {
    if (!paused) return;
    paused = false;
    bridge.resume(pauseOffset);
    fire(SYNTH, 'resume');
    if (current && current._syn) {
      // Resume the synthetic schedule from where the pause froze it.
      var s = current._syn;
      s.playbackStart = now() - s.elapsed;
      s.stopped = false;
      announceNext(current);
    } else if (!current) {
      next();
    }
  }

  var native = {
    onVoices: function (json) {
      var spec;
      try { spec = JSON.parse(json); } catch (e) { spec = []; }
      voices = (spec || []).map(function (v) { return new Voice(v); });
      fire(SYNTH, 'voiceschanged');
    },

    onStart: function (id) {
      if (!current || current._deckkId !== id) return;
      // Synthesise boundaries unless the engine is known to report its own; the
      // first real range cancels the synthetic schedule and disables them.
      if (rangeState !== true) startSynthetic(current);
      fire(current, 'start');
    },

    onEnd: function (id) {
      // While paused the engine only reports the stop we asked for; the utterance
      // is still current and resume() replays what is left of it.
      if (paused) return;
      if (!current || current._deckkId !== id) return;
      var s = stopSynthetic(current);
      if (s && rangeState === null && s.words.length) rangeState = false;
      if (s && s.duration > 0) rememberPace(current, now() - s.playbackStart);
      finish(current, 'end');
    },

    onError: function (id, code) {
      if (paused) return;
      if (!current || current._deckkId !== id) return;
      var u = current;
      stopSynthetic(u);
      fire(u, 'error', { error: code || 'synthesis-failed' });
      // An error still terminates the utterance; without `end` a reader that only
      // waits for one of the two would stall on the failed item.
      finish(u, 'end');
    },

    onRange: function (id, start, end) {
      if (!current || current._deckkId !== id) return;
      if (current._syn && !current._syn.stopped) {
        // The engine reports real word positions; stop guessing.
        rangeState = true;
        stopSynthetic(current);
      }
      pauseOffset = start;
      fire(current, 'boundary', { charIndex: start, charLength: end - start });
    }
  };

  function getVoices() { return voices.slice(); }

  SYNTH = {
    listeners: {},
    speak: speak,
    cancel: cancel,
    pause: pause,
    resume: resume,
    getVoices: getVoices,
    onvoiceschanged: null,
    addEventListener: addEventListener,
    removeEventListener: removeEventListener
  };

  Object.defineProperties(SYNTH, {
    speaking: { get: function () { return current !== null; } },
    paused: { get: function () { return paused; } },
    pending: { get: function () { return queue.length; } }
  });

  function install(retries) {
    var b = window.deckkTtsBridge;
    if (!b) {
      if (retries > 0) setTimeout(function () { install(retries - 1); }, 100);
      return;   // no native bridge: leave whatever the page expects alone
    }
    if (window.__deckkTtsShim) return;
    bridge = b;

    window.__deckkTtsShim = true;
    window.__deckkTtsNative = native;
    window.SpeechSynthesisUtterance = Utterance;
    window.speechSynthesis = SYNTH;

    b.init();
    var spec = [];
    try { spec = JSON.parse(b.voicesJson()) || []; } catch (e) { spec = []; }
    voices = spec.map(function (v) { return new Voice(v); });
    // Voices are also pushed later when the engine finishes connecting, the same
    // way Chrome populates them asynchronously after the first empty getVoices().
    if (voices.length) setTimeout(function () { fire(SYNTH, 'voiceschanged'); }, 0);
  }

  install(BRIDGE_WAIT_ATTEMPTS);
})();
