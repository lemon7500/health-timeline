#!/usr/bin/env bash
set -euo pipefail

# Run only on a fresh disposable emulator; the probe uses a separate app ID and
# public synthetic data. Never run this workflow against the customer's phone.
case "${1:-}" in 26|35) api="$1" ;; *) echo 'Expected API 26 or 35' >&2; exit 1 ;; esac
repo_root="$(cd "$(dirname "$0")/.." && pwd)"
cd "$repo_root"
log_dir="${RUNNER_TEMP:-$repo_root/.tooling/sqlcipher-compat-logs}"
mkdir -p "$log_dir"
bash scripts/test-sqlcipher-compat.sh 2>&1 | tee "$log_dir/sqlcipher-compatibility.log"

# Current API runs the complete migration/repository/Compose suite. Minimum API
# exercises the real production Room integration and actual v1-v5 backup imports.
arguments=()
if [[ "$api" == '26' ]]; then
  arguments+=('-Pandroid.testInstrumentationRunnerArguments.class=com.healthtimeline.app.data.EncryptedDatabaseCompatibilityTest,com.healthtimeline.app.backup.LegacyCryptoBackupCompatibilityTest')
fi
./gradlew :androidApp:verifyBuildToolchain :androidApp:verifyRuntimeCrypto \
  :androidApp:connectedDebugAndroidTest "${arguments[@]}" --stacktrace 2>&1 \
  | tee "$log_dir/android-instrumentation.log"
