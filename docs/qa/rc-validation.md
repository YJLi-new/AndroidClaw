# RC Validation

Date: 2026-03-12

## Scope state

- Scope is frozen for the current RC pass.
- Baseline Profiles are explicitly deferred for this pass.
- Release shrinking remains disabled for this pass.

## Automated validation matrix

### Local fast matrix

Commands run:

```bash
ANDROIDCLAW_JAVA_HOME=/tmp/androidclaw-jdk17-extract/jdk-17.0.18+8 \
./gradlew --no-daemon \
  -Dkotlin.compiler.execution.strategy=in-process \
  -Dkotlin.incremental=false \
  :app:assembleDebug \
  :app:testDebugUnitTest \
  :app:lintDebug \
  :app:assembleRelease \
  --console=plain \
  --no-configuration-cache \
  --no-build-cache
```

Result:

- `:app:assembleDebug` passed
- `:app:testDebugUnitTest` passed
- `:app:lintDebug` passed
- `:app:assembleRelease` passed

Reference:

- `docs/qa/release-build-validation.md`

### Required device matrix

Commands run:

```bash
ANDROIDCLAW_JAVA_HOME=/tmp/androidclaw-jdk17-extract/jdk-17.0.18+8 \
./scripts/run_windows_android_test.sh --avd AndroidClawApi34 --test-class ai.androidclaw.app.MainActivitySmokeTest --no-window

ANDROIDCLAW_JAVA_HOME=/tmp/androidclaw-jdk17-extract/jdk-17.0.18+8 \
./scripts/run_windows_android_test.sh --avd AndroidClawApi34 --test-class ai.androidclaw.runtime.scheduler.TaskExecutionWorkerSmokeTest --no-window

ANDROIDCLAW_JAVA_HOME=/tmp/androidclaw-jdk17-extract/jdk-17.0.18+8 \
./scripts/run_exact_alarm_regression.sh --api34-avd AndroidClawApi34 --api31-avd AndroidClawApi31 --no-window
```

Result:

- API 34 `MainActivitySmokeTest` passed
- API 34 `TaskExecutionWorkerSmokeTest` passed
- exact-alarm regression passed for:
  - API 34 fresh-install degrade
  - API 31 deny
  - API 31 allow
  - API 31 deny-after-allow

References:

- `docs/qa/windows-emulator-validation.md`
- `docs/qa/exact-alarm-regression.md`

### Supporting persistence evidence

Already recorded for the current repo state:

- migration instrumentation passed
- startup-maintenance instrumentation passed

Reference:

- `docs/qa/persistence-validation.md`

## Release artifact

- APK path:
  - `app/build/outputs/apk/release/app-release-unsigned.apk`
- APK size:
  - `10,189,093` bytes
- Baseline Profiles present:
  - no
- R8/resource shrinking enabled:
  - no

## Remaining manual / external items

These are not hidden failures. They remain manual or external prerequisites for final tester handoff:

- real-provider send with a valid API key and compatible endpoint
- human walkthrough of session rename/archive, task creation UI, skill import/toggle/slash invoke, and notification-denied warning behavior
- any release-signing step with a human-held key

## Known limitations

- `docs/KNOWN_LIMITATIONS.md`

## RC status

- The automated RC matrix is green.
- Remaining gaps are manual/external and are explicitly documented above rather than hidden.
