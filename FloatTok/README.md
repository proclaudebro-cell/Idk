# FloatTok

Watch your favourited TikTok videos in a floating, freely resizable player over any app on Android.
Personal-use utility: no ads, no accounts, no tracking. The library stays on the phone.

Target: Google Pixel 9a on Android 17 (works on Android 11+, `minSdk 30`, `targetSdk 36`). Sideloaded APK; no root.

## Install

1. Copy `FloatTok-<version>-release.apk` to the phone and open it. Android will ask you to allow installs from
   that app (Files, Chrome, …) once.
2. Open FloatTok and tap **Open permission setting**. Switch on **Display over other apps** for FloatTok, then go back.
3. Allow notifications when asked (playback controls live in the media notification).

If Android shows "App not installed" when updating, the new APK was signed with a different key: uninstall the
old copy first (export a backup from **Import → Backup** before uninstalling).

## Getting your favourites in

TikTok's official third-party APIs (Login Kit, Display API) only expose your profile and the videos *you* posted.
There is no supported way for another app to read your Favourites, so **FloatTok does not sync automatically and
never asks for your TikTok password**. Instead, every video shows how it was imported:

| Method | How | Label |
| --- | --- | --- |
| Share sheet | In TikTok: Share → More/Other → FloatTok | Shared |
| Paste links | Import tab → paste one or many links | Pasted link |
| Data export | TikTok → Settings and privacy → Account → **Download your data** (JSON or TXT). Pick the ZIP/JSON/TXT in Import. Only **Favourite Videos** (and collections, if present) are imported; the **Like List** is skipped. | Data export |
| TikTok web (experimental) | Import → Open TikTok web. Sign in on TikTok's own page, open your profile's Favourites tab or a collection, tap **Scroll & collect** / **Import this page**. | TikTok web |
| Local files | Import → Add video files, or share a video file to FloatTok | Local file |

Titles and thumbnails come from TikTok's public oEmbed endpoint. A JSON backup/restore is under Import → Backup.

## Floating player

- **Move**: drag anywhere on the video (also works when a drag starts on a button).
- **Resize**: pinch, or drag any edge or corner. From about 10% to 100% of the screen width, continuously.
- **Double-tap**: maximise / restore. The previous size and position are remembered per orientation.
- **Dock**: fling or drag the window against the left or right edge. It hides and a small tab stays at the edge.
  Tap the tab or pull it inwards to bring the player back exactly where it was. Drag the tab up and down to move it.
  Audio keeps playing while docked unless you choose **When docked → Pause**. The video is never reloaded.
- **Controls** (auto-hide while playing; tap to show): close, title, open app, dock, settings · previous,
  play/pause, next · seek bar, time, **AUTO** (autoplay next) toggle, size/position lock.
- **Player settings** (⋮): autoplay, shuffle, repeat (off / this video / queue), speed 0.5–2×, opacity, video shape
  (original / fit / crop), aspect lock, docked audio, audio-only bar, mute, lock.
- The notification and lock-screen media controls (MediaSession) work too, including headset buttons.
- **Close** stops the foreground service and removes the windows; nothing keeps running.

### Playback backends

- **TikTok videos** play in TikTok's official embed player (`https://www.tiktok.com/player/v1/{id}`) inside a WebView,
  controlled through its documented postMessage API (play, pause, seek, mute; ready/state/time/error events).
  In **Direct** mode (default) FloatTok loads the player page itself, which also allows speed, looping and fit/crop.
  **Embedded** mode uses only the documented API; switch in Settings → TikTok player if Direct misbehaves.
- **Local files and direct video URLs** (`.mp4`, `.m3u8`, …) play with Android's MediaPlayer.

## Building

The app uses only Android framework APIs plus the Kotlin standard library (no AndroidX), so it builds two ways:

- **Gradle** (Android Studio / CI): `./gradlew assembleRelease` (CI signs release with the debug key).
- **Offline toolchain** (used where Google's Maven repository is unreachable):
  `tools/build-local.sh` runs aapt2 → kotlinc → R8 → zipalign → apksigner and verifies the signature;
  `tools/run-tests.sh` runs the JVM unit tests. See the variables at the top of the script.

The signing key for local builds lives in `keystore/` (git-ignored). Keep it: Android only installs updates signed
with the same key.

CI (`.github/workflows/floattok.yml`) runs unit tests, lint, builds both APKs and runs `ci/emulator-smoke.sh` on an
Android 15 emulator, which drives the real UI (share → Play now, drag, corner and edge resize, double-tap, fling to
dock, tab move/restore, media keys, close) and prints window geometry and media-session state to the log.

## Known limits

- No automatic Favourites sync (TikTok doesn't offer one to third-party apps; see above).
- TikTok's embed player always shows some of its own chrome (author, play icon) and can't play private, deleted or
  region-locked videos; FloatTok skips those when autoplay is on.
- Speed control is unavailable in Embedded mode.
- Moving the player out of the way of another app's keyboard isn't possible: overlays can't see other apps' keyboards.
- Photo (slideshow) posts depend on TikTok's embed player supporting them.
