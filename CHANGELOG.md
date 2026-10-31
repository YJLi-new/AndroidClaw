# Changelog

All notable changes to AndroidClaw.

## v0.2.0 - 2026-10-31 (rework)

A rework of the runtime tool layer and the whole UI. The data layer,
providers, scheduler and Room schema were kept.

### Runtime and tools
- Registered tools cut from 168 to 36: 34 model-facing tools plus 2
  slash-only tools (`sessions_compact` for `/compact`, `memory_command` for
  `/memory`). Every model-facing tool is now sent; before, only 100 of 143
  reached the model.
- Tool names are snake_case (`tasks_list`, `sessions_history`, ...). Old
  dotted names are still accepted as aliases.
- Native tool calling only: tool schemas go in the provider's tool field and
  are no longer listed again in the system prompt. Tool JSON per turn went
  from 46,158 to 12,889 bytes (unit test caps it at 24 KB and each
  description at 300 characters).
- Removed meta tools from the model surface (`*.doctor`, `*.handoff`,
  `*.example`, `*.export`/`*.import`, `*.stats`, `*.setup.matrix`,
  `runtime.portability.audit` and similar): 17 tool files and their tests.
  `runtime/tools` shrank from 29,361 to 3,406 lines.
- New tools: `web_fetch`, `device_status`, `open_url`, `open_app`,
  `open_settings`, `set_alarm`, `set_timer`, `calendar_add_event`,
  `clipboard_get`, `clipboard_set`, `notify`, `workspace_list`,
  `workspace_read`, `workspace_write`, `workspace_edit`. Intent tools need
  the app in the foreground and return `FOREGROUND_REQUIRED` from scheduled
  runs.
- Workspace persona files `AGENTS.md`, `SOUL.md`, `USER.md`, `MEMORY.md` in
  app-private storage, injected into the system prompt within 8 KB.
- Scheduled runs get an explicit set of 18 read-only or opt-in tools,
  including `notify`, `web_fetch` and the workspace tools.
- The offline demo provider (FakeProvider) gives short readable replies
  (`Reply: <message>`) and can trigger a tool with `[tool:<name>]` or
  `[tool:<name> {json}]`.
- Bundled skills use the new tool names; `task_helper` was rewritten.

### Providers
- New **OpenAI** preset: base URL `https://api.openai.com/v1`, default model
  `gpt-6-luna`.
- Requests to `api.openai.com` (the OpenAI preset, or an OpenAI-compatible
  base URL on that host) use the Responses API (`/v1/responses`) for streaming
  and tool calls. DeepSeek, GLM, Kimi, MiniMax, Gemini and custom
  OpenAI-compatible hosts keep chat/completions.
- Responses parsing is shared by OpenAI and OpenAI Codex.
- Optional live tests (`OpenAiLiveTest`, 4 tests) run only when
  `ANDROIDCLAW_LIVE_OPENAI_KEY` is set; `ANDROIDCLAW_LIVE_OPENAI_MODEL`
  overrides the model. Verified on 2026-10-31 with `gpt-6-luna`: plain reply,
  streaming, a tool round trip, and a full agent turn with `workspace_read`.

### UI
- Four tabs: Chat, Tasks, Skills, Settings. Diagnostics (the former Health
  tab) moved under Settings.
- Chat: session title in the top bar, session drawer (new, switch, rename,
  archive, restore, delete), transcript-first message list with markdown and
  code copy, collapsible tool cards, Stop while streaming, slash-command
  suggestions, overflow menu for search, share/export, usage and compact.
- Tasks: list with a New task button, full editor with local date and time
  pickers, interval presets, cron with next-run preview, readable delivery and
  target pickers, editing of existing tasks, run now, run history,
  exact-alarm hint.
- Skills: badges, confirmation before enabling, detail screen, import.
- Settings: provider setup and connection test, Memory, theme
  (system/light/dark), app language, Diagnostics, About. First-run
  onboarding offers the offline demo or provider setup.
- Complete Simplified Chinese translation: 397 strings and 13 plurals in
  English and `zh-rCN`, parity checked by a unit test. Hard-coded user-visible
  strings went from 174 to 0. Per-app language on Android 13+, in-app
  fallback on Android 8 to 12.
- Edge-to-edge layout and dark-theme-aware components.

### Fixed
- Dotted tool names were rejected by OpenAI and Anthropic, so tool calls
  failed on real providers; names (including those in history) are now
  provider-safe.
- 43 of 143 tools were silently dropped from every request.
- Tool payloads never reached the model, only a summary, so `/memory list`
  showed "Found stored memories." without the memories.
- Cancelling during a tool call left a call without a result, which providers
  reject on the next turn.
- Duplicate tool-call ids in one response produced colliding result rows.
- UI notices such as "Turn failed" and "AndroidClaw is ready" were sent to the
  model as system messages.
- FakeProvider reused tool results from earlier turns for a repeated marker.
- Scheduled runs had no notify, web or workspace tools.
- Every current OpenAI model returned HTTP 400 for chat/completions with
  tools; the official endpoint now uses the Responses API.
- Responses streaming trimmed text deltas, gluing words together
  ("Hello " + "there" became "Hellothere"); this also affected OpenAI Codex.
- Hard-coded light colors broke the dark theme; the window had no background
  (black flash on start).
- Rich-text link detection was quadratic in message length.
- The transcript and usage observed the same Room query twice.
- The task target picker showed "Main session" twice.
- A re-enabled task kept a stale next-run time in the past.
- Tasks could not be edited and sessions could not be deleted.
- Fast typing in the composer dropped characters ("hello" became "hell").

### Validation
- Unit tests: 687/687 after the UI merge (665 before the rework; the
  16,000-line test file for the removed meta tools went with them).
- Instrumentation on the `AndroidClawApi34` emulator: 11/11; manual journey
  13/13 steps with no crash lines in logcat.
- `assembleDebug`, `lintDebug` and ktlint green.

## v0.1.0

First public beta: durable sessions, streaming providers (OpenAI-compatible,
OpenAI Codex, Claude, MiniMax, GLM, Kimi, Gemini, DeepSeek), `SKILL.md`
skills, once/interval/cron automations, local Memory, QA and release
packaging.
