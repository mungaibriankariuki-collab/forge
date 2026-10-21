#!/usr/bin/env bash
# Installs every AI agent from the Forge Store on the emulator with `forge-store test` and
# screenshots the PASS/FAIL report.
set -x
mkdir -p shots
APK=$(ls *x86_64.apk | head -1)
adb install -r -g "$APK"
shot() { adb exec-out screencap -p > "shots/$1.png"; }
type_line() { adb shell input text "$(echo "$1" | sed 's/ /%s/g')"; adb shell input keyevent 66; }

adb shell am start -n com.termux/.app.TermuxActivity
sleep 90
adb shell input keyevent 4   # Close the welcome setup screen.
sleep 3
type_line "forge-store test claude-code opencode copilot-cli"
# Wait for the test to finish (up to 40 minutes), checking the screen for the summary header.
for i in $(seq 1 80); do
  sleep 30
  adb shell uiautomator dump /sdcard/ui.xml >/dev/null 2>&1
  adb pull /sdcard/ui.xml shots/ui.xml >/dev/null 2>&1
  grep -q "== Summary ==" shots/ui.xml 2>/dev/null && break
done
shot store-1-end
type_line "clear"
type_line "forge-store report"
sleep 3
shot store-2-report
adb logcat -d -b crash > shots/crash.txt
echo "(end of crash log)" >> shots/crash.txt
exit 0
