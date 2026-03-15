# Release Build Validation

Date: 2026-03-15

## Command

```bash
ANDROIDCLAW_JAVA_HOME=/tmp/androidclaw-jdk17-extract/jdk-17.0.18+8 \
./gradlew --no-daemon \
  -Dkotlin.compiler.execution.strategy=in-process \
  -Dkotlin.incremental=false \
  :app:assembleDebug \
  :app:testDebugUnitTest \
  :app:lintDebug \
  :app:assembleQa \
  :app:assembleRelease \
  :app:bundleRelease \
  --console=plain \
  --no-configuration-cache \
  --no-build-cache
```

## Result

- `:app:assembleDebug` passed
- `:app:testDebugUnitTest` passed
- `:app:lintDebug` passed
- `:app:assembleQa` passed
- `:app:assembleRelease` passed
- `:app:bundleRelease` passed

Build result:

```text
BUILD SUCCESSFUL in 8m 7s
110 actionable tasks: 68 executed, 42 up-to-date
```

## Artifacts

- installable qa APK path:
  - `app/build/outputs/apk/qa/app-qa.apk`
- installable qa APK size:
  - `2,109,200` bytes
- release APK path:
  - `app/build/outputs/apk/release/app-release-unsigned.apk`
- release APK size:
  - `2,096,912` bytes
- release AAB path:
  - `app/build/outputs/bundle/release/app-release.aab`
- release AAB size:
  - `4,657,005` bytes

Largest uncompressed entries in the optimized release APK:

- `classes.dex`: `3,231,588` bytes (`1,566,526` compressed)
- `resources.arsc`: `100,268` bytes
- `lib/arm64-v8a/libdatastore_shared_counter.so`: `54,304` bytes
- `lib/x86_64/libdatastore_shared_counter.so`: `53,840` bytes
- `okhttp3/internal/publicsuffix/publicsuffixes.gz`: `41,394` bytes

## Notes

- `qa` and `release` now both ship with code shrinking and resource shrinking enabled.
- Release-like launch proof for the shrunk `qa` APK uses the direct install/launch smoke path:
  - `./scripts/run_windows_android_test.sh --variant qa --launch-smoke --avd AndroidClawApi34 --launch-component ai.androidclaw.app/.MainActivity --no-window`
- The shared debug `androidTest` APK remains the instrumentation lane for debug and exact-alarm regression, but it is not used as the proof lane for minified `qa`.
- The only keep-rule expansion needed for shrinking was a narrow `-dontwarn java.beans.*` rule set for SnakeYAML's unused desktop bean-introspection path on Android.
- `lintDebug` is currently scoped to production sources. Test-source lint is intentionally ignored because AGP 8.13 + Kotlin FIR crashes while analyzing `debugUnitTest` and `debugAndroidTest` sources on this workstation.
- Network-backed lint version-check detectors are disabled so the repo fast loop remains deterministic and local.
