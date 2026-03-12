# RELEASE CHECKLIST

> Use this checklist for the current AndroidClaw v0 RC pass. Baseline Profiles are explicitly deferred for this pass and must be recorded as absent in release evidence.

## Scope freeze

- no new feature categories
- only bug fixes, validation fixes, performance fixes, and documentation fixes

## Required automated validation

- `./gradlew :app:assembleDebug`
- `./gradlew :app:testDebugUnitTest`
- `./gradlew :app:lintDebug`
- `./gradlew :app:assembleRelease`
- `ANDROIDCLAW_JAVA_HOME=/path/to/jdk17 ./scripts/run_windows_android_test.sh --avd AndroidClawApi34 --test-class ai.androidclaw.app.MainActivitySmokeTest --no-window`
- `ANDROIDCLAW_JAVA_HOME=/path/to/jdk17 ./scripts/run_windows_android_test.sh --avd AndroidClawApi34 --test-class ai.androidclaw.runtime.scheduler.TaskExecutionWorkerSmokeTest --no-window`
- `ANDROIDCLAW_JAVA_HOME=/path/to/jdk17 ./scripts/run_exact_alarm_regression.sh --api34-avd AndroidClawApi34 --api31-avd AndroidClawApi31 --no-window`

Recommended supporting validation:

- `ANDROIDCLAW_JAVA_HOME=/path/to/jdk17 ./scripts/run_windows_android_test.sh --avd Medium_Phone_API_36.1 --test-class ai.androidclaw.data.db.AndroidClawDatabaseMigrationTest --no-window`
- `ANDROIDCLAW_JAVA_HOME=/path/to/jdk17 ./scripts/run_windows_android_test.sh --avd Medium_Phone_API_36.1 --test-class ai.androidclaw.app.StartupMaintenanceIntegrationTest --no-window`

## Required manual QA

- launch app fresh
- send one `FakeProvider` message
- create, switch, rename, and archive a session
- create `once`, `interval`, and `cron` tasks
- use `Run now`
- inspect task run history
- import a local skill zip
- enable and disable a skill
- slash-invoke an eligible skill
- verify health/task diagnostics
- verify exact-alarm degrade messaging
- verify notification warning behavior when notifications are denied

## External/manual blockers to record honestly

- real-provider send requires a valid API key and endpoint
- Baseline Profiles are not present in this RC pass
- release shrinking remains disabled in this RC pass

## Evidence docs

- `docs/qa/windows-emulator-validation.md`
- `docs/qa/exact-alarm-regression.md`
- `docs/qa/persistence-validation.md`
- `docs/qa/release-build-validation.md`
- `docs/qa/rc-validation.md`
- `docs/KNOWN_LIMITATIONS.md`
