# SkipIt extension — skip silence on any website's video

Works on top of videos playing in the page (YouTube, course sites, VOD players, etc.), not just files.
While a video plays, it listens to the audio and speeds through silent stretches (default 6×, muted),
returning to normal the instant speech resumes. A small badge on the video shows time saved.

## Install (free, no store needed)
1. Chrome/Edge/Brave: open `chrome://extensions`, enable **Developer mode**.
2. **Load unpacked** → select this `extension/` folder.
3. Play a video. Toggle with **Alt+S**; tune threshold / wait / speed from the toolbar popup.

## Known limits
- **Muted videos are left alone** (a muted element gives the analyser silence). Unmute to skip.
- **DRM video (Netflix, Disney+, etc.) and cross-site media without CORS** can't be analysed; the badge says so.
- Needs ~0.35 s of silence before it kicks in (adjustable) — it reacts live, with no look-ahead.
- Filler words ("um") aren't detected; this is loudness-based.
