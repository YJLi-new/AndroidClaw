# PERFORMANCE

> This file records the current performance posture for AndroidClaw and the repo-supported paths for keeping startup and navigation fast.

## Goals

- keep startup work bounded and local
- avoid keeping screen-only flows hot when no UI is observing them
- make the eventual Baseline Profile path explicit without breaking the repo on unsupported hosts
- keep release-size decisions explicit and measured

## Current posture

- startup stays local:
  - no provider network call is performed on app launch
  - startup maintenance trims old scheduler artifacts and restores pending work only
- top-level screen state is now collected with `SharingStarted.WhileSubscribed(5_000)` in:
  - `ChatViewModel`
  - `TasksViewModel`
  - `HealthViewModel`
- Baseline Profile dependencies are not checked in yet because this WSL Gradle runtime cannot fetch uncached AndroidX benchmark/profile artifacts from Google Maven without a TLS handshake failure
- an installable `qa` build lane now exists for release-like local validation without production signing keys
- `qa` and `release` now both ship with code shrinking and resource shrinking enabled
- minified `qa` should be validated with direct install/launch smoke; the shared debug `androidTest` APK remains the debug-oriented instrumentation lane
- production lint stays enabled, but test-source lint is disabled because AGP 8.13 + Kotlin FIR crashes while analyzing `debugUnitTest` and `debugAndroidTest` sources in this environment
- the lint fast loop also disables network-backed version-check detectors so validation stays deterministic and local

## Baseline Profile support

Current coverage targets:

- app launch
- open chat
- open tasks
- open skills
- open settings
- open health
- send one message with `FakeProvider`

Current known trade-off:

- the collection module is intentionally not checked in yet because it would break the current repo build on this workstation
- the blocker is environmental, not architectural: Gradle can build the app, but it cannot fetch new AndroidX benchmark/profile artifacts from Google Maven in this WSL runtime
- the next collection pass should happen either on a host where Gradle can resolve Google Maven cleanly or with a preseeded local mirror for those artifacts

## Validation lanes

Fast repo validation:

```bash
./gradlew :app:assembleDebug
./gradlew :app:testDebugUnitTest
./gradlew :app:lintDebug
./gradlew :app:assembleQa
./gradlew :app:assembleRelease
./gradlew :app:bundleRelease
```

Future Baseline Profile task discovery, once the dependency-resolution blocker is cleared:

```bash
./gradlew :app:tasks --all | rg 'BaselineProfile|profile'
```

Windows-emulator preflight for the existing device lane:

```bash
ANDROIDCLAW_JAVA_HOME=/path/to/jdk17 ./scripts/check_host_prereqs.sh --required-avd AndroidClawApi34 --required-avd AndroidClawApi31
```

## Size and shrinking

Current release posture:

- `qa` is installable locally via debug signing and is the optimization/test target
- `qa` currently has `isMinifyEnabled = true` and `isShrinkResources = true`
- `release` now also has `isMinifyEnabled = true` and `isShrinkResources = true`
- release-like launch validation should use:
  - `./scripts/run_windows_android_test.sh --variant qa --launch-smoke --avd AndroidClawApi34 --launch-component ai.androidclaw.app/.MainActivity`

Current decision:

- keep debug instrumentation and exact-alarm regression on the shared debug `androidTest` path
- keep the keep-rule surface narrow and evidence-based
- use direct launch smoke for minified `qa` instead of mixing the shared debug `androidTest` APK with a shrunk release-like app

Current measured artifacts:

- baseline before shrinking:
  - `app/build/outputs/apk/qa/app-qa.apk`: `10,234,149` bytes
  - `app/build/outputs/apk/release/app-release-unsigned.apk`: `10,189,093` bytes
- current optimized outputs:
  - `app/build/outputs/apk/qa/app-qa.apk`: `2,109,200` bytes
  - `app/build/outputs/apk/release/app-release-unsigned.apk`: `2,096,912` bytes
  - `app/build/outputs/bundle/release/app-release.aab`: `4,657,005` bytes

Largest uncompressed entries in the current optimized release APK:

- `classes.dex`: `3,231,588` bytes (`1,566,526` compressed)
- `resources.arsc`: `100,268` bytes
- `lib/arm64-v8a/libdatastore_shared_counter.so`: `54,304` bytes
- `lib/x86_64/libdatastore_shared_counter.so`: `53,840` bytes
- `okhttp3/internal/publicsuffix/publicsuffixes.gz`: `41,394` bytes

Keep rules currently required:

- narrow `-dontwarn java.beans.*` rules for SnakeYAML's unused desktop bean-introspection path on Android
