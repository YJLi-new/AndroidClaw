# Release Size Validation

Date: 2026-03-15

## Baseline before shrinking

- `app/build/outputs/apk/qa/app-qa.apk`: `10,234,149` bytes
- `app/build/outputs/apk/release/app-release-unsigned.apk`: `10,189,093` bytes

## Current repo state

- `qa` and `release` both ship with:
  - `isMinifyEnabled = true`
  - `isShrinkResources = true`
- narrow keep-rule change currently needed:
  - `-dontwarn java.beans.*` for SnakeYAML's unused desktop bean-introspection path on Android

## Current measured artifacts

- `app/build/outputs/apk/qa/app-qa.apk`: `2,109,200` bytes
- `app/build/outputs/apk/release/app-release-unsigned.apk`: `2,096,912` bytes
- `app/build/outputs/bundle/release/app-release.aab`: `4,657,005` bytes

Size reduction:

- `qa` APK: `-8,124,949` bytes from baseline
- `release` APK: `-8,092,181` bytes from baseline

Largest uncompressed entries in the optimized release APK:

- `classes.dex`: `3,231,588` bytes (`1,566,526` compressed)
- `resources.arsc`: `100,268` bytes
- `lib/arm64-v8a/libdatastore_shared_counter.so`: `54,304` bytes
- `lib/x86_64/libdatastore_shared_counter.so`: `53,840` bytes
- `okhttp3/internal/publicsuffix/publicsuffixes.gz`: `41,394` bytes

## Install and smoke truth

The minified `qa` APK is not validated with the shared debug `androidTest` APK. That path is good for debug instrumentation and exact-alarm regression, but it is not a truthful release-like proof once `qa` is shrunk.

Observed failure on the mixed `qa` + shared-debug-androidTest path:

- instrumentation startup crashed before the test body ran
- crash signature: `NoClassDefFoundError: kotlin.jvm.internal.Intrinsics`
- direct app launch still succeeded immediately afterward

Current release-like smoke command:

```bash
ANDROIDCLAW_JAVA_HOME=/path/to/jdk17 ./scripts/run_windows_android_test.sh \
  --variant qa \
  --launch-smoke \
  --avd AndroidClawApi34 \
  --launch-component ai.androidclaw.app/.MainActivity \
  --no-window
```

Observed direct launch result on `AndroidClawApi34`:

```text
Performing Streamed Install
Success
Starting: Intent { cmp=ai.androidclaw.app/.MainActivity }
Status: ok
LaunchState: COLD
Activity: ai.androidclaw.app/.MainActivity
TotalTime: 2812
WaitTime: 2823
Complete
```

## Packaging validation

Local validation completed with:

```bash
./gradlew :app:assembleQa :app:assembleRelease :app:bundleRelease
./gradlew :app:assembleDebug
./gradlew :app:testDebugUnitTest
./gradlew :app:lintDebug
```

All of those commands passed on the current repo state after the shrinking changes.
