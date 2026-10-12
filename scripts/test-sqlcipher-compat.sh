#!/usr/bin/env bash
set -euo pipefail

# ONLY for a fresh disposable emulator. It never touches com.healthtimeline.app.
# Existing probe data are refused, not cleared. Exported fixtures are public synthetic data.
repo_root="$(cd "$(dirname "$0")/.." && pwd)"
cd "$repo_root"
if [[ "$(adb shell getprop ro.kernel.qemu | tr -d '\r')" != '1' ]]; then
  echo 'This synthetic upgrade probe is restricted to a disposable emulator, never a real phone.' >&2; exit 1
fi
export_dir="${1:-$repo_root/.tooling/sqlcipher-compat-fixtures}"
if [[ "$export_dir" != /* ]]; then export_dir="$repo_root/$export_dir"; fi
case "$export_dir" in "$repo_root/.tooling/"*) ;; *) echo 'Fixture export must be under repository .tooling/' >&2; exit 1;; esac
if [[ -e "$export_dir/closed.db" || -e "$export_dir/wal.db" ]]; then
  echo 'Refusing to overwrite previously generated encrypted compatibility fixtures.' >&2; exit 1
fi
package='com.healthtimeline.compatprobe'
dump_seed_failure() {
  adb shell run-as "$package" cat files/seed-error.txt 2>/dev/null || true
  # This tag is restricted to public synthetic fixture setup, not production logs.
  adb logcat -d -s 'SyntheticSQLCompat:E' '*:S' || true
}
if adb shell pm path "$package" | tr -d '\r' | grep -q '^package:'; then
  echo 'Probe already installed: use a fresh disposable emulator, do not clear production or probe data.' >&2; exit 1
fi
./gradlew :sqlcipherCompat:assembleBaselineDebug :sqlcipherCompat:assembleCandidateDebug \
  :sqlcipherCompat:assembleCandidateDebugAndroidTest --stacktrace
adb install -r tools/sqlcipher-compat/build/outputs/apk/baseline/debug/sqlcipherCompat-baseline-debug.apk
adb shell am start -W -n "$package/.SeedActivity"
ready=false
for attempt in $(seq 1 90); do
  # `test` is a shell builtin without a standalone binary on API 26. Use the
  # universally available Android ls executable, not run-as ... test.
  if adb shell run-as "$package" /system/bin/ls files/seed-error.txt >/dev/null 2>&1; then
    dump_seed_failure
    exit 1
  fi
  if adb shell run-as "$package" /system/bin/ls files/seed-ready.json >/dev/null 2>&1; then ready=true; break; fi
  sleep 1
done
if [[ "$ready" != true ]]; then echo 'Old-engine seed timed out.' >&2; dump_seed_failure; exit 1; fi
adb shell run-as "$package" cat files/seed-ready.json
# Killing, rather than helper.close(), preserves old committed WAL for candidate recovery.
adb shell am force-stop "$package"
mkdir -p "$export_dir/attachments"
for name in closed.db wal.db wal.db-wal; do
  adb exec-out run-as "$package" cat "databases/$name" > "$export_dir/$name"
  test -s "$export_dir/$name"
done
for name in closed.snapshot.json wal.snapshot.json closed.sha256 seed-ready.json; do
  adb exec-out run-as "$package" cat "files/$name" > "$export_dir/$name"
done
for name in synthetic-live.png synthetic-recycled.pdf; do
  adb exec-out run-as "$package" cat "files/attachments/$name" > "$export_dir/attachments/$name"
done
if adb shell run-as "$package" /system/bin/ls databases/wal.db-shm >/dev/null 2>&1; then
  adb exec-out run-as "$package" cat databases/wal.db-shm > "$export_dir/wal.db-shm"
fi
# install -r is the actual old-to-new binary replacement: never uninstall or pm clear.
adb install -r tools/sqlcipher-compat/build/outputs/apk/candidate/debug/sqlcipherCompat-candidate-debug.apk
adb install -r tools/sqlcipher-compat/build/outputs/apk/androidTest/candidate/debug/sqlcipherCompat-candidate-debug-androidTest.apk
log_file="$export_dir/probe-instrumentation.log"
adb shell am instrument -w -r "$package.test/androidx.test.runner.AndroidJUnitRunner" | tee "$log_file"
grep -Eq 'OK \(2 tests\)' "$log_file"
if grep -Eq 'FAILURES!!!|INSTRUMENTATION_FAILED|Process crashed|shortMsg=' "$log_file"; then exit 1; fi
echo 'SQLCipher 4.18.0 -> 4.19.1 replacement verified (closed encrypted v8 DB and committed old WAL).'
echo "Unmodified baseline-only synthetic fixtures exported to $export_dir"
