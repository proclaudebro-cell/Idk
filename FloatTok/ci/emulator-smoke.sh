#!/usr/bin/env bash
# Emulator smoke test for FloatTok. Drives the real UI with adb and prints evidence to the log:
# overlay window geometry (from dumpsys window), media session state, crashes, and small screenshots
# (base64 JPEG between ===SHOT=== markers, so they can be read from the log without downloading artifacts).
set -uo pipefail

APK="${1:-FloatTok/app/build/outputs/apk/debug/app-debug.apk}"
PKG=app.floattok
OUT=ci-out
mkdir -p "$OUT"
FAILS=0

log() { echo; echo "### $*"; }
fail() { echo "FAIL: $*"; FAILS=$((FAILS + 1)); }
pass() { echo "PASS: $*"; }

shot() {
  local name="$1"
  adb exec-out screencap -p > "$OUT/$name.png" 2>/dev/null || return
  python3 - "$OUT/$name.png" "$name" <<'PY' || true
import sys, base64, io
from PIL import Image
im = Image.open(sys.argv[1]).convert("RGB")
w = 240
im = im.resize((w, int(im.height * w / im.width)))
b = io.BytesIO(); im.save(b, "JPEG", quality=50)
print("===SHOT %s===" % sys.argv[2]); print(base64.b64encode(b.getvalue()).decode()); print("===END===")
PY
}

# Prints "x y w h alpha" for a window title, from the LayoutParams in dumpsys.
win() {
  adb shell dumpsys window windows | python3 -c '
import sys, re
title = sys.argv[1]
txt = sys.stdin.read()
blocks = re.split(r"\n\s*Window #\d+ ", txt)
for b in blocks:
    if ("u0 " + title + "}") in b.split("\n")[0]:
        m = re.search(r"mAttrs=\{\((-?\d+),(-?\d+)\)\((\d+)x(\d+)\)", b)
        a = re.search(r" alpha=([0-9.]+)", b)
        t = re.search(r"fl=[A-Z_ ]*\bNOT_TOUCHABLE\b", b) is not None
        if m:
            print(m.group(1), m.group(2), m.group(3), m.group(4), a.group(1) if a else "1.0", "untouchable" if t else "touchable")
            sys.exit(0)
print("none")
' "$1"
}

tap_text() {
  local text="$1"
  for i in 1 2 3 4 5; do
    adb shell uiautomator dump /sdcard/ui.xml >/dev/null 2>&1
    local b
    b=$(adb shell cat /sdcard/ui.xml | python3 -c '
import sys, re
t = sys.argv[1]
x = sys.stdin.read()
m = re.search(r"text=\"" + re.escape(t) + r"\"[^>]*bounds=\"\[(\d+),(\d+)\]\[(\d+),(\d+)\]\"", x)
if m:
    a, b, c, d = map(int, m.groups()); print((a + c) // 2, (b + d) // 2)
' "$text")
    if [ -n "$b" ]; then adb shell input tap $b; echo "tapped '$text' at $b"; return 0; fi
    sleep 2
  done
  echo "could not find '$text'"; return 1
}

media_state() { adb shell dumpsys media_session | grep -A 14 "package=$PKG" | grep -m1 -oE "state=PlaybackState \{state=[A-Z_]*\(?[0-9]+\)?, position=[0-9]+" || echo "no session"; }
is_playing() { media_state | grep -qE "state=(PLAYING\(3\)|3)," ; }

adb wait-for-device
adb shell settings put global window_animation_scale 0
adb shell input keyevent KEYCODE_WAKEUP
adb shell wm dismiss-keyguard || true
SIZE=$(adb shell wm size | grep -oE '[0-9]+x[0-9]+' | tail -1)
SW=${SIZE%x*}; SH=${SIZE#*x}
DENS=$(adb shell wm density | grep -oE '[0-9]+' | tail -1)
echo "screen ${SW}x${SH} density $DENS"
dp() { echo $(( $1 * DENS / 160 )); }

log "Install and grant overlay permission"
adb install -r -g "$APK" || { fail "install"; exit 1; }
adb shell appops set $PKG SYSTEM_ALERT_WINDOW allow
adb shell dumpsys package $PKG | grep -E "targetSdk|versionName" | head -3

log "Main screen"
adb shell am start -W -n $PKG/.ui.MainActivity
sleep 4
shot main
adb shell uiautomator dump /sdcard/ui.xml >/dev/null 2>&1
adb shell cat /sdcard/ui.xml | grep -q 'text="Paste links"' && pass "first launch opens Import tab" || echo "note: Import tab text not found"

log "Share a direct MP4 link and play it (native backend)"
adb shell am start -W -a android.intent.action.SEND -t text/plain \
  --es android.intent.extra.TEXT "'Sample https://storage.googleapis.com/gtv-videos-bucket/sample/ForBiggerBlazes.mp4'" \
  -n $PKG/.ui.ShareReceiverActivity
sleep 3
shot share_sheet
tap_text "Play now" || fail "Play now button"
sleep 10
adb shell input keyevent KEYCODE_HOME
sleep 3
P=$(win "FloatTok player"); echo "player: $P"
[ "$P" != "none" ] && pass "floating window shown over home screen" || fail "no floating window"
echo "media: $(media_state)"
is_playing && pass "native playback PLAYING" || fail "native playback not playing"
adb shell dumpsys activity services $PKG | grep -E "ServiceRecord|isForeground|foregroundServiceType" | head -5
shot native_over_home

read X Y W H A T <<< "$P"
CX=$((X + W / 2)); CY=$((Y + H / 2))

log "Drag the window"
adb shell input swipe $CX $CY $((CX - 150)) $((CY + 250)) 700
sleep 1.5
P2=$(win "FloatTok player"); echo "after drag: $P2"
read X2 Y2 W2 H2 A2 T2 <<< "$P2"
[ "$W2" = "$W" ] && [ $((X2 - X)) -le -100 ] && [ $((Y2 - Y)) -ge 150 ] && pass "drag moved window" || fail "drag"

log "Resize from the bottom-right corner (smaller)"
adb shell input swipe $((X2 + W2 - $(dp 8))) $((Y2 + H2 - $(dp 8))) $((X2 + W2 - 160)) $((Y2 + H2 - 160)) 900
sleep 1.5
P3=$(win "FloatTok player"); echo "after corner resize: $P3"
read X3 Y3 W3 H3 A3 T3 <<< "$P3"
[ "$W3" -lt "$W2" ] && [ "$X3" = "$X2" ] && [ "$Y3" = "$Y2" ] && pass "corner resize shrank window, anchored top-left" || fail "corner resize"

log "Resize from the left edge (smaller)"
adb shell input swipe $((X3 + $(dp 6))) $((Y3 + H3 / 2)) $((X3 + 2000)) $((Y3 + H3 / 2)) 900
sleep 1.5
P4=$(win "FloatTok player"); echo "after shrinking to minimum: $P4"
read X4 Y4 W4 H4 A4 T4 <<< "$P4"
MINW=$(( SW / 10 ))
[ "$W4" -le $((MINW + 5)) ] && pass "shrinks to ~10% width ($W4 px of $SW)" || fail "min size ($W4 px, expected ~$MINW)"
shot thumbnail_size

log "Double-tap to maximize, then restore"
CX=$((X4 + W4 / 2)); CY=$((Y4 + H4 / 2))
adb shell "input tap $CX $CY & sleep 0.12; input tap $CX $CY"
sleep 1.5
P5=$(win "FloatTok player"); echo "after double tap: $P5"
read X5 Y5 W5 H5 A5 T5 <<< "$P5"
[ "$H5" -gt $((SH / 2)) ] || [ "$W5" -gt $((SW * 9 / 10)) ] && pass "double tap maximized" || fail "double tap maximize (input timing may be too slow on CI)"
shot maximized
CX=$((X5 + W5 / 2)); CY=$((Y5 + H5 / 2))
adb shell "input tap $CX $CY & sleep 0.12; input tap $CX $CY"
sleep 1.5
P6=$(win "FloatTok player"); echo "after second double tap: $P6"
read X6 Y6 W6 H6 A6 T6 <<< "$P6"

log "Fling to the right edge to dock"
CX=$((X6 + W6 / 2)); CY=$((Y6 + H6 / 2))
adb shell input swipe $CX $CY $((SW - 1)) $CY 120
sleep 1.5
PD=$(win "FloatTok player"); TAB=$(win "FloatTok tab")
echo "player while docked: $PD"; echo "tab: $TAB"
read XD YD WD HD AD TD <<< "$PD"
[ "$TAB" != "none" ] && [ "$XD" -ge "$SW" ] && [ "$TD" = "untouchable" ] && pass "docked: window off-screen and untouchable, tab shown" || fail "dock"
sleep 3
echo "media while docked: $(media_state)"
is_playing && pass "keeps playing while docked" || fail "stopped while docked"
shot docked

log "Drag the tab up, then tap it to restore"
read TX TY TW TH TA TT <<< "$TAB"
adb shell input swipe $((TX + TW / 2)) $((TY + TH / 2)) $((TX + TW / 2)) $((TY + TH / 2 - 300)) 600
sleep 1
TAB2=$(win "FloatTok tab"); echo "tab after drag: $TAB2"
read TX2 TY2 TW2 TH2 TA2 TT2 <<< "$TAB2"
[ "$TY2" -lt "$TY" ] && pass "tab moves vertically" || fail "tab vertical move"
adb shell input tap $((TX2 + TW2 / 2)) $((TY2 + TH2 / 2))
sleep 1.5
PR=$(win "FloatTok player"); TAB3=$(win "FloatTok tab"); echo "restored: $PR  tab: $TAB3"
read XR YR WR HR AR TR <<< "$PR"
[ "$TAB3" = "none" ] && [ "$XR" -ge 0 ] && [ "$XR" -lt "$SW" ] && [ "$TR" = "touchable" ] && [ "$WR" = "$W6" ] && pass "tab restores previous size" || fail "restore from tab"

log "Media keys go to the media session"
adb shell input keyevent KEYCODE_MEDIA_PAUSE; sleep 1.5
echo "after MEDIA_PAUSE: $(media_state)"
media_state | grep -qE "state=(PAUSED\(2\)|2)," && pass "media key pauses" || fail "media key pause"
adb shell input keyevent KEYCODE_MEDIA_PLAY; sleep 1.5
is_playing && pass "media key resumes" || fail "media key play"

log "TikTok video through the official embed player"
adb shell am start -W -a android.intent.action.SEND -t text/plain \
  --es android.intent.extra.TEXT "'https://www.tiktok.com/@scout2015/video/6718335390845095173'" \
  -n $PKG/.ui.ShareReceiverActivity
sleep 3
tap_text "Play now" || fail "Play now (TikTok)"
sleep 25
adb shell input keyevent KEYCODE_HOME
sleep 2
M1=$(media_state); sleep 8; M2=$(media_state)
echo "tiktok media: $M1 -> $M2"
echo "$M2" | grep -qE "state=(PLAYING\(3\)|3)," && pass "TikTok playback PLAYING (network-dependent)" || echo "WARN: TikTok embed not playing on the CI emulator (network, region or WebView version)"
shot tiktok_over_home
adb logcat -d -s chromium:I | grep -iE "console|error|x-tiktok|Uncaught" | tail -20

log "Close the player from its close button"
PC=$(win "FloatTok player"); read XC YC WC HC AC TC <<< "$PC"
echo "player before close: $PC"
for attempt in 1 2; do
  # A single tap toggles the controls; the close button sits in the top-left corner.
  adb shell input tap $((XC + WC / 2)) $((YC + HC / 2)); sleep 1
  adb shell input tap $((XC + $(dp 18))) $((YC + $(dp 18))); sleep 2
  PX=$(win "FloatTok player"); [ "$PX" = "none" ] && break
done
echo "after close: $PX"
SVC=$(adb shell dumpsys activity services $PKG | grep -c "ServiceRecord" || true)
[ "$PX" = "none" ] && [ "$SVC" = "0" ] && pass "close removes window and stops the service" || fail "close (window: $PX, services: $SVC)"

log "Crashes"
CR=$(adb logcat -d -b crash)
if echo "$CR" | grep -q "$PKG"; then echo "$CR" | tail -60; fail "app crashed"; else pass "no crashes"; fi
adb logcat -d | grep -E "FloatTok|app.floattok" | grep -iE "exception|error" | tail -30

echo
echo "SMOKE TEST FAILURES: $FAILS"
exit 0
