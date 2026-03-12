# AndroidClaw

AndroidClaw is an Android-native local AI assistant host inspired by NanoClaw and OpenClaw.

It is built as a single APK and focuses on four core contracts:

- chat sessions with durable history
- typed tools with clear capability reporting
- `SKILL.md`-based skills with import, enable/disable, precedence, and slash invocation
- scheduled automations with `once`, `interval`, and `cron`, including `MAIN_SESSION` and `ISOLATED_SESSION` execution modes

This repository is a Kotlin-first Android app built with:

- Jetpack Compose
- Room
- WorkManager
- kotlinx serialization
- OkHttp

## Current status

AndroidClaw already includes:

- local chat with a deterministic `FakeProvider`
- an OpenAI-compatible provider path
- a tool-call runtime loop
- bundled, local, and workspace skill loading
- task scheduling, run history, exact-alarm degradation, and scheduler diagnostics
- Windows-emulator and Android instrumentation validation paths

## Key docs

- [Execution plan](PLANv4.md)
- [Architecture](docs/ARCHITECTURE.md)
- [Scheduler](docs/SCHEDULER.md)
- [Skills compatibility](docs/SKILLS_COMPAT.md)
- [Testing](docs/TESTING.md)
- [Release checklist](docs/RELEASE_CHECKLIST.md)
- [Known limitations](docs/KNOWN_LIMITATIONS.md)

## Build

From the repository root:

```bash
./gradlew :app:assembleDebug
./gradlew :app:testDebugUnitTest
./gradlew :app:lintDebug
```

Build a release APK:

```bash
./gradlew :app:assembleRelease
```

## Device validation

Windows AVD from WSL:

```bash
ANDROIDCLAW_JAVA_HOME=/path/to/jdk17 ./scripts/run_windows_android_test.sh --avd AndroidClawApi34 --test-class ai.androidclaw.app.MainActivitySmokeTest
ANDROIDCLAW_JAVA_HOME=/path/to/jdk17 ./scripts/run_exact_alarm_regression.sh --api34-avd AndroidClawApi34 --api31-avd AndroidClawApi31
```

## Product boundaries

AndroidClaw v0 does not include:

- browser automation
- external chat-channel integrations
- remote bridge mode as a required baseline
- shell execution
- cloud sync

## Why the repo may not show up in Google immediately

Google indexing for a public GitHub repository is not immediate. The best repo-side signals are now:

- a root `README.md`
- a clear repository description
- GitHub topics
- stable public links to the repo

Those changes help discovery, but Google re-crawl timing is still external.
