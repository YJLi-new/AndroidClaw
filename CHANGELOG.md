# Changelog

All notable changes to AndroidClaw.

## v0.3.0 - 2026-11-01 (media, web search, voice)

Adds the assistant features that were missing next to common chat assistants:
photos in chat, web search, image generation and editing, video generation,
and voice. X/Twitter integration and an xAI/Grok provider are out of scope.
No new runtime dependency; `versionCode` 3.

### Photos in chat
- Attach button in the composer opens the system photo picker (no storage
  permission). Up to 4 images per message as removable chips; image-only
  messages are allowed.
- Import with a sampled decode (a 12 MP photo never becomes a full-resolution
  bitmap: 4000×3000 → 1176×1568, 177 KB, 92 ms, Java heap +0.8 MB on the
  emulator), EXIF orientation applied, metadata dropped, long side ≤ 1568 px,
  JPEG ≤ 1.5 MB, 256 px thumbnail, 8 MB in-memory thumbnail cache.
- Room schema 5 → 6: new `message_attachments` table (cascade delete with the
  message). Files live under `filesDir/attachments/<sessionId>/` and are
  deleted with their message or session.
- Transcript thumbnail grid, full-screen viewer with Save (MediaStore
  Pictures/AndroidClaw on Android 10+, "Save as" on 8–9) and Share. Chat
  export lists attachments.
- Encoding per provider: OpenAI Responses `input_image`, Claude `image`
  blocks, chat/completions `image_url` parts. Each image is preceded by a
  `[attachment <id>: <name> <w>×<h>]` marker. Only the 2 latest image turns
  are resent (≤ 4 images, ≤ 8 MB encoded).
- Vision on by default for OpenAI, Codex, Claude and Gemini; a "Model accepts
  images" switch for DeepSeek, GLM, Kimi, MiniMax and custom endpoints. An
  image-related 4xx maps to `IMAGES_UNSUPPORTED` with a localized hint.

### Web search
- One rule, shown in Settings → Web search (Automatic / Off): a saved Brave
  Search key → typed `web_search` tool for every provider and in scheduled
  runs; otherwise the provider's hosted search (OpenAI Responses `web_search`,
  Claude `web_search` server tool with `max_uses` 5); otherwise off, with the
  reason shown. Codex stays off (its endpoint was not verified).
- `web_search(query, count ≤ 10, freshness?)` calls Brave `res/v1/web/search`
  and returns title, URL and snippet, at most 8 KB.
- Hosted results are parsed (OpenAI `web_search_call` and `url_citation`;
  Claude `server_tool_use`, `web_search_tool_result` and citations) into up to
  8 deduplicated sources. Replies show a "Searched the web" card and a Sources
  row; history replay sends text only.
- The Brave key is stored encrypted, with Save, Test and Remove.

### Image and video generation
- `image_generate(prompt, size?, quality?, input_attachment_ids?)` on the
  OpenAI Images API: generations, or edits of attached photos. The image is
  saved as a `GENERATED_IMAGE` attachment; the model sees only
  `Generated image <id>, <w>×<h>`. 180 s timeout; Stop cancels the call.
- `video_generate(prompt, seconds?, size?)` starts an OpenAI video job. A
  unique one-shot `VideoJobWorker` (WorkManager, backoff, gives up after
  30 minutes) downloads the mp4 (≤ 50 MB), adds a message with a poster frame
  and posts a notification. Tap plays it in the system video player.
- Both use the saved OpenAI API key under any chat provider, are offered only
  when that key exists, and are never offered to scheduled runs. Settings →
  Image and video holds the model ids.
- Debug builds with the offline demo provider use a fake image backend that
  draws a test PNG, so image generation works without a key.

### Voice
- Composer mic (shown when the box is empty and a recognizer exists) opens the
  system speech recognizer in the app language. The text goes into the
  composer; "Send voice input right away" (Settings → Voice) defaults to off.
- Read aloud on every assistant reply with platform TextToSpeech (created on
  first use, shut down with the chat screen). Markdown stripped, code blocks
  replaced by a short phrase, Stop always available.
- "Voice replies" in the chat menu reads replies to dictated messages aloud.
  The mic never reopens by itself.
- Diagnostics → Voice: recognizer, TTS engines, TTS support for the app
  language.

### Tools and FakeProvider
- Model-facing tools 34 → 37 (`web_search`, `image_generate`,
  `video_generate`); registered 36 → 39. Tool JSON per turn 12,889 →
  14,587 bytes (cap 24 KB). Scheduled runs 18 → 19 tools (`web_search`).
- FakeProvider: replies to photos with `Images: N (w×h, …)`; `[search:<query>]`
  returns a hosted-search-style reply with two example sources;
  `Draw a fox [tool:image_generate]` calls the tool with the prompt.

### Behavior decisions
- Image `detail` stays at the provider default: a probe read Latin and
  Chinese text in a photo at the default setting.
- Default image model `gpt-image-2.5-sunburst`: the newest `gpt-image-*` id
  listed by `/v1/models`; the 2.5 tie with `flare` was broken by the newer
  `created` timestamp. Default video model `sora-2`. Both are Settings fields.
- Voice uses the system recognizer, so the app does not request
  `RECORD_AUDIO`. The manifest only adds `<queries>` for
  `RECOGNIZE_SPEECH` and `TTS_SERVICE`.

### Fixed
- Claude streaming dropped whitespace-only text deltas (" ", "\n\n"), which
  glued words together.
- Chinese: the Web search settings said 提供方 while the rest of Settings uses
  服务商.
- `TasksViewModelTest`: a Turbine race in the toggle test (an action message
  emitted after the awaited state) made the suite flaky; fixed in the test
  (c28b219).

### Verification
- Unit tests 612 passed of 619, 7 skipped (live tests without a key),
  0 failed (v0.2.0: 510 of 514). Instrumentation 17/17 on `AndroidClawApi34`.
  Lint 0 errors; ktlint 0 findings; debug, QA, release APK and bundle build.
- Live OpenAI tests with a key: 7/7 (vision, streaming, non-streaming, tool
  round trip, hosted search with 1 citation, agent turn, image generation).
  Video generation was not run live.
- Emulator journey with the offline demo provider: English 23/23, Chinese
  17/17, 0 crashes.
- Startup (QA, paired fresh installs on the same emulator): +6.1% against
  v0.2.0. QA APK 3,093,639 → 3,239,799 bytes (+4.7%).

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
- Tool cards: the result summary is the title, with the tool name and
  Done / Failed: CODE / Running below; expanded cards pretty-print arguments
  and results and mask secret-like fields. `MEMORY_DISABLED`,
  `FOREGROUND_REQUIRED` and `PERMISSION_REQUIRED` show a localized hint.
  Slash commands that dispatch straight to a tool show only the card.
- Settings → Assistant workspace: list of persona and note files with sizes,
  8 KB prompt-budget meter, monospace editor, save, reset to template with
  confirmation, unsaved-changes guard.
- Memory toggle explains what is stored and where.
- On Android 13+ the notification permission is requested when the first task
  is saved and from the Tasks banner.
- Isolated task sessions are titled `<task> · 2026-10-31 07:02` instead of a
  raw ISO timestamp.
- Skill detail shows one state label (Disabled wins over Ready / Invalid /
  Missing tool / Desktop only).
- Complete Simplified Chinese translation: 435 translated strings and 13
  plurals in English and `zh-rCN`, parity checked by a unit test. Hard-coded user-visible
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
- The merged build crashed on launch: a FakeProvider regex had unescaped
  `}` / `]`, which the JVM accepts and Android ICU rejects.
- Slash direct dispatch stored the full summary plus a debug
  `Active skills: …` line, which was also sent back to the model.
- The demo provider's post-tool reply echoed the raw tool result; it now says
  `Done with <tool>: <summary>`.
- The rename dialog put the cursor before the old name; it now selects it.
- "AndroidClaw is ready.", "Turn cancelled." and "Turn failed" notices stayed
  English in Chinese.
- Cancelled tool rows had no error code (now `CANCELLED`), and failed cards
  showed "Done".
- No runtime notification-permission request existed on Android 13+.
- Skill detail could show "Disabled" and "Ready" at the same time.
- `scripts/run_ktlint.sh` linted nothing and exited 0 in Windows-git
  worktrees; it now falls back to globs and fails when nothing matches.
- Provider-header tests hard-coded app version 0.1.0.
- A workspace ViewModel test leaked a coroutine past the Main-dispatcher
  reset and crashed the next test class.

### Known limitations
- Demo-provider replies and the tool summaries written for the model (tool
  card titles such as "Memory is off. …") are English in every app language.
  A real model answers in the user's language; the UI around them (status,
  error hints, notices) is localized.

### Validation
- Unit tests: 510 passed, 4 skipped (OpenAI live tests without a key), 0
  failed, 514 total (665 before the rework; the 16,000-line test file for the
  removed meta tools went with them).
- Instrumentation on the `AndroidClawApi34` emulator: 11/11 tests in 7
  classes.
- Emulator journeys: English 13/13 and Chinese 10/10 steps, 0 crash lines;
  scheduler once / interval / main / isolated runs observed on device (4/4).
- `assembleDebug`, `lintDebug` (0 errors), ktlint (0 findings), `assembleQa`,
  `assembleRelease` and `bundleRelease` green, run with `--offline`.
- App version 0.2.0 (versionCode 2).

## v0.1.0

First public beta: durable sessions, streaming providers (OpenAI-compatible,
OpenAI Codex, Claude, MiniMax, GLM, Kimi, Gemini, DeepSeek), `SKILL.md`
skills, once/interval/cron automations, local Memory, QA and release
packaging.
