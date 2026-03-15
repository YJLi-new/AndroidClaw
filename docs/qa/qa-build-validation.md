# QA Build Validation

Date: 2026-03-15

## Commands

```bash
export JAVA_HOME=/tmp/androidclaw-jdk17-extract/jdk-17.0.18+8
export PATH="$JAVA_HOME/bin:$PATH"

./gradlew :app:assembleQa :app:assembleAndroidTest
ANDROIDCLAW_JAVA_HOME=/tmp/androidclaw-jdk17-extract/jdk-17.0.18+8 \
  ./scripts/run_windows_android_test.sh \
  --variant qa \
  --avd AndroidClawApi34 \
  --test-class ai.androidclaw.app.MainActivitySmokeTest \
  --no-window
```

## Result

- `:app:assembleQa` passed
- `:app:assembleAndroidTest` passed
- Windows AVD smoke install/launch for the `qa` APK passed

Smoke output:

```text
ai.androidclaw.app.MainActivitySmokeTest:.

Time: 13.285

OK (1 test)
```

## Artifacts

- qa APK path:
  - `app/build/outputs/apk/qa/app-qa.apk`
- qa APK size:
  - `10,234,149` bytes
- shared androidTest APK path:
  - `app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk`

## Notes

- `qa` is the installable release-like lane and is signed with the local debug key.
- This repo/AGP setup exposes a shared `assembleAndroidTest` artifact rather than a separate `assembleQaAndroidTest` task.
- The `qa` lane intentionally keeps the base application id so device smoke stays close to the production packaging shape.
