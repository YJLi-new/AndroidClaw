# Beta Validation

Date: 2026-03-15

## Scope state

- Scope is frozen for the current beta packet.
- Baseline Profiles are explicitly deferred for this packet.
- `qa` is the installable beta lane.
- `release` remains the unsigned packaging lane.

## Automated validation summary

### Fast + packaging

Current passing commands:

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

Result:

- `:app:assembleDebug` passed
- `:app:testDebugUnitTest` passed
- `:app:lintDebug` passed
- `:app:assembleQa` passed
- `:app:assembleRelease` passed
- `:app:bundleRelease` passed

Reference:

- `docs/qa/release-build-validation.md`

### Installable beta lane

Current release-like proof lane:

```bash
ANDROIDCLAW_JAVA_HOME=/tmp/androidclaw-jdk17-extract/jdk-17.0.18+8 \
./scripts/run_windows_android_test.sh --variant qa --launch-smoke --avd AndroidClawApi34 --launch-component ai.androidclaw.app/.MainActivity --no-window
```

Reference:

- `docs/qa/release-size-validation.md`

### Device smoke and scheduler

Current passing commands:

```bash
ANDROIDCLAW_JAVA_HOME=/tmp/androidclaw-jdk17-extract/jdk-17.0.18+8 \
./scripts/run_windows_android_test.sh --avd AndroidClawApi34 --test-class ai.androidclaw.app.MainActivitySmokeTest --no-window

ANDROIDCLAW_JAVA_HOME=/tmp/androidclaw-jdk17-extract/jdk-17.0.18+8 \
./scripts/run_windows_android_test.sh --avd AndroidClawApi34 --test-class ai.androidclaw.runtime.scheduler.TaskExecutionWorkerSmokeTest --no-window
```

Reference:

- `docs/qa/windows-emulator-validation.md`

### Exact-alarm regression

Current passing command:

```bash
ANDROIDCLAW_JAVA_HOME=/tmp/androidclaw-jdk17-extract/jdk-17.0.18+8 \
./scripts/run_exact_alarm_regression.sh --api34-avd AndroidClawApi34 --api31-avd AndroidClawApi31 --no-window
```

Reference:

- `docs/qa/exact-alarm-regression.md`

### Persistence and upgrade

Reference:

- `docs/qa/persistence-validation.md`

## Artifact summary

- `qa` APK:
  - `app/build/outputs/apk/qa/app-qa.apk`
  - `2,109,200` bytes
- release APK:
  - `app/build/outputs/apk/release/app-release-unsigned.apk`
  - `2,096,912` bytes
- release AAB:
  - `app/build/outputs/bundle/release/app-release.aab`
  - `4,657,005` bytes

## Remaining manual / external items

- real-provider send still requires a valid API key and compatible endpoint
- manual walkthrough remains required for:
  - task creation from the agent/tool path
  - skill secret/config editing
  - full notification-denied UX review
  - human verification on a real physical device if desired

## Active references

- `docs/BETA_HANDOFF.md`
- `docs/RELEASE_CHECKLIST.md`
- `docs/KNOWN_LIMITATIONS.md`
