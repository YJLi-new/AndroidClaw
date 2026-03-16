<div align="center">

# 🤖 AndroidClaw

**Android-native local AI assistant host — your pocket-sized AI command center.**

Inspired by NanoClaw and OpenClaw, built from the ground up for Android.

[![Kotlin](https://img.shields.io/badge/Kotlin-First-7F52FF?logo=kotlin&logoColor=white)](https://kotlinlang.org/)
[![Jetpack Compose](https://img.shields.io/badge/Jetpack%20Compose-UI-4285F4?logo=jetpackcompose&logoColor=white)](https://developer.android.com/jetpack/compose)
[![License](https://img.shields.io/badge/License-TBD-lightgrey)](#license)

[📖 Project Page](https://yjli-new.github.io/AndroidClaw/) · [⚙️ CI / Actions](https://github.com/YJLi-new/AndroidClaw/actions)

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

## ✨ Features

**Chat & Provider**
- Durable chat sessions with a deterministic `FakeProvider` for local testing
- OpenAI-compatible real-provider path with SSE streaming (safe batch fallback)
- Visible streamed assistant output, cancel, retry, and clearer failure states
- Budgeted context assembly via `ContextWindowManager` — no more naive fixed-count slicing

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

## 🗺️ Roadmap

| Status | Item |
|:------:|:-----|
| 🔲 | Native Anthropic provider support |
| 🔲 | Session-summary generation (seam already in place) |
| 🔲 | Chat export / share / search |
| 🔲 | Room `kapt → ksp` migration |
| 🔲 | Baseline Profiles for startup optimization |

## 🚧 Non-Goals (v0)

AndroidClaw v0 intentionally does **not** include: browser automation, external chat-channel integrations, remote bridge mode, shell execution, or cloud sync.

## 🤝 Contributing

Contributions, issues, and feature requests are welcome! Feel free to open an issue or submit a pull request.

## 📄 License

TBD — License information will be added soon.

---

<div align="center">

Made with ❤️ for the Android AI community

</div>
