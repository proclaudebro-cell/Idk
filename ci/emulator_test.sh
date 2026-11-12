#!/usr/bin/env bash
# End-to-end test run on an Android emulator: enable the accessibility service, show the
# floating panel, record a tap and a swipe, stop, and check the saved recording.
set -euo pipefail
APK=${1:-app/build/outputs/apk/debug/app-debug.apk}
PKG=app.touchbot
SVC=$PKG/$PKG.AutomationService
OUT=${OUT:-ci-out}
mkdir -p "$OUT"

dump() { adb shell uiautomator dump --windows /sdcard/ui.xml >/dev/null 2>&1 || adb shell uiautomator dump /sdcard/ui.xml >/dev/null; adb pull /sdcard/ui.xml "$OUT/ui.xml" >/dev/null; }

# Prints "x y" for the first node whose text contains $1 (ignores uiautomator dump failures).
find_text() {
  python3 - "$1" "$OUT/ui.xml" <<'PY'
import re, sys, xml.etree.ElementTree as ET
needle, path = sys.argv[1], sys.argv[2]
for n in ET.parse(path).iter("node"):
    if needle in n.get("text", ""):
        l, t, r, b = map(int, re.findall(r"\d+", n.get("bounds")))
        print((l + r) // 2, (t + b) // 2); break
PY
}

tap_text() {
  for i in $(seq 1 15); do
    dump || true
    xy=$(find_text "$1" || true)
    if [ -n "$xy" ]; then echo "tap '$1' at $xy"; adb shell input tap $xy; return 0; fi
    sleep 1
  done
  echo "FAIL: could not find '$1' on screen"; cp "$OUT/ui.xml" "$OUT/last-ui.xml" || true
  adb exec-out screencap -p > "$OUT/fail-$RANDOM.png" || true
  return 1
}
shot() { adb exec-out screencap -p > "$OUT/$1.png" || true; }

adb install -r -t "$APK"
adb shell settings put secure enabled_accessibility_services "$SVC"
adb shell settings put secure accessibility_enabled 1
adb shell am start -n $PKG/.MainActivity
sleep 3
ok=0
for i in $(seq 1 15); do dump || true; grep -q "service is running" "$OUT/ui.xml" && ok=1 && break; sleep 1; done
[ $ok = 1 ] || { shot service-off; echo "FAIL: accessibility service did not start"; exit 1; }
echo "PASS: accessibility service running"; shot 1-main

tap_text "Show floating controls"; sleep 2
tap_text "Rec"; sleep 1                       # start recording
shot 2-recording
read W H < <(adb shell wm size | grep -oE '[0-9]+x[0-9]+' | tail -1 | tr x ' ')
echo "screen ${W}x${H}"
adb shell am start -a android.settings.SETTINGS >/dev/null; sleep 3
tap_text "Network"; sleep 2                                                    # a real click in Settings
adb shell input keyevent KEYCODE_BACK; sleep 2
adb shell input swipe $((W / 2)) $((H * 2 / 3)) $((W / 2)) $((H / 3)) 400; sleep 1   # scroll the list
shot 3-after-input
tap_text "Stop"; sleep 1                      # stop + save

rm -f "$OUT"/rec-*.json
FILES=$(adb shell run-as $PKG ls files/recordings | tr -d '\r')
echo "recordings: $FILES"
[ -n "$FILES" ] || { echo "FAIL: no recording saved"; exit 1; }
for f in $FILES; do adb shell run-as $PKG cat "files/recordings/$f" > "$OUT/rec-$f"; done
cat "$OUT"/rec-*.json; echo
python3 - "$OUT" <<'PY'
import glob, json, sys
rec = json.load(open(sorted(glob.glob(sys.argv[1] + "/rec-*.json"))[0]))
types = [a["type"] for a in rec["actions"]]
print("action types:", types)
assert "tap" in types, "tap was not recorded"
assert "swipe" in types, "scroll was not recorded"
assert types.count("tap") == 1, f"duplicate taps recorded: {types}"
print("PASS: tap and swipe recorded")
PY
