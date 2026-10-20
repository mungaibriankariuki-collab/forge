#!/usr/bin/env bash
# Smoke test on an emulator: install Forge, let the bootstrap install, try the bundled API,
# open the Appearance screen and the tab menu, and save screenshots + crash log in shots/.
set -x
mkdir -p shots
APK=$(ls *x86_64.apk | head -1)
adb install -r -g "$APK"

shot() { adb exec-out screencap -p > "shots/$1.png"; }
type_line() { adb shell input text "$(echo "$1" | sed 's/ /%s/g')"; adb shell input keyevent 66; }

# Tap the centre of the first UI element whose content-desc or text matches $1.
tap_ui() {
  adb shell uiautomator dump /sdcard/ui.xml >/dev/null
  adb pull /sdcard/ui.xml shots/ui.xml >/dev/null
  b=$(grep -o "<node[^>]*\(content-desc\|text\)=\"$1\"[^>]*>" shots/ui.xml | grep -o 'bounds="[^"]*"' | head -1 | grep -o '[0-9]\+')
  set -- $b
  [ -n "$4" ] && adb shell input tap $(( ($1 + $3) / 2 )) $(( ($2 + $4) / 2 ))
}

adb shell am start -n com.termux/.app.TermuxActivity
sleep 90
shot 1-terminal

type_line "pkg install -y termux-api > /dev/null 2>&1; clear; termux-battery-status; termux-toast hello-from-forge"
sleep 150
shot 2-api

type_line "echo second-session-test"
tap_ui "New session"
sleep 5
shot 3-two-tabs

tap_ui "Appearance"
sleep 4
shot 4-appearance
adb shell input swipe 500 1800 500 400 400
sleep 2
shot 5-appearance-scrolled
adb shell input swipe 500 1800 500 200 300
adb shell input swipe 500 1800 500 200 300
sleep 2
shot 6-appearance-themes
adb shell input keyevent 4
sleep 3
shot 7-back-to-terminal

adb logcat -d -b crash > shots/crash.txt
adb logcat -d | grep -iE "forge|termux|AndroidRuntime" | tail -400 > shots/logcat.txt
exit 0
