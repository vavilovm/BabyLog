#!/usr/bin/env bash
set -euo pipefail

apk=${1:?Usage: smoke-test-apk.sh path/to/app.apk}
package=com.mark.babylog
component="$package/.MainActivity"
log_dir=${SMOKE_LOG_DIR:-build/reports/startup}
mkdir -p "$log_dir"

adb install --no-streaming -r "$apk"
adb shell am force-stop "$package"
adb logcat -c
adb shell am start -W -n "$component" | tee "$log_dir/launch.txt"
# Wait beyond the first frame: asynchronous initialization can also fail.
sleep 10
adb logcat -d -b crash > "$log_dir/crash.txt"
adb shell dumpsys activity activities > "$log_dir/activities.txt"
if ! grep -Fxq "Status: ok" "$log_dir/launch.txt" || ! grep -Fxq "Activity: $component" "$log_dir/launch.txt"; then
    cat "$log_dir/crash.txt"
    echo "App did not finish launching its main activity" >&2
    exit 1
fi
if ! adb shell pidof "$package" > "$log_dir/pid.txt"; then
    cat "$log_dir/crash.txt"
    echo "App exited during startup" >&2
    exit 1
fi
if grep -Fq "Process: $package," "$log_dir/crash.txt"; then
    cat "$log_dir/crash.txt"
    exit 1
fi
if ! grep -Eq "(mResumedActivity|topResumedActivity).*com.mark.babylog/.MainActivity" "$log_dir/activities.txt"; then
    cat "$log_dir/launch.txt"
    echo "App did not reach its main activity" >&2
    exit 1
fi
