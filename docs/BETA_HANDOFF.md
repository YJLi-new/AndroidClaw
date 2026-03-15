# BETA HANDOFF

Date: 2026-03-15

## Scope

AndroidClaw is currently packaged as a beta-ready local host, not a production-signed release.

This packet is intentionally scoped to:

- installable local testing through the shrunk `qa` APK
- release packaging truth through the release AAB
- repeatable repo-owned validation lanes
- explicit limitations instead of hidden assumptions

## Artifacts

Current artifact set:

- installable beta APK:
  - `app/build/outputs/apk/qa/app-qa.apk`
  - current size: `2,109,200` bytes
- release packaging APK:
  - `app/build/outputs/apk/release/app-release-unsigned.apk`
  - current size: `2,096,912` bytes
- release bundle:
  - `app/build/outputs/bundle/release/app-release.aab`
  - current size: `4,657,005` bytes

Artifact truth:

- `qa` is the installable beta lane
- `release` is the unsigned production-shape packaging lane
- Baseline Profiles are explicitly deferred for this beta packet

## Automated validation

Run or verify these lanes before handing the build to another tester:

### Fast loop

```bash
./gradlew :app:assembleDebug
./gradlew :app:testDebugUnitTest
./gradlew :app:lintDebug
```

### Packaging

```bash
./gradlew :app:assembleQa
./gradlew :app:assembleRelease
./gradlew :app:bundleRelease
```

### Windows AVD smoke

```bash
ANDROIDCLAW_JAVA_HOME=/path/to/jdk17 ./scripts/run_windows_android_test.sh --variant qa --launch-smoke --avd AndroidClawApi34 --launch-component ai.androidclaw.app/.MainActivity --no-window
ANDROIDCLAW_JAVA_HOME=/path/to/jdk17 ./scripts/run_windows_android_test.sh --avd AndroidClawApi34 --test-class ai.androidclaw.app.MainActivitySmokeTest --no-window
ANDROIDCLAW_JAVA_HOME=/path/to/jdk17 ./scripts/run_windows_android_test.sh --avd AndroidClawApi34 --test-class ai.androidclaw.runtime.scheduler.TaskExecutionWorkerSmokeTest --no-window
ANDROIDCLAW_JAVA_HOME=/path/to/jdk17 ./scripts/run_exact_alarm_regression.sh --api34-avd AndroidClawApi34 --api31-avd AndroidClawApi31 --no-window
```

### Evidence references

- `docs/qa/beta-validation.md`
- `docs/qa/release-build-validation.md`
- `docs/qa/release-size-validation.md`
- `docs/qa/windows-emulator-validation.md`
- `docs/qa/exact-alarm-regression.md`
- `docs/qa/persistence-validation.md`

## Install guidance

### Local install

Preferred beta install artifact:

- `app/build/outputs/apk/qa/app-qa.apk`

If `adb` is available:

```bash
adb install -r app/build/outputs/apk/qa/app-qa.apk
adb shell am start -n ai.androidclaw.app/.MainActivity
```

If you are using the repo's Windows-emulator wrapper from WSL, the launch-smoke command above is the supported proof lane.

### Bundle distribution

The release AAB is included in the beta packet for packaging/distribution workflows:

- `app/build/outputs/bundle/release/app-release.aab`

Use it only in one of these cases:

- you have Play Console access and want:
  - Internal App Sharing
  - Internal testing track
- you have a signed bundle or signing flags for local `bundletool` use

If neither is true, keep the beta install path on the `qa` APK instead of pretending the AAB is directly installable.

## Manual beta checklist

- launch the app fresh
- send one `FakeProvider` message
- create, switch, rename, and archive a session
- create `once`, `interval`, and `cron` tasks in the GUI
- create at least one task through the agent/tool path
- use `Run now`
- inspect task run history
- import a local skill zip
- enable and disable a skill
- configure a skill secret/config field
- slash-invoke an eligible skill
- verify health/task diagnostics
- verify exact-alarm degrade messaging
- verify notification warning behavior when notifications are denied
- if testing a real provider, record the endpoint/model used and whether an API key was configured

## What testers should not expect

- exact alarms are not universally available; Android 14+ may deny them by default
- notification visibility depends on Android 13+ notification permission state
- Baseline Profiles are absent from this beta packet
- the app is not yet a production-signed release
- browser automation, shell execution, remote bridge mode, cloud sync, and external chat-channel integrations are out of scope for this beta

## Reporting guidance

When filing a bug, include:

- device or AVD name and Android version
- whether the build was the `qa` APK or a Play/internal bundle install
- provider mode:
  - `FakeProvider`
  - OpenAI-compatible endpoint
- whether notifications were granted
- whether exact alarms were granted or degraded
- the session/task/skill flow that reproduced the issue
