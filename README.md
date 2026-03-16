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

## What it does today

AndroidClaw already supports:

- durable chat sessions with a deterministic `FakeProvider`
- an OpenAI-compatible real-provider path
- visible streamed assistant output on the chat screen
- cancel and retry for interactive provider turns
- typed tools and a persisted tool-call runtime loop
- bundled, local, and workspace `SKILL.md` skills
- skill import, enable/disable, precedence, and slash invocation
- scheduled automations with `once`, `interval`, and `cron`
- task run history, exact-alarm degradation, and scheduler diagnostics

Recent work added:

- additive streaming provider/runtime contracts
- OpenAI-compatible SSE streaming with safe batch fallback
- chat turn UX for live progress, cancel, retry, and clearer failure state
- budgeted context assembly via `ContextWindowManager` instead of a fixed recent-message slice

## Current status

AndroidClaw already includes:

- local chat with a deterministic `FakeProvider`
- an OpenAI-compatible provider path with SSE streaming support
- a tool-call runtime loop
- live chat streaming, cancel, and retry handling
- budgeted prompt/context selection for longer sessions
- bundled, local, and workspace skill loading
- task scheduling, run history, exact-alarm degradation, and scheduler diagnostics
- an installable `qa` APK lane plus release AAB packaging
- Windows-emulator and Android instrumentation validation paths

Still intentionally pending:

- native Anthropic provider support
- session-summary generation beyond the current summary insertion seam
- chat export/share and search
- Room `kapt -> ksp` migration
- Baseline Profiles

## Public links

- [Project page](https://yjli-new.github.io/AndroidClaw/)
- [GitHub Actions](https://github.com/YJLi-new/AndroidClaw/actions)

## Build

From the repository root:

```bash
export JAVA_HOME=/path/to/jdk17
./gradlew :app:assembleDebug
./gradlew :app:testDebugUnitTest
./gradlew :app:lintDebug
```

Build release-like artifacts:

```bash
./gradlew :app:assembleRelease
./gradlew :app:bundleRelease
```

## Device validation

Windows AVD from WSL:

```bash
ANDROIDCLAW_JAVA_HOME=/path/to/jdk17 ./scripts/run_windows_android_test.sh --avd AndroidClawApi34 --test-class ai.androidclaw.app.MainActivitySmokeTest
ANDROIDCLAW_JAVA_HOME=/path/to/jdk17 ./scripts/run_windows_android_test.sh --variant qa --launch-smoke --avd AndroidClawApi34 --launch-component ai.androidclaw.app/.MainActivity
ANDROIDCLAW_JAVA_HOME=/path/to/jdk17 ./scripts/run_exact_alarm_regression.sh --api34-avd AndroidClawApi34 --api31-avd AndroidClawApi31
```

Fast release-like packaging lane:

```bash
./gradlew :app:assembleQa :app:assembleRelease :app:bundleRelease
```

## CI

GitHub Actions now separates:

- `fast`: debug assemble, unit tests, and lint
- `packaging`: `assembleDebugAndroidTest`, `assembleQa`, `assembleRelease`, and `bundleRelease`

The workflow uploads fast-loop reports plus packaging outputs so the `qa` APK and release bundle can be downloaded from the Actions UI.

## Runtime notes

- Streaming is additive. Providers that do not support streaming still work through the non-streaming path.
- Partial streamed assistant text stays ephemeral until the final assistant message is persisted.
- Context selection is budgeted rather than fixed-count only, which keeps the latest important turns and tool-call closure under a bounded prompt budget.
- Scheduled turns still use the durable non-streaming execution path.

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
