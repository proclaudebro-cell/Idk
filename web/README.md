# SkipIt — free video silence skipper

Static, zero-dependency web app (installable PWA). Everything runs in the browser; no uploads, no server.

- Detects silence with the Web Audio API (adjustable threshold, minimum gap, padding).
- Preview skips the dead air live; waveform shows what gets cut.
- Export re-records only the kept parts (MediaRecorder) to WebM.

Run locally: `cd web && python3 -m http.server 8000`, then open http://localhost:8000.
Host free on GitHub Pages / Netlify / Cloudflare Pages by serving the `web/` folder.

Not included yet: filler-word ("um", "uh") detection, which needs in-browser speech recognition (e.g. Whisper via transformers.js).
