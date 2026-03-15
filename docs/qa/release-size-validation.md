# Release Size Validation

Date: 2026-03-15

## Baseline before shrinking

- `app/build/outputs/apk/qa/app-qa.apk`: `10,234,149` bytes
- `app/build/outputs/apk/release/app-release-unsigned.apk`: `10,189,093` bytes

## Current repo state

- `qa` is the active shrinking lane
  - `isMinifyEnabled = true`
  - `isShrinkResources = true`
- `release` remains unshrunk for now
  - `isMinifyEnabled = false`
  - `isShrinkResources = false`
- narrow keep-rule change currently needed:
  - `-dontwarn java.beans.*` for SnakeYAML's unused desktop bean-introspection path on Android

## Current measured artifact

- `app/build/outputs/apk/qa/app-qa.apk`: `2,500,568` bytes

That is a reduction of `7,733,581` bytes from the earlier `qa` baseline.

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
Status: ok
LaunchState: COLD
Activity: ai.androidclaw.app/.MainActivity
Complete
```

## Remaining blocker to close ws4 fully

The current shell session later lost local-socket access needed by Gradle's file-lock listener and Linux-side `adb`, so the repo could not finish the final local rerun of:

- `:app:assembleRelease`
- `:app:bundleRelease`
- post-change device proof from the shell-side wrappers

Because of that, `release` shrinking remains intentionally disabled until the next clean rerun on either:

- the Windows host path, or
- CI after the packaging workflow update lands
