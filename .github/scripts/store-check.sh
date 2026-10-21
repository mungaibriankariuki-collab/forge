#!/usr/bin/env bash
# Installs one group of Forge Store items on the emulator with `forge-store test` and screenshots
# the PASS/FAIL report. GROUP selects a line of .github/scripts/store-groups.txt.
set -x
mkdir -p shots
IDS=$(sed -n "${GROUP:-1}p" .github/scripts/store-groups.txt)
APK=$(ls *x86_64.apk | head -1)
adb install -r -g "$APK"
shot() { adb exec-out screencap -p > "shots/store-g${GROUP:-1}-$1.png"; }
type_line() { adb shell input text "$(echo "$1" | sed 's/ /%s/g')"; adb shell input keyevent 66; }

adb shell am start -n com.termux/.app.TermuxActivity
sleep 90
# Close the welcome setup screen only if it is showing (an extra Back would leave Forge),
# then make sure the terminal is in front before typing.
adb shell uiautomator dump /sdcard/ui.xml >/dev/null 2>&1
adb pull /sdcard/ui.xml shots/ui.xml >/dev/null 2>&1
grep -q "Welcome to Forge" shots/ui.xml 2>/dev/null && adb shell input keyevent 4
sleep 2
adb shell am start -n com.termux/.app.TermuxActivity
sleep 4
adb shell input tap 160 300   # Focus the terminal.
sleep 1
type_line "forge-store test $IDS"
# Wait (up to ~2 h) until the test sets the tab title to "store-done", which shows in the tabs bar.
for i in $(seq 1 260); do
  sleep 30
  adb shell uiautomator dump /sdcard/ui.xml >/dev/null 2>&1
  adb pull /sdcard/ui.xml shots/ui.xml >/dev/null 2>&1
  grep -q "store-done" shots/ui.xml 2>/dev/null && break
done
shot 1-end
type_line "clear"
type_line "forge-store report"
sleep 3
adb shell input keyevent 111   # Hide the keyboard so more lines fit.
sleep 2
shot 2-report
# Collect the per-item install logs the test copied to shared storage.
adb pull /sdcard/forge-test "shots/logs-g${GROUP:-1}" >/dev/null 2>&1 && tar -czf "shots/store-g${GROUP:-1}-logs.tar.gz" -C shots "logs-g${GROUP:-1}"
exit 0
