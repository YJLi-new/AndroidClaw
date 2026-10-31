<div align="center">

<img src="android_arch_logo_minimal.svg" alt="AndroidClaw logo" width="148" height="148" />

# AndroidClaw

**A small Android-native AI assistant host. One APK, your phone is the host.**

Inspired by NanoClaw and OpenClaw: sessions, typed tools, `SKILL.md` skills and scheduled automations, rebuilt in Kotlin for Android.

[![Kotlin](https://img.shields.io/badge/Kotlin-First-7F52FF?logo=kotlin&logoColor=white)](https://kotlinlang.org/)
[![Jetpack Compose](https://img.shields.io/badge/Jetpack%20Compose-UI-4285F4?logo=jetpackcompose&logoColor=white)](https://developer.android.com/jetpack/compose)
[![License](https://img.shields.io/badge/License-Apache%202.0-blue.svg)](LICENSE)

[Project page](https://androidclaw.top/) · [Latest release](https://github.com/YJLi-new/AndroidClaw/releases/latest) · [CI](https://github.com/YJLi-new/AndroidClaw/actions) · [Changelog](CHANGELOG.md) · [中文说明](#中文说明)

</div>

---

Last verified against repo: 2026-10-31 (v0.2.0 rework)

## What it is

AndroidClaw is a single-APK assistant that runs its whole runtime on the
phone: chat sessions stored in Room, a native tool-calling loop, skills loaded
from `SKILL.md` files, and tasks scheduled with WorkManager and exact alarms.
It talks to a model provider of your choice over HTTPS, or to a built-in
offline demo provider that needs no key and no network.

It does not embed Node.js, Docker, a browser engine or a shell, and it is not a
remote companion for a desktop gateway.

## Screenshots

| Onboarding | Sessions drawer | Tasks | Task editor | Skills | Settings |
|:-:|:-:|:-:|:-:|:-:|:-:|
| <img src="site/assets/screens/en/onboarding.png" width="150" alt="First-run onboarding over the chat screen" /> | <img src="site/assets/screens/en/drawer.png" width="150" alt="Session drawer with main session and archive" /> | <img src="site/assets/screens/en/tasks.png" width="150" alt="Task list with run-now and next run" /> | <img src="site/assets/screens/en/task_editor.png" width="150" alt="Task editor with interval presets" /> | <img src="site/assets/screens/en/skills.png" width="150" alt="Skills list with toggles" /> | <img src="site/assets/screens/en/settings.png" width="150" alt="Settings with provider, memory, theme and language" /> |

## Features

### Chat
- Transcript-first chat screen: top bar with the session title, a modal
  drawer for sessions (new, switch, rename, archive, restore, delete).
- Markdown rendering with code blocks and copy buttons; streaming replies
  with a Stop button.
- Tool calls appear as collapsible cards in the transcript (summary line
  collapsed, full JSON result expanded).
- Slash-command suggestions while typing `/`; overflow menu for search,
  share/export, token usage and `/compact` history compaction.
- The main session always exists; scheduled tasks can post into it.

### Model-facing tools (34)
Native tool calling only: tool schemas go in the provider's `tools` field and
are not repeated in the system prompt (about 12.9 KB of tool JSON per turn,
unit-tested to stay under 24 KB).

| Group | Tools |
|---|---|
| Sessions | `sessions_list`, `sessions_history`, `sessions_create`, `sessions_rename`, `sessions_send` |
| Memory | `memory_search`, `memory_get`, `memory_remember`, `memory_forget` |
| Tasks | `tasks_list`, `tasks_create`, `tasks_update`, `tasks_delete`, `tasks_run_now`, `tasks_runs` |
| Skills | `skills_list`, `skills_get`, `skills_set_enabled` |
| Workspace files | `workspace_list`, `workspace_read`, `workspace_write`, `workspace_edit` |
| Web | `web_fetch` (public http/https pages as text; private networks blocked) |
| Device and intents | `device_status`, `open_url`, `open_app`, `open_settings`, `set_alarm`, `set_timer`, `calendar_add_event`, `clipboard_get`, `clipboard_set` |
| Notifications and health | `notify`, `health_status` |

- Two more tools are slash-only and never sent to the model:
  `sessions_compact` (`/compact`) and `memory_command` (`/memory`).
- Intent tools (`open_*`, `set_alarm`, `set_timer`, `calendar_add_event`,
  `clipboard_*`) need the app in the foreground. `calendar_add_event` opens a
  prefilled calendar screen and the user saves it.
- Scheduled runs get 18 read-only or opt-in tools (web, device status,
  notify, workspace files, memory search/get/remember, session list/history/
  send, task list/runs, skill list/get, health). Foreground-only tools
  return `FOREGROUND_REQUIRED` there.

### Workspace persona files
OpenClaw-style `AGENTS.md`, `SOUL.md`, `USER.md` and `MEMORY.md` live in
app-private storage, are created from templates on first use, and are
injected into the system prompt within an 8 KB budget (truncation is marked).
The assistant can read and edit them with the `workspace_*` tools.

### Skills
- Bundled, local and workspace `SKILL.md` skills with frontmatter parsing,
  precedence, enable/disable (with a confirmation for risky skills), a detail
  screen, and import from a file.
- Bundled skills: `/compact`, `/list_tasks`, `/memory`, `/summarize_session`,
  `/task_helper`. Skills with `command-dispatch: tool` run a tool directly,
  without a model round trip.

### Scheduled tasks
- Once, interval (presets from 15 minutes) and five-field cron schedules,
  edited with local date and time pickers and a next-run preview for cron.
- Post into a chat session or run in a fresh isolated session.
- Edit existing tasks, enable/disable, run now, run history, exact-alarm
  hints when Android denies precise alarms.

### Settings and diagnostics
- Provider setup with a connection test, optional local Memory, theme
  (system/light/dark) and app language (system/English/简体中文).
- Diagnostics screen: provider, network, scheduler, notifications, recent
  automation results, exportable report.
- Full English and Simplified Chinese UI (397 strings and 13 plurals in each
  locale, parity enforced by a unit test). Per-app language on Android 13+,
  in-app fallback on Android 8 to 12.

### Providers
| Provider | Transport |
|---|---|
| Offline demo (FakeProvider) | Local, deterministic, no key |
| OpenAI | Responses API (`/v1/responses`), default model `gpt-6-luna` |
| OpenAI-compatible | chat/completions; a base URL on `api.openai.com` is routed to the Responses API |
| OpenAI Codex | Device-code OAuth sign-in, Responses streaming |
| Claude | Native Anthropic Messages API |
| MiniMax, GLM, Kimi, Gemini, DeepSeek | OpenAI-compatible presets (chat/completions) |

All remote providers stream over SSE and keep a non-streaming path for
scheduled runs. API keys and Codex OAuth credentials are stored encrypted on
the device.

## Build and test

Requirements: JDK 17, Android SDK with platform 36. `minSdk` is 26.

```bash
export JAVA_HOME=/path/to/jdk17

# The first run must be online: it fills the Gradle cache and verifies
# artifacts against gradle/verification-metadata.xml.
./gradlew :app:assembleDebug
./gradlew :app:testDebugUnitTest
./gradlew :app:lintDebug
./scripts/run_ktlint.sh

# Release-like packaging
./gradlew :app:assembleQa :app:assembleRelease :app:bundleRelease
```

Notes:
- Later runs can add `--offline` once the cache holds every artifact. If
  offline resolution fails with a missing plugin marker, run once online.
- If Gradle cannot reach Google Maven but `curl` can, seed the project-local
  fallback repo with `./scripts/seed_android_build_local_maven.sh`.
- In a git worktree, pass globs to ktlint:
  `./scripts/run_ktlint.sh "app/src/**/*.kt" "*.kts" "app/*.kts"`.
- Debug builds start with the offline demo provider. To see a tool card
  without a key, send `What is my battery? [tool:device_status]`.

### Live provider tests (optional)
`OpenAiLiveTest` calls the real OpenAI API and is skipped unless
`ANDROIDCLAW_LIVE_OPENAI_KEY` is set. `ANDROIDCLAW_LIVE_OPENAI_MODEL`
overrides the model (default `gpt-6-luna`). Read the key from a file into the
variable at run time; never commit it.

```bash
ANDROIDCLAW_LIVE_OPENAI_KEY="$(cat /path/to/key.txt)" \
  ./gradlew :app:testDebugUnitTest --tests 'ai.androidclaw.runtime.providers.OpenAiLiveTest'
```

### Device tests (Windows AVD from WSL)

```bash
ANDROIDCLAW_JAVA_HOME=/path/to/jdk17 \
  ./scripts/run_windows_android_test.sh \
  --avd AndroidClawApi34 \
  --test-class ai.androidclaw.app.MainActivitySmokeTest,ai.androidclaw.runtime.scheduler.TaskExecutionWorkerSmokeTest,ai.androidclaw.app.OnboardingProviderSetupSmokeTest,ai.androidclaw.feature.tasks.TasksScreenSmokeTest

ANDROIDCLAW_JAVA_HOME=/path/to/jdk17 \
  ./scripts/run_exact_alarm_regression.sh \
  --api34-avd AndroidClawApi34 \
  --api31-avd AndroidClawApi31
```

## Architecture

One Gradle app module, manual dependency wiring in `AppContainer`, packages:

- `app`: application, activity, container, startup maintenance.
- `ui`: theme, navigation (4 tabs: Chat, Tasks, Skills, Settings), shared
  components, locale handling.
- `feature`: chat, tasks, skills, settings, diagnostics, onboarding.
- `data`: Room (schema v5), DataStore settings, encrypted secrets,
  `WorkspaceStore`.
- `runtime`: agent loop, prompt assembly, providers, tools, skills,
  scheduler, memory.

Stack: Kotlin, Jetpack Compose, Room, WorkManager, coroutines, kotlinx
serialization, OkHttp. No DI framework, no vendor SDKs.

## Non-goals

No browser automation, external chat channels (Telegram, WhatsApp, Slack and
so on), remote bridge mode, shell execution, voice wake word or cloud sync.

## License

Apache-2.0. See [LICENSE](LICENSE).

---

## 中文说明

最后核对仓库日期：2026-10-31（v0.2.0 重做版）

### 这是什么

AndroidClaw 是一个只有一个 APK 的安卓 AI 助手宿主，整个运行时都在手机上：聊天会话存在 Room 数据库里，模型通过原生工具调用来使用工具，技能来自 `SKILL.md` 文件，定时任务由 WorkManager 和精确闹钟调度。它通过 HTTPS 连接你选择的模型服务，也可以使用内置的离线演示服务（不需要密钥，也不需要联网）。

它不内嵌 Node.js、Docker、浏览器内核或 shell，也不是桌面网关的远程伴侣应用。

### 截图

| 设置 | 任务 | 任务编辑 | 技能 |
|:-:|:-:|:-:|:-:|
| <img src="site/assets/screens/zh/settings.png" width="150" alt="设置：模型服务、记忆、主题、语言" /> | <img src="site/assets/screens/zh/tasks.png" width="150" alt="任务列表" /> | <img src="site/assets/screens/zh/task_editor.png" width="150" alt="新建任务：日期和时间选择" /> | <img src="site/assets/screens/zh/skills.png" width="150" alt="技能列表" /> |

### 功能

**聊天**
- 以对话记录为主的聊天界面：顶栏显示会话标题，侧边抽屉管理会话（新建、切换、重命名、归档、恢复、删除）。
- 支持 Markdown 和代码块（可一键复制）；流式输出，可随时停止。
- 工具调用以可折叠卡片显示在对话中：折叠时显示一行摘要，展开后显示完整 JSON 结果。
- 输入 `/` 时提示斜杠命令；右上角菜单提供搜索、分享/导出、用量统计和 `/compact` 压缩历史。
- 主会话始终存在，定时任务可以把结果发到主会话。

**模型可用的工具（34 个）**

只使用原生工具调用：工具定义放在服务商接口的 `tools` 字段里，不在系统提示词中重复列出（每轮约 12.9 KB 工具 JSON，单元测试保证不超过 24 KB）。

| 分组 | 工具 |
|---|---|
| 会话 | `sessions_list`、`sessions_history`、`sessions_create`、`sessions_rename`、`sessions_send` |
| 记忆 | `memory_search`、`memory_get`、`memory_remember`、`memory_forget` |
| 任务 | `tasks_list`、`tasks_create`、`tasks_update`、`tasks_delete`、`tasks_run_now`、`tasks_runs` |
| 技能 | `skills_list`、`skills_get`、`skills_set_enabled` |
| 工作区文件 | `workspace_list`、`workspace_read`、`workspace_write`、`workspace_edit` |
| 网页 | `web_fetch`（把公开的 http/https 网页抓取为文本，禁止访问内网地址） |
| 设备与系统意图 | `device_status`、`open_url`、`open_app`、`open_settings`、`set_alarm`、`set_timer`、`calendar_add_event`、`clipboard_get`、`clipboard_set` |
| 通知与健康 | `notify`、`health_status` |

- 另有两个只能通过斜杠命令使用、不会发给模型的工具：`sessions_compact`（`/compact`）和 `memory_command`（`/memory`）。
- 系统意图类工具（`open_*`、`set_alarm`、`set_timer`、`calendar_add_event`、`clipboard_*`）要求应用在前台。`calendar_add_event` 会打开预填好的日历页面，由用户自己保存。
- 定时任务运行时只能使用 18 个只读或显式开放的工具（网页、设备状态、通知、工作区文件、记忆搜索/读取/记住、会话列表/历史/发送、任务列表/运行记录、技能列表/读取、健康状态）；前台专用工具在后台会返回 `FOREGROUND_REQUIRED`。

**工作区人设文件**

参照 OpenClaw，`AGENTS.md`、`SOUL.md`、`USER.md`、`MEMORY.md` 保存在应用私有目录中，首次使用时按模板创建，并在 8 KB 预算内注入系统提示词（被截断时会标明）。助手可以用 `workspace_*` 工具读取和修改它们。

**技能**
- 支持内置、本地和工作区三层 `SKILL.md` 技能：解析 frontmatter、按优先级覆盖、启用/停用（有风险的技能需要确认）、详情页、从文件导入。
- 内置技能：`/compact`、`/list_tasks`、`/memory`、`/summarize_session`、`/task_helper`。声明了 `command-dispatch: tool` 的技能直接调用工具，不经过模型。

**定时任务**
- 支持一次性、按间隔（预设最短 15 分钟）和五段式 cron 计划；使用本地日期、时间选择器，cron 会预览下一次运行时间。
- 结果可以发到某个聊天会话，也可以在新的独立会话中运行。
- 可以编辑已有任务、启用/停用、立即运行、查看运行记录；系统拒绝精确闹钟时会给出提示。

**设置与诊断**
- 模型服务配置与连接测试、可选的本地记忆、主题（跟随系统/浅色/深色）、应用语言（跟随系统/English/简体中文）。
- 诊断页：模型服务、网络、调度器、通知权限、最近的自动化结果，可导出报告。
- 界面完整支持英文和简体中文（两种语言各 397 条字符串和 13 条复数字符串，由单元测试保证一致）。Android 13 及以上使用系统的按应用语言设置，Android 8 到 12 使用应用内切换。

**模型服务**

| 服务 | 接口 |
|---|---|
| 离线演示（FakeProvider） | 本地、确定性回复、无需密钥 |
| OpenAI | Responses API（`/v1/responses`），默认模型 `gpt-6-luna` |
| OpenAI 兼容 | chat/completions；如果 Base URL 指向 `api.openai.com`，会自动改走 Responses API |
| OpenAI Codex | 设备码 OAuth 登录，Responses 流式输出 |
| Claude | 原生 Anthropic Messages API |
| MiniMax、GLM、Kimi、Gemini、DeepSeek | OpenAI 兼容预设（chat/completions） |

所有远程服务都支持 SSE 流式输出，定时任务使用非流式路径。API 密钥和 Codex OAuth 凭据在设备上加密保存。

### 构建与测试

需要 JDK 17 和带 platform 36 的 Android SDK，`minSdk` 为 26。

```bash
export JAVA_HOME=/path/to/jdk17

# 第一次构建必须联网：会下载依赖并按 gradle/verification-metadata.xml 校验。
./gradlew :app:assembleDebug
./gradlew :app:testDebugUnitTest
./gradlew :app:lintDebug
./scripts/run_ktlint.sh

# 发布形态的打包
./gradlew :app:assembleQa :app:assembleRelease :app:bundleRelease
```

说明：
- 依赖缓存齐全后可以加 `--offline`。如果离线解析报缺少插件标记（plugin marker），先联网跑一次。
- 如果 Gradle 访问不了 Google Maven 但 `curl` 可以，用 `./scripts/seed_android_build_local_maven.sh` 填充项目内的备用仓库。
- 在 git worktree 里运行 ktlint 时要传入文件通配：`./scripts/run_ktlint.sh "app/src/**/*.kt" "*.kts" "app/*.kts"`。
- Debug 版本默认使用离线演示服务。不需要密钥也能看到工具卡片：发送 `What is my battery? [tool:device_status]`。

**真实服务测试（可选）**：`OpenAiLiveTest` 会调用真实的 OpenAI API，只有设置了环境变量 `ANDROIDCLAW_LIVE_OPENAI_KEY` 才会运行；`ANDROIDCLAW_LIVE_OPENAI_MODEL` 可以指定模型（默认 `gpt-6-luna`）。运行时从文件读取密钥到环境变量，不要把密钥提交到仓库。

```bash
ANDROIDCLAW_LIVE_OPENAI_KEY="$(cat /path/to/key.txt)" \
  ./gradlew :app:testDebugUnitTest --tests 'ai.androidclaw.runtime.providers.OpenAiLiveTest'
```

**真机/模拟器测试**：在 WSL 中驱动 Windows 上的 AVD，命令见上文英文部分的 “Device tests”。

### 架构

一个 Gradle 应用模块，在 `AppContainer` 中手动组装依赖：

- `app`：Application、Activity、依赖容器、启动维护。
- `ui`：主题、导航（4 个标签页：聊天、任务、技能、设置）、通用组件、语言切换。
- `feature`：聊天、任务、技能、设置、诊断、首次引导。
- `data`：Room（schema v5）、DataStore 设置、加密密钥存储、`WorkspaceStore`。
- `runtime`：智能体循环、提示词组装、模型服务、工具、技能、调度器、记忆。

技术栈：Kotlin、Jetpack Compose、Room、WorkManager、协程、kotlinx serialization、OkHttp。不使用依赖注入框架，也不引入厂商 SDK。

### 不做的事

不做浏览器自动化、外部聊天渠道（Telegram、WhatsApp、Slack 等）、远程桥接、shell 执行、语音唤醒和云同步。

### 许可证

Apache-2.0，见 [LICENSE](LICENSE)。
