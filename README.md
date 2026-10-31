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

| Chat | Tool card | Sessions drawer | Tasks | Task editor |
|:-:|:-:|:-:|:-:|:-:|
| <img src="site/assets/screens/en/chat.png" width="150" alt="Chat with a scheduled run posted into the main session" /> | <img src="site/assets/screens/en/tool_card.png" width="150" alt="Expanded device_status tool card with arguments and result" /> | <img src="site/assets/screens/en/drawer.png" width="150" alt="Session drawer with sessions and archive" /> | <img src="site/assets/screens/en/tasks.png" width="150" alt="Task list with run history" /> | <img src="site/assets/screens/en/task_editor.png" width="150" alt="Task editor with date and time pickers" /> |

| Skills | Settings | Assistant workspace | Diagnostics | Onboarding |
|:-:|:-:|:-:|:-:|:-:|
| <img src="site/assets/screens/en/skills.png" width="150" alt="Skills list with toggles" /> | <img src="site/assets/screens/en/settings.png" width="150" alt="Settings with provider, memory and workspace" /> | <img src="site/assets/screens/en/workspace.png" width="150" alt="Assistant workspace with persona files and prompt budget" /> | <img src="site/assets/screens/en/diagnostics.png" width="150" alt="Diagnostics with provider, scheduler and tools" /> | <img src="site/assets/screens/en/onboarding.png" width="150" alt="First-run onboarding" /> |

## Features

### Chat
- Transcript-first chat screen: top bar with the session title, a modal
  drawer for sessions (new, switch, rename, archive, restore, delete).
- Markdown rendering with code blocks and copy buttons; streaming replies
  with a Stop button.
- Tool calls appear as cards in the transcript: the result summary is the
  title, with the tool name and Done / Failed: CODE / Running below. Expanded
  cards show pretty-printed arguments and result (secret-like fields masked).
  Error codes such as `MEMORY_DISABLED`, `FOREGROUND_REQUIRED` and
  `PERMISSION_REQUIRED` come with a localized hint. Slash commands that
  dispatch straight to a tool show only the card.
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
The assistant can read and edit them with the `workspace_*` tools, and you can
edit them in Settings → Assistant workspace (file list with sizes, prompt
budget meter, monospace editor, reset to template with confirmation,
unsaved-changes guard).

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
- On Android 13+ the notification permission is requested when you save your
  first task (and from the Tasks banner).
- Isolated runs get their own session titled `<task> · <date> <time>`.

### Settings and diagnostics
- Provider setup with a connection test, optional local Memory (with an
  explanation of what it stores), Assistant workspace editor, theme
  (system/light/dark) and app language (system/English/简体中文).
- Diagnostics screen: provider, network, scheduler, notifications, recent
  automation results, exportable report.
- Full English and Simplified Chinese UI (435 translated strings and 13
  plurals in each locale, parity enforced by a unit test). Per-app language on Android 13+,
  in-app fallback on Android 8 to 12. Demo-provider replies and the tool
  summaries written for the model stay English in every language (a real
  model answers in yours).

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
- After one online run, `--offline` works (the v0.2.0 full suite and
  packaging ran offline).
- If Gradle cannot reach Google Maven but `curl` can, seed the project-local
  fallback repo with `./scripts/seed_android_build_local_maven.sh`.
- `./scripts/run_ktlint.sh` falls back to globs when `git ls-files` fails
  (for example WSL git in a Windows-git worktree) and exits non-zero when it
  finds problems or matches no files.
- Debug builds start with the offline demo provider. To see a tool card
  without a key, send `What is my battery? [tool:device_status]`.

Last full run (v0.2.0): unit tests 508 passed, 4 skipped (the OpenAI live
tests without a key), 0 failed; instrumentation 11/11 tests in 7 classes on
the `AndroidClawApi34` emulator; lint 0 errors; ktlint 0 findings; `qa`,
`release` APK and bundle build.

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

| 聊天 | 工具卡片 | 任务 | 任务编辑（Cron） |
|:-:|:-:|:-:|:-:|
| <img src="site/assets/screens/zh/chat.png" width="150" alt="定时任务结果发到主会话" /> | <img src="site/assets/screens/zh/tool_card.png" width="150" alt="工具卡片与记忆提示" /> | <img src="site/assets/screens/zh/tasks.png" width="150" alt="任务列表" /> | <img src="site/assets/screens/zh/task_editor.png" width="150" alt="新建任务：Cron 与下次运行预览" /> |

| 技能 | 设置 | 助手工作区 | 诊断 |
|:-:|:-:|:-:|:-:|
| <img src="site/assets/screens/zh/skills.png" width="150" alt="技能列表" /> | <img src="site/assets/screens/zh/settings.png" width="150" alt="设置：模型服务、记忆、工作区" /> | <img src="site/assets/screens/zh/workspace.png" width="150" alt="助手工作区：人设文件与提示词预算" /> | <img src="site/assets/screens/zh/diagnostics.png" width="150" alt="诊断：模型服务、调度器、工具" /> |

### 功能

**聊天**
- 以对话记录为主的聊天界面：顶栏显示会话标题，侧边抽屉管理会话（新建、切换、重命名、归档、恢复、删除）。
- 支持 Markdown 和代码块（可一键复制）；流式输出，可随时停止。
- 工具调用以卡片显示在对话中：标题是结果摘要，下方是工具名和“完成 / 失败：错误码 / 运行中”；展开后显示格式化的参数和结果（疑似密钥的字段会被遮盖）。`MEMORY_DISABLED`、`FOREGROUND_REQUIRED`、`PERMISSION_REQUIRED` 等错误码附带本地化提示。直接调用工具的斜杠命令只显示卡片。
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

参照 OpenClaw，`AGENTS.md`、`SOUL.md`、`USER.md`、`MEMORY.md` 保存在应用私有目录中，首次使用时按模板创建，并在 8 KB 预算内注入系统提示词（被截断时会标明）。助手可以用 `workspace_*` 工具读取和修改它们；你也可以在 设置 → 助手工作区 中编辑（文件列表与大小、提示词预算、等宽编辑器、确认后重置为模板、未保存提醒）。

**技能**
- 支持内置、本地和工作区三层 `SKILL.md` 技能：解析 frontmatter、按优先级覆盖、启用/停用（有风险的技能需要确认）、详情页、从文件导入。
- 内置技能：`/compact`、`/list_tasks`、`/memory`、`/summarize_session`、`/task_helper`。声明了 `command-dispatch: tool` 的技能直接调用工具，不经过模型。

**定时任务**
- 支持一次性、按间隔（预设最短 15 分钟）和五段式 cron 计划；使用本地日期、时间选择器，cron 会预览下一次运行时间。
- 结果可以发到某个聊天会话，也可以在新的独立会话中运行。
- 可以编辑已有任务、启用/停用、立即运行、查看运行记录；系统拒绝精确闹钟时会给出提示。
- Android 13 及以上在保存第一个任务时（以及在任务页提示条中）申请通知权限。
- 独立会话运行时会新建标题为 `<任务名> · <日期> <时间>` 的会话。

**设置与诊断**
- 模型服务配置与连接测试、可选的本地记忆（附说明）、助手工作区编辑器、主题（跟随系统/浅色/深色）、应用语言（跟随系统/English/简体中文）。
- 诊断页：模型服务、网络、调度器、通知权限、最近的自动化结果，可导出报告。
- 界面完整支持英文和简体中文（两种语言各 435 条翻译字符串和 13 条复数字符串，由单元测试保证一致）。Android 13 及以上使用系统的按应用语言设置，Android 8 到 12 使用应用内切换。离线演示的回复和写给模型的工具摘要在任何语言下都是英文（真实模型会用你的语言回答）。

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
- 联网跑过一次之后可以加 `--offline`（v0.2.0 的完整测试和打包都是离线跑的）。
- 如果 Gradle 访问不了 Google Maven 但 `curl` 可以，用 `./scripts/seed_android_build_local_maven.sh` 填充项目内的备用仓库。
- `./scripts/run_ktlint.sh` 在 `git ls-files` 不可用时（例如在 Windows git 创建的 worktree 中使用 WSL git）会改用文件通配；发现问题或没有匹配到文件时返回非零退出码。
- Debug 版本默认使用离线演示服务。不需要密钥也能看到工具卡片：发送 `What is my battery? [tool:device_status]`。

最近一次完整运行（v0.2.0）：单元测试 508 个通过、4 个跳过（未提供密钥的 OpenAI 真实服务测试）、0 个失败；仪器测试在 `AndroidClawApi34` 模拟器上 7 个类 11/11 通过；lint 0 错误；ktlint 0 问题；`qa`、`release` APK 和 bundle 均构建成功。

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
