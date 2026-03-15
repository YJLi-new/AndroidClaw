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
- `qa` now carries the shrinking experiment (`isMinifyEnabled = true`, `isShrinkResources = true`) while `release` remains unshrunk until the `qa` evidence lane is fully closed
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
- `release` remains `isMinifyEnabled = false` and `isShrinkResources = false` until the `qa` shrink lane is fully evidenced
- release-like launch validation should use:
  - `./scripts/run_windows_android_test.sh --variant qa --launch-smoke --avd AndroidClawApi34 --launch-component ai.androidclaw.app/.MainActivity`

Current decision:

- keep release shrinking disabled until there is a dedicated `qa` validation pass with install/launch evidence
- keep debug instrumentation and exact-alarm regression on the shared debug `androidTest` path
- do not turn on `release` shrinking speculatively just to claim a size win

Current measured artifacts:

- `app/build/outputs/apk/qa/app-qa.apk`: `2,500,568` bytes after the current `qa` shrinking pass
- `app/build/outputs/apk/release/app-release-unsigned.apk`: `10,189,093` bytes on 2026-03-12

Largest uncompressed entries in the current release artifact baseline:

- `classes.dex`: `13,963,960` bytes (`4,800,740` compressed)
- `classes2.dex`: `10,023,716` bytes (`3,494,842` compressed)
- `classes3.dex`: `2,570,396` bytes (`966,887` compressed)
- `resources.arsc`: `474,928` bytes
- `okhttp3/internal/publicsuffix/publicsuffixes.gz`: `41,394` bytes

That future shrinking pass should still record:

- the release APK size before the change
- the release APK size after the change
- any keep rules added to preserve behavior
