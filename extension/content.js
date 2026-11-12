(() => {
  'use strict';
  if (window.__skipit) return; window.__skipit = true;

  const DEFAULTS = { enabled: true, threshold: -40, minSilence: 0.35, silenceSpeed: 6, rewind: 0.1 };
  let cfg = { ...DEFAULTS };
  const store = (typeof chrome !== 'undefined' && chrome.storage && chrome.storage.sync) || null;
  if (store) {
    store.get(DEFAULTS, v => { cfg = { ...DEFAULTS, ...v }; });
    chrome.storage.onChanged.addListener((ch) => { for (const k in ch) cfg[k] = ch[k].newValue; });
  }

  const states = new WeakMap();
  let ctx;

  /* A video can only be analysed safely if its audio is same-origin (blob:/MSE, same site)
     or CORS-enabled, and not DRM-protected. Otherwise Web Audio would output silence. */
  function analysable(v) {
    if (v.mediaKeys) return 'drm';
    const src = v.currentSrc || v.src || '';
    if (!src || src.startsWith('blob:') || src.startsWith('data:')) return 'ok';
    try {
      if (new URL(src, location.href).origin === location.origin) return 'ok';
    } catch (e) {}
    return v.crossOrigin ? 'ok' : 'cors';
  }

  function attach(v) {
    if (states.has(v)) return;
    const why = analysable(v);
    if (why !== 'ok') { states.set(v, { blocked: why }); return; }
    ctx = ctx || new AudioContext();
    const src = ctx.createMediaElementSource(v);
    const an = ctx.createAnalyser(); an.fftSize = 1024;
    const gain = ctx.createGain();
    src.connect(an); an.connect(gain); gain.connect(ctx.destination);
    const s = { v, an, gain, buf: new Float32Array(an.fftSize), silentSince: null,
                skipping: false, userRate: v.playbackRate || 1, setting: false, saved: 0, last: performance.now() };
    v.addEventListener('ratechange', () => { if (!s.setting && !s.skipping) s.userRate = v.playbackRate; });
    states.set(v, s);
  }

  function level(s) {
    s.an.getFloatTimeDomainData(s.buf);
    let sum = 0; for (let i = 0; i < s.buf.length; i++) sum += s.buf[i] * s.buf[i];
    return 20 * Math.log10(Math.sqrt(sum / s.buf.length) + 1e-9);
  }

  function setRate(s, r) { s.setting = true; s.v.playbackRate = r; s.setting = false; }

  function endSkip(s) {
    s.skipping = false;
    setRate(s, s.userRate);
    s.gain.gain.setTargetAtTime(1, ctx.currentTime, 0.005);
    // detection lag at high speed swallows a little speech; step back to compensate
    const v = s.v, target = v.currentTime - cfg.rewind;
    if (cfg.rewind > 0 && target > 0) {
      for (let i = 0; i < v.buffered.length; i++)
        if (target >= v.buffered.start(i) && target <= v.buffered.end(i)) { v.currentTime = target; break; }
    }
  }

  function step(v) {
    const s = states.get(v);
    if (!s || s.blocked) return;
    const now = performance.now(), dt = (now - s.last) / 1000; s.last = now;
    s.mutedNow = v.muted || v.volume === 0;   // muted element => analyser hears silence; don't guess
    if (v.paused || v.ended || !cfg.enabled || s.mutedNow) { if (s.skipping) endSkip(s); s.silentSince = null; return; }
    if (ctx.state === 'suspended') ctx.resume();
    if (level(s) < cfg.threshold) {
      if (s.silentSince === null) s.silentSince = now;
      if (!s.skipping && now - s.silentSince >= cfg.minSilence * 1000) {
        s.skipping = true; setRate(s, cfg.silenceSpeed);
        s.gain.gain.setTargetAtTime(0, ctx.currentTime, 0.005);
      }
    } else {
      s.silentSince = null;
      if (s.skipping) endSkip(s);
    }
    if (s.skipping) s.saved += dt * (v.playbackRate - 1) / v.playbackRate;
  }

  /* ---------- badge on top of the video ---------- */
  const host = document.createElement('div');
  host.style.cssText = 'position:fixed;z-index:2147483647;pointer-events:none;display:none';
  const badge = host.attachShadow({ mode: 'open' });
  badge.innerHTML = '<style>div{font:600 12px system-ui;color:#fff;background:rgba(15,17,21,.8);padding:3px 8px;border-radius:6px}</style><div id=t></div>';
  const label = badge.getElementById('t');

  function paint(v) {
    const s = states.get(v), r = v.getBoundingClientRect();
    if (!s || r.width < 200 || r.height < 120 || r.bottom < 0 || r.top > innerHeight) { host.style.display = 'none'; return; }
    const parent = document.fullscreenElement || document.documentElement;
    if (host.parentNode !== parent) parent.appendChild(host);
    host.style.display = 'block';
    host.style.left = r.left + 8 + 'px'; host.style.top = r.top + 8 + 'px';
    if (s.blocked) label.textContent = '⏭ SkipIt: can\'t analyse this video (' + (s.blocked === 'drm' ? 'DRM' : 'cross-site') + ')';
    else if (!cfg.enabled) label.textContent = '⏭ SkipIt off (Alt+S)';
    else if (s.mutedNow) label.textContent = '⏭ SkipIt: unmute to skip silence';
    else label.textContent = (s.skipping ? '⏩ ' : '⏭ ') + 'saved ' + fmt(s.saved);
  }
  const fmt = t => Math.floor(t / 60) + ':' + String(Math.floor(t % 60)).padStart(2, '0');

  /* ---------- wiring ---------- */
  document.addEventListener('play', e => { if (e.target instanceof HTMLVideoElement) attach(e.target); }, true);
  document.querySelectorAll('video').forEach(v => { if (!v.paused) attach(v); });

  setInterval(() => {
    let main = null;
    document.querySelectorAll('video').forEach(v => {
      step(v);
      if (!v.paused && states.has(v)) main = v;
    });
    if (main) paint(main); else host.style.display = 'none';
  }, 25);

  addEventListener('keydown', e => {
    if (e.altKey && e.code === 'KeyS' && !/input|textarea/i.test(e.target.tagName)) {
      cfg.enabled = !cfg.enabled;
      if (store) store.set({ enabled: cfg.enabled });
    }
  }, true);
})();
