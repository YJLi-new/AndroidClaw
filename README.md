<div align="center">

# 🤖 AndroidClaw

**Android-native local AI assistant host — your pocket-sized AI command center.**

Inspired by NanoClaw and OpenClaw, built from the ground up for Android.

[![Kotlin](https://img.shields.io/badge/Kotlin-First-7F52FF?logo=kotlin&logoColor=white)](https://kotlinlang.org/)
[![Jetpack Compose](https://img.shields.io/badge/Jetpack%20Compose-UI-4285F4?logo=jetpackcompose&logoColor=white)](https://developer.android.com/jetpack/compose)
[![License](https://img.shields.io/badge/License-Apache%202.0-blue.svg)](LICENSE)

[📖 Project Page](https://yjli-new.github.io/AndroidClaw/) · [⬇️ Latest Release](https://github.com/YJLi-new/AndroidClaw/releases/latest) · [⚙️ CI / Actions](https://github.com/YJLi-new/AndroidClaw/actions)

</div>

---

## 💡 What is AndroidClaw?

AndroidClaw is a **single-APK** AI assistant host that runs entirely on your Android device. It turns your phone into a capable AI workstation with durable chat history, extensible tool use, skill-based customization, and scheduled automations — no cloud dependency required.

### 🎯 Four Core Contracts

| Contract | Description |
|:---------|:------------|
| 💬 **Chat Sessions** | Durable history, streaming output, cancel & retry |
| 🔧 **Typed Tools** | Clear capability reporting, persisted tool-call runtime loop |
| 📜 **SKILL.md Skills** | Import, enable/disable, precedence, and `/slash` invocation |
| ⏰ **Scheduled Automations** | `once` · `interval` · `cron`, with `MAIN_SESSION` and `ISOLATED_SESSION` modes |

## 📌 Current Status

AndroidClaw already ships a usable local-first runtime:

- durable chat sessions and persisted message history
- streaming assistant output with cancel / retry
- typed tool calling with persisted tool-call and tool-result turns
- bundled, local, and workspace skills with precedence and enable / disable
- scheduled automations with `once`, `interval`, `cron`, exact-alarm degradation, and task-run history
- a public GitHub Pages project site and installable release artifacts

## ✨ Features

**Chat & Provider**
- Durable chat sessions with a deterministic `FakeProvider` for local testing
- Real-provider support for:
  - `OpenAI-compatible`
  - `MiniMax`
  - `GLM`
  - `Kimi`
  - `Claude` via a native Anthropic Messages provider
  - `Gemini` via its OpenAI-compatible endpoint
- SSE streaming with safe batch fallback on compatible providers
- Visible streamed assistant output, cancel, retry, and clearer failure states
- Budgeted context assembly via `ContextWindowManager` — no more naive fixed-count slicing
- Per-provider saved base URL, model ID, timeout, and encrypted API key storage in Settings

**Skills & Tools**
- Bundled, local, and workspace `SKILL.md` skill loading
- Skill import, enable/disable, precedence ordering, and slash invocation
- Typed tools with a persisted tool-call runtime loop

**Automation & Scheduling**
- `once` / `interval` / `cron` task scheduling
- Task run history, exact-alarm degradation, and scheduler diagnostics
- Scheduled turns use a durable non-streaming execution path

**Build & CI**
- Installable `qa` APK lane + release AAB packaging
- GitHub Actions CI: `fast` (assemble + test + lint) and `packaging` (APK + AAB)
- Windows-emulator and Android instrumentation validation paths

## 🏗️ Tech Stack

- **Language:** Kotlin
- **UI:** Jetpack Compose
- **Persistence:** Room
- **Background:** WorkManager
- **Serialization:** kotlinx-serialization
- **Networking:** OkHttp

## 🚀 Getting Started

### Prerequisites

- JDK 17+
- Android SDK (API 31+)

### Build

```bash
export JAVA_HOME=/path/to/jdk17

# Debug build
./gradlew :app:assembleDebug

# Unit tests & lint
./gradlew :app:testDebugUnitTest
./gradlew :app:lintDebug
```

### Release Artifacts

```bash
./gradlew :app:assembleRelease
./gradlew :app:bundleRelease

# Or all packaging lanes at once
./gradlew :app:assembleQa :app:assembleRelease :app:bundleRelease
```

### 📱 Device Validation (Windows AVD from WSL)

```bash
ANDROIDCLAW_JAVA_HOME=/path/to/jdk17 \
  ./scripts/run_windows_android_test.sh \
  --avd AndroidClawApi34 \
  --test-class ai.androidclaw.app.MainActivitySmokeTest

ANDROIDCLAW_JAVA_HOME=/path/to/jdk17 \
  ./scripts/run_exact_alarm_regression.sh \
  --api34-avd AndroidClawApi34 \
  --api31-avd AndroidClawApi31
```

## 📐 Architecture Notes

- **Streaming is additive.** Providers that don't support streaming still work through the non-streaming path — no breaking changes.
- **Ephemeral partial text.** Streamed assistant tokens stay ephemeral until the final message is committed to Room.
- **Budgeted context selection.** The latest important turns and tool-call closures stay within a bounded prompt budget, replacing naive recent-message slicing.
- **Hybrid provider strategy.** Claude uses a native Anthropic transport; MiniMax / GLM / Kimi / Gemini reuse the OpenAI-compatible runtime path to keep the app small.

## 🗺️ Roadmap

| Status | Item |
|:------:|:-----|
| ✅ | Native Anthropic provider support |
| ✅ | Named provider presets for MiniMax / GLM / Kimi / Gemini |
| 🔲 | Session-summary generation (seam already in place) |
| 🔲 | Chat export / share / search |
| 🔲 | Room `kapt → ksp` migration |
| 🔲 | Baseline Profiles for startup optimization |

## 🚧 Non-Goals (v0)

AndroidClaw v0 intentionally does **not** include: browser automation, external chat-channel integrations, remote bridge mode, shell execution, or cloud sync.

## 🤝 Contributing

Contributions, issues, and feature requests are welcome! Feel free to open an issue or submit a pull request.

## 📄 License

Apache-2.0. See [LICENSE](LICENSE).

---

<div align="center">

Made with ❤️ for the Android AI community

</div>
