#!/usr/bin/env bash
set -uo pipefail

if ! command -v adb >/dev/null 2>&1; then
  echo "ERROR: adb is not available." >&2
  exit 2
fi

mapfile -t qa_devices < <(adb devices | awk 'NR > 1 && $2 == "device" { print $1 }')
if [[ "${#qa_devices[@]}" -ne 1 ]]; then
  echo "ERROR: Exactly one authorized QA device/emulator must be connected; found ${#qa_devices[@]}." >&2
  exit 2
fi
export ANDROID_SERIAL="${qa_devices[0]}"
unset qa_devices
echo "QA Android target resolved: exactly one authorized device/emulator."

set +e
gradle :app:connectedDebugAndroidTest --rerun-tasks --no-build-cache --stacktrace
status=$?
echo "connectedDebugAndroidTest exit code: $status"
if [[ -d app/build/outputs/androidTest-results ]]; then
  find app/build/outputs/androidTest-results -type f -name '*.xml' -print -exec sed -n '1,360p' {} \;
else
  echo "No Android test XML result directory found."
fi
if [[ "$status" -ne 0 ]]; then
  exit "$status"
fi

set -e
app_apk="app/build/outputs/apk/debug/app-debug.apk"
test_apk="app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk"
test -f "$app_apk"
test -f "$test_apk"
adb install -r -t "$app_apk"
adb install -r -t "$test_apk"

run_case() {
  local method="$1"
  local output
  output="$(adb shell am instrument -w -r \
    -e class "app.ownplay.mobile.ProcessDeathPersistenceInstrumentedTest#${method}" \
    app.ownplay.mobile.test/androidx.test.runner.AndroidJUnitRunner)"
  echo "$output"
  grep -q "OK (1 test)" <<<"$output"
}

run_case aSeedDurableStateForProcessDeath
adb shell am start -W -n app.ownplay.mobile/.MainActivity
before_pid="$(adb shell pidof app.ownplay.mobile | tr -d '\r')"
test -n "$before_pid"

adb shell am force-stop app.ownplay.mobile
sleep 1
after_stop_pid="$(adb shell pidof app.ownplay.mobile 2>/dev/null | tr -d '\r' || true)"
test -z "$after_stop_pid"

adb shell am start -W -n app.ownplay.mobile/.MainActivity
after_pid="$(adb shell pidof app.ownplay.mobile | tr -d '\r')"
test -n "$after_pid"
test "$after_pid" != "$before_pid"

run_case bVerifyDurableStateAfterRelaunch
echo "PASS: Room and DataStore state survived force-stop and process relaunch ($before_pid -> $after_pid)."
