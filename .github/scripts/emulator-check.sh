#!/usr/bin/env bash
# Smoke test on an emulator: install Forge, let the bootstrap install, try the bundled API,
# use the tabs bar and the Appearance screen, and save screenshots + logs in shots/.
set -x
mkdir -p shots
APK=$(ls *x86_64.apk | head -1)
adb install -r -g "$APK"

SIZE=$(adb shell wm size | grep -o '[0-9]*x[0-9]*' | tail -1)
W=${SIZE%x*}; H=${SIZE#*x}

shot() { adb exec-out screencap -p > "shots/$1.png"; }
# Only plain words: `input text` goes through the device shell.
type_line() { adb shell input text "$(echo "$1" | sed 's/ /%s/g')"; adb shell input keyevent 66; }
scroll_down() { adb shell input swipe $((W/2)) $((H*8/10)) $((W/2)) $((H*2/10)) 300; sleep 1; }

# Tap the centre of the first UI element whose content-desc or text is exactly $1. Returns 1 if absent.
tap_ui() {
  adb shell uiautomator dump /sdcard/ui.xml >/dev/null
  adb pull /sdcard/ui.xml shots/ui.xml >/dev/null
  b=$(grep -o "<node[^>]*\(content-desc\|text\)=\"$1\"[^>]*>" shots/ui.xml | grep -o 'bounds="[^"]*"' | head -1 | grep -o '[0-9]\+')
  set -- $b
  [ -z "$4" ] && return 1
  adb shell input tap $(( ($1 + $3) / 2 )) $(( ($2 + $4) / 2 ))
}
# Scroll until $1 is visible, then tap it.
scroll_tap() { for i in $(seq 1 40); do tap_ui "$1" && return 0; scroll_down; done; return 1; }

adb shell am start -n com.termux/.app.TermuxActivity
sleep 90
shot 1-welcome-setup

# First-run wizard: untick phone storage (needs a settings screen), install Essentials + Python.
tap_ui "Phone storage"
sleep 1
tap_ui "Install 2 kits"
sleep 300
shot 1b-setup-running
adb shell input keyevent 66
sleep 4
shot 1c-terminal

type_line "pkg install -y termux-api"
sleep 150
type_line "clear"
type_line "termux-battery-status"
sleep 15
type_line "termux-toast hello"
sleep 2
shot 2-api

tap_ui "Sessions and settings"
sleep 2
tap_ui "⬇ Setup"
sleep 3
shot 2b-setup-installed
adb shell input keyevent 4
sleep 2

tap_ui "New session"
sleep 5
shot 3-two-tabs

tap_ui "Appearance"
sleep 4
shot 4-appearance
scroll_tap "Coder"
sleep 1
shot 5-keyboard-coder
scroll_tap "Dracula"
sleep 2
shot 6-theme-dracula
adb shell input keyevent 4
sleep 4
shot 7-terminal-after-changes

adb logcat -d -b crash > shots/crash.txt
echo "(end of crash log)" >> shots/crash.txt
adb logcat -d | grep -iE "forge|termux|AndroidRuntime" | tail -400 > shots/logcat.txt
exit 0
