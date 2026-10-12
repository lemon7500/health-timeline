# Synthetic SQLCipher upgrade probe (not an app release)

`com.healthtimeline.compatprobe` contains only public, fabricated records and a public test key. It never accesses `com.healthtimeline.app`, its Keystore, or the release signing configuration.

On a **fresh disposable emulator**, from the repository root:

```sh
bash scripts/test-android-instrumentation.sh 35
# Use 26 for the minimum-Android compatibility subset.
```

The script builds genuine 4.18.0 / 4.19.1 APKs, seeds the exact production Room v8 schema, preserves committed old WAL by killing the old process, exports old-only fixtures, then installs the candidate with `adb install -r`. Existing probe data and existing fixture exports are refused, never cleared. Tests require the exported fixtures; a normal app test run without generating them is not a valid compatibility run.

API 35 runs the full application instrumentation suite. API 26 runs the production `AppDatabase` compatibility and v1–v5 legacy encrypted backup imports. Files under `.tooling/sqlcipher-compat-fixtures` are ignored, generated synthetic test artifacts, not customer backups. Do not run against a real phone or reuse this public key for medical data.

This verifies genuine encrypted-file compatibility at schema v8. Plaintext historical migration tests, publisher-authenticity verification, real Keystore upgrade, ARM/16 KiB hardware, and true-device notification behavior are separate evidence.
