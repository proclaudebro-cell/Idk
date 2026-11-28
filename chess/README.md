# Chess Coach (Android)

Play chess against a random bot, with a built-in coach. No API keys, no accounts, no network needed.

## What it does
- **Play**: tap a piece, then tap a highlighted square. You play against a bot that makes random legal moves.
- **Stuck? Hint**: explains what to do next and why. Checks, mate in one, free material, attacked pieces, then opening or general advice. Shows arrows on the board in *Pictures* mode.
- **Live commentary**: after each of your moves the coach says whether it was best, good, or a mistake, and why.
- **Review**: every move of the current game, graded against the best move, with the better alternative.
- **Profile**: records of archived games, your most common mistake, and the teaching style you prefer.
  - *Pictures*: arrows on the board.
  - *Words*: longer explanations and the principle behind each move.
  - *Steps*: a checklist to run through each turn.

The coach uses a 2-ply material search and rule-based checks. It is not a strong engine and will not find deep combinations. The bot is random, so it will often hang pieces, which is good practice for spotting free material.

Game progress and profile are stored in the app's local WebView storage on the device.

## Layout
- `app/src/main/assets/engine.js`: rules, legal moves, check/mate, SAN (perft-verified).
- `app/src/main/assets/coach.js`: move ranking, grading, hints, mistake tags.
- `app/src/main/assets/app.js`, `index.html`, `style.css`: UI and persistence.
- `app/src/main/java/app/chess/MainActivity.kt`: WebView host.
- `tests/`: Node tests for engine and coach.

## Build
```
node --test chess/tests/engine.test.js chess/tests/coach.test.js
cd chess && ./gradlew assembleDebug    # APK: app/build/outputs/apk/debug/app-debug.apk
```
Requires JDK 17 and the Android SDK (or open the `chess` folder in Android Studio). CI builds the APK on every change under `chess/`.
