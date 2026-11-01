# PLANv13 - Media, web search and voice

Status: active — batch 1 running (2026-11-01)
Owner: main session (planning, merges, pushes) + Opus subagents (execution)
Started: 2026-11-01
Supersedes: `PLANv12.md` (kept at the root, marked superseded)

## User request (verbatim, 2026-11-01)

> grok bot的各种功能现在在这里都有吗
>
> 写planv13，但不要x和grok功能

Context: the main session compared v0.2.0 (`main` at c043def) with the Grok
assistant (the Grok app and @grok on X, feature list as known to mid-2026).
Already present: multi-turn chat with saved sessions, custom instructions and
persona, memory, scheduled tasks, reading a given URL. Missing: web search,
image input, image generation and editing, video generation, voice. Also
missing but excluded by the user: X integration and a Grok (xAI) model preset.

Interpretation recorded by the main session: v13 adds the missing features
except X and Grok, inside the AGENTS.md boundaries. Delivery is `rework/v13`
pushed with a PR; merging into `main` waits for the user's approval.

## Scope

In:
1. Web search.
2. Image input (photos from the system photo picker) for vision models.
3. Image generation and editing.
4. Video generation, gated on a live probe (M5).
5. Voice: tap-to-talk input and read-aloud replies.

Out (user): X/Twitter integration (posts, search, mentions); a Grok/xAI
provider preset or any Grok-specific behavior.

Out (AGENTS.md): hands-free or realtime voice conversation, continuous audio,
wake word, camera capture, screen recording.

Deferred (not asked for; seen in the same comparison): document attachments
(PDF, text files), a reasoning ("think") toggle, cloud speech (OpenAI
transcription and TTS) for devices without system speech engines.

## Current state (survey 2026-11-01, `main` c043def)

- `ModelMessage.content` is a String; no attachments anywhere.
  `ProviderCapabilities.supportsImages` / `supportsFiles` exist but are unused.
- Room `AndroidClawDatabase` version 5; `messages` has no attachment columns.
- 36 tools (34 model-facing); `web_fetch` reads one URL; no search backend.
- Providers: FakeProvider, OpenAI (Responses), OpenAI-compatible
  (chat/completions), OpenAI Codex (Responses), Claude (Messages), presets
  MiniMax, GLM, Kimi, Gemini, DeepSeek.
- No speech code; no `RECORD_AUDIO`; manifest `<queries>` holds launcher
  intents only.
- WorkManager has one worker (`TaskExecutionWorker`). FileProvider is declared.
  minSdk 26, targetSdk 36. Test deps include OkHttp MockWebServer; no
  espresso-intents.

## Target end state (acceptance)

### M1. Attachments and image input (`media` line)
- Room 5 → 6: new table `message_attachments` (id, messageId FK with cascade,
  sessionId, kind = `USER_IMAGE` | `GENERATED_IMAGE` | `VIDEO`, mimeType,
  displayName, relativePath, byteCount, width, height, durationMs nullable,
  createdAt; index on messageId). Migration test against exported `6.json`.
- Files live in `filesDir/attachments/<sessionId>/`. Deleting a session
  deletes its folder; deleting a message deletes its files. No startup scan.
- Composer attach button → `PickVisualMedia` (system photo picker; no
  permission on any API level; document picker fallback on old devices). Up to
  4 images per message, shown as removable thumbnail chips before sending.
- Import: sampled decode (never a full-resolution bitmap in memory), EXIF
  orientation applied and metadata dropped, long side ≤ 1568 px, JPEG q85,
  ≤ 1.5 MB per image, plus a 256 px thumbnail on disk. Bounded in-memory
  thumbnail LruCache (8 MB). No Coil.
- `ModelMessage` gains `attachments`, read from disk on IO at request time.
  Encoding: OpenAI Responses `input_image` (data URL), Claude `image` blocks
  (base64), OpenAI-compatible `image_url` parts (data URL); FakeProvider
  replies deterministically with the image count and sizes.
- Each user image is preceded by a text marker
  `[attachment <id>: <name> <w>×<h>]` so the model can reference it in tool
  calls (M3 edits).
- Request bound: images are resent only for the latest 2 user turns that carry
  images, at most 4 images and 8 MB of encoded image data per request; older
  images become their text marker.
- `ProviderCapabilities.supportsImages` per provider and preset: OpenAI, Codex,
  Claude, Gemini on; DeepSeek, GLM, Kimi, MiniMax and custom OpenAI-compatible
  off, with a "Model accepts images" switch in their provider settings. When
  off, the attach button explains why. A provider 4xx about images maps to
  `IMAGES_UNSUPPORTED` with a localized hint.
- Transcript shows images inline (thumbnail grid, new `ChatAttachments.kt`).
  Tap opens a full-screen viewer (sampled to screen size) with Save (MediaStore
  Pictures on API 29+, document "Save as" on 26–28; no storage permission) and
  Share through FileProvider.
- Chat export lists attachments by name.

### M2. Web search (`search` line)
- One rule decides the search path, shown in Settings → Web search
  (Automatic / Off, Brave key field with a Test button):
  1. Brave Search API key saved → typed `web_search` tool for every provider
     and in scheduled runs.
  2. No Brave key and the provider has hosted search → hosted search: OpenAI
     Responses `web_search` tool entry; Claude `web_search` server tool with
     `max_uses` 5. Works with the model key alone. Codex only if the probe
     shows its endpoint accepts the tool.
  3. Otherwise search is off, and Settings says why.
- Typed `web_search(query, count ≤ 10, freshness?)` → Brave
  `res/v1/web/search`; returns title, url and snippet per result, result
  ≤ 8 KB; errors map to the existing network codes. OpenClaw-compatible name
  and core arguments. Read-only, allowed in scheduled runs.
- Hosted results parsed: OpenAI `web_search_call` items and `url_citation`
  annotations; Claude `server_tool_use` / `web_search_tool_result` blocks and
  text `citations`. Stored in `providerMeta` as a bounded sources list (≤ 8,
  deduplicated by URL). History replay sends only the text.
- UI: a "Searched the web: <query>" card and a Sources row under the reply
  (new `ChatSources.kt`, one call site in the transcript); tapping a source
  opens the browser.
- Brave key stored encrypted like provider keys.
- Claude hosted search is contract-tested with MockWebServer only (no Claude
  key on this host); Brave likewise (no Brave key).
- Tool budget holds: ≤ 40 model-facing tools, ≤ 24 KB tool payload
  (existing unit test).

### M3. Image generation and editing (`gen` line)
- Typed `image_generate(prompt, size?, quality?, input_attachment_ids?)` on
  the OpenAI Images API: generations, or edits with the referenced attachments
  sent as multipart images. `b64_json` is saved as a `GENERATED_IMAGE`
  attachment on the tool result message; the model sees only
  `Generated image <id>, <w>×<h>`, never base64.
- Uses the saved OpenAI API key whatever chat provider is active, and is
  offered only when that key exists. Model id is configurable in Settings →
  Image and video; the default comes from the live probe (pre-registered rule:
  the newest `gpt-image-*` id that `/v1/models` lists).
- Not offered to scheduled runs in v13 (each call costs money).
- Call timeout 180 s; Stop cancels the call.
- Debug builds ship a fake image backend (a deterministic PNG drawn with
  Canvas) so the offline demo and the emulator journey cover it without a key.

### M4. Voice (`voice` line)
- Mic button in the composer when the text field is empty →
  `RecognizerIntent.ACTION_RECOGNIZE_SPEECH`. The system recognizer UI owns the
  microphone, so the app needs no `RECORD_AUDIO`. Language follows the app
  language. The result goes into the composer; setting "Send voice input right
  away" (default off).
- The mic button is hidden when no recognizer resolves. Manifest `<queries>`
  gains `RECOGNIZE_SPEECH` and `TTS_SERVICE`.
- Read aloud: a per-assistant-message action using platform `TextToSpeech`,
  created lazily on first use and shut down when the chat ViewModel is
  cleared. Markdown is stripped and code blocks are replaced by a short
  localized phrase. Stop is always available while speaking.
- "Voice replies" toggle in the chat menu: replies to voice-input turns are
  read aloud automatically. The mic never reopens by itself (AGENTS.md: no
  continuous audio).
- Speech launcher and TTS sit behind small interfaces with fakes for tests (no
  espresso-intents).
- Diagnostics shows recognizer and TTS availability, and whether TTS supports
  the app language.

### M5. Video generation (`gen` line, gated)
- Gate (pre-registered): `/v1/models` on the user's key lists a `sora-*`
  model. If not, M5 is recorded as skipped and nothing is built.
- Typed `video_generate(prompt, seconds?, size?)` → OpenAI Videos API; returns
  at once with a job id. A unique one-shot `VideoJobWorker` (WorkManager,
  exponential backoff, gives up after 30 min) checks the job, downloads the mp4
  (≤ 50 MB) into the session folder, inserts an assistant message with a
  `VIDEO` attachment and posts a notification. Nothing runs while no job
  exists.
- Transcript shows a poster frame (`MediaMetadataRetriever`); tap plays it in
  the system video player through FileProvider. No Media3 or ExoPlayer.
- Listing models is free; a live generation runs only after the user approves
  the cost.

### M6. Integration, docs, delivery (`int` and `doc` lines)
- Every new string in `values` and `values-zh-rCN` (parity test stays green).
- FakeProvider scripted paths: image reply, hosted-search-style sources,
  `image_generate` tool call, voice turn.
- Emulator journey on `AndroidClawApi34`, EN and zh: attach a photo (pushed
  into MediaStore with adb), send, see the thumbnail and the FakeProvider
  reply; open the viewer and save; image generation through the fake backend;
  the Web search settings screen; the mic path with the fake launcher in
  instrumentation and the real intent resolving (or the button hidden) on the
  device; read aloud; zero crash lines in logcat.
- Live tests gated by `ANDROIDCLAW_LIVE_OPENAI_KEY` (skipped without it):
  vision on a generated test image with known text and color; hosted web search
  returns at least 1 citation; image generation returns a decodable PNG; video
  only per the M5 gate.
- README (EN + 中文), CHANGELOG 0.3.0, `docs/KNOWN_LIMITATIONS.md`, `site/`,
  screenshots under `handoff/screens/`.

### Quality gates (every line)
- `./scripts/run_ktlint.sh`, `:app:assembleDebug`, `:app:testDebugUnitTest`,
  `:app:lintDebug`, `:app:assembleQa :app:assembleRelease :app:bundleRelease`
  green; instrumentation green on the AVD.
- No new runtime dependency. The photo picker is in androidx.activity; speech,
  TTS, MediaStore and MediaMetadataRetriever are platform APIs. Any exception
  is recorded under Decisions first.
- Startup: no new work before the first frame (TTS, attachments and workers
  are lazy); `am start -W` TotalTime within +10% of the v0.2.0 baseline on the
  AVD, measured as a fresh-install pair on a warm emulator (v0.2.0 QA: 722 ms
  median, 683–750).
- Memory: importing a 12 MP photo never allocates a full-resolution bitmap
  (unit test on the sampling math, device check of peak Java heap ≤ +32 MB
  during import).
- Room migration 5 → 6 tested. CI green on the PR before merge.

## Execution layout

- Main checkout `E:\OPC\AndroidClaw` (`main`, then `rework/v13`): main
  session only. Holds `PLANv13.md`, `AGENTS.md`, `handoff/`, `notes/`.
- Worktrees created by the main session with Windows git:
  `E:\OPC\AndroidClaw-wt\<line>` on `rework/<line>`. Gradle in WSL under
  `flock /tmp/androidclaw-full.lock`. One line owns the emulator at a time.
- Rules every agent reads first: `handoff/agent_rules.md`. Line plans
  `notes/<line>_plan.md` are written before each dispatch.
- File ownership, to keep parallel merges clean:
  - `media`: `data/db` (+ schema 6), attachment store and repository,
    `ModelMessage` and provider request encoding, composer attach, new
    `ChatAttachments.kt`, image viewer.
  - `search`: `web_search` tool, Web search settings and Brave key store,
    search-only functions in provider request building and response parsing,
    sources in `providerMeta`, new `ChatSources.kt` with one transcript call
    site.
  - `gen`: image and video tools and backends, `VideoJobWorker`, Image and
    video settings, generated image and video rendering inside
    `ChatAttachments.kt`.
  - `voice`: composer mic, read-aloud message action, voice settings, speech
    and TTS adapters, diagnostics rows.

## Batches

0. **Main:** mark PLANv12 superseded, write this plan, worktrees, line plans;
   dispatch after the user's go-ahead.
1. **Parallel:** `media` (M1) and `search` (M2). Emulator: `media`, whose
   first step also measures the v0.2.0 startup baseline. Each line starts with
   its live probe (vision on `gpt-6-luna` for `media`; hosted `web_search`
   tool type and response shape for `search`) and hands the numbers back
   before building on them.
2. **Main merges into `rework/v13`; parallel:** `gen` (M3, then the M5 gate)
   and `voice` (M4). Emulator: `voice`; `gen` is JVM-only and its device
   checks move to `int`.
3. **`int`** (journey, live tests, bug fixes), then **`doc`**; main runs the
   full gate, pushes `rework/v13`, opens the PR; merge on the user's approval.

## Pre-registered readouts

- `media`: vision probe (accepted yes/no, latency), import size and time for a
  12 MP sample, migration test result, startup baseline, unit counts.
- `search`: hosted search probe (tool type accepted, citation count,
  latency), Brave contract tests, tool payload bytes after the new tool, unit
  counts.
- `gen`: image and video model ids from `/v1/models` (names only), one live
  image generation (latency, bytes), M5 go/no-go.
- `voice`: recognizer and TTS availability on the AVD, unit and
  instrumentation counts.
- `int`: journey EN/zh pass counts, crash lines, live pass/skip counts,
  startup delta, APK size delta.

## Decisions

- 2026-11-01: X and Grok excluded per the user. No xAI preset.
- 2026-11-01: Search uses the typed Brave tool when a Brave key is saved,
  otherwise the provider's hosted search where it exists, otherwise off. It
  works after install with only the model key on OpenAI and Claude, keeps an
  OpenClaw-compatible typed tool for every provider and for scheduled runs,
  and one rule decides which path runs.
- 2026-11-01: Image generation is a typed tool on the OpenAI Images API, not
  the Responses hosted image tool, so it works under any chat provider, fits
  the Tool Contract, and is testable with FakeProvider and MockWebServer.
- 2026-11-01: Voice is tap-to-talk through the system recognizer plus platform
  TextToSpeech: no `RECORD_AUDIO`, no realtime audio, and the mic never
  reopens by itself.
- 2026-11-01: Photo picker only, no camera capture (AGENTS.md excludes camera
  workflows).
- 2026-11-01: No new runtime libraries (no Coil, no Media3): platform decoding
  with sampling and the system video player.
- 2026-11-01: Version 0.3.0 (versionCode 3).

## Running ledger

- 2026-11-01: Plan written after the Grok comparison. PLANv12.md stays tracked
  at the root, marked superseded: `docs/` is gitignored, so moving it to
  `docs/past_plans/` would drop it from the repository.
- 2026-11-01: User: "开工". Branch `rework/v13`; batch 1 dispatched (`media`,
  `search`).
- 2026-11-01: `media` vision probe (gpt-6-luna, 512×512 red PNG, 5×7 pixel
  font): HTTP 200 ×3, colour 3/3, text 0/3. Startup baseline v0.2.0 QA:
  TotalTime median 2428 ms (2191–3465, 5 cold starts). Decision: continue M1
  with one clarifying large-text probe (`notes/media_plan.md` Revision 1).
- 2026-11-01: `search` line done (5eb0f1d, merged at 378b27b): hosted
  `{"type":"web_search"}` accepted with function tools, HTTP 200 non-stream
  and stream, 1 citation each, ~7.1–7.5 s; live hosted test pass; tool payload
  12,889 → 13,343 B, model-facing tools 34 → 35; Brave contracts 9/9, Claude
  hosted contracts 5/5; unit 510/514 → 543/548 (skips are live tests);
  assembleDebug + testDebugUnitTest + lintDebug + ktlint green. Not run on
  this line: QA/release builds, instrumentation.
- Open bug for `int` (found by `search`, outside its area): the Claude stream
  drops whitespace-only text deltas, which can glue words together.
- 2026-11-01: `media` line done (d43a74f, merged at 42958d2; 6 append-only
  conflicts with `search`, both sides kept). Probe 4 (TrueType `AC13 安卓`,
  default detail): HTTP 200, 1824 ms, Latin and Chinese text read → `detail`
  stays default. 12 MP import: 4000×3000 → 1176×1568, 176,931 B, 92 ms, Java
  heap +0.8 MB (native +18.5 MB). Migration 5 → 6 pass; live vision 3740 ms
  pass; unit 510/514 → 532/537; instrumentation 13/13; debug/QA/release/AAB,
  lint 0 errors, ktlint green; paid calls 5/6. Startup, fresh-install pair:
  v0.2.0 722 ms (683–750) vs M1 726 ms (696–771), +0.6%; the earlier 2428 ms
  was a cold emulator. Device: attach, send, viewer, Save, Share, no crashes.
- 2026-11-01: Merge gate on `rework/v13`: run 1 hit a Turbine race in
  `TasksViewModelTest` (toggle emits an action message after the awaited
  state; tasks code untouched by v13), fixed test-only in c28b219. Run 2 on
  81c984e: ktlint 0, assembleDebug + unit + lint green; unit 565 passed of 571,
  6 skipped (live), 0 failed; lint 0 errors, 28 warnings. Batch 2 dispatched
  from 81c984e (`gen`, `voice`).
