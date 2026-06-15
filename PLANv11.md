# PLANv11 - Continuous validated hardening

Status: active
Owner: repository / Codex agents
Started: 2026-06-15
Supersedes: `docs/past_plans/PLANv10.md`

## Purpose

AndroidClaw now has the v0 runtime shape in place: durable sessions, typed tools,
`SKILL.md` skills, scheduled automations, multiple providers, OpenAI Codex OAuth,
optional local memory, a refreshed UI, a project page, and release artifacts. This
plan keeps the repository aligned while we continue small, evidence-backed
hardening cycles instead of broad rewrites.

The active goal is to make the current Android-native single-APK app more robust,
legible, and verifiable with each cycle.

## Current constraints

- Preserve the lightweight Android-native host model: no Node.js, Docker,
  Chromium, desktop gateway dependency, or remote-first companion runtime.
- Keep production code in the single `app` Android module.
- Keep manual dependency wiring; do not introduce DI frameworks.
- Prefer Room, WorkManager, coroutines, kotlinx serialization, OkHttp, and typed
  native tools.
- Prefer deterministic JVM/Robolectric validation over live network/device tests
  unless a change specifically requires a device.
- Treat provider responses, memory contents, imported skills, and cross-session
  facts as untrusted boundary data.

## Ongoing workstreams

1. **Provider/OAuth resilience**
   - Keep OpenAI-compatible, Anthropic, DeepSeek/Gemini-compatible, and OpenAI
     Codex failures actionable and sanitized.
   - Preserve provider-specific auth behavior while sharing obvious parser and
     transport hardening.
2. **Memory correctness**
   - Keep memory optional, local-only, bounded, and explicitly framed as
     untrusted context.
   - Improve extraction conservatively and maintain user opt-out behavior.
3. **Session and compact behavior**
   - Keep compaction visibly hiding older turns while preserving durable history
     and summary context.
4. **UI/UX fit and accessibility**
   - Keep phone-sized layouts usable, especially chat input, provider settings,
     and task creation.
5. **Docs, release, and project-page truth**
   - Keep repo docs, page content, screenshots, and release links synchronized
     with the actual app.

## Validation gates

Default fast loop from repo root:

```bash
export ANDROIDCLAW_JAVA_HOME=/home/lanla/.local/jdks/jdk-17.0.18+8
export JAVA_HOME="$ANDROIDCLAW_JAVA_HOME"
export PATH="$JAVA_HOME/bin:$HOME/.local/bin:$PATH"

./scripts/run_ktlint.sh
./gradlew --offline --no-daemon --console=plain --no-configuration-cache --no-build-cache :app:assembleDebug :app:testDebugUnitTest :app:lintDebug
```

Use narrower test filters first when changing a focused subsystem. Run
`:app:connectedDebugAndroidTest` only when a Linux-visible emulator/device is
available, or use the repo's Windows AVD scripts for manual QA.

## Running ledger

- 2026-06-15: Adopted `PLANv11.md` as the active continuous hardening plan
  because all previous `PLANv*.md` files were archived under `docs/past_plans/`.
  Next slice: deduplicate and harden provider HTTP error-message extraction so
  object-shaped rejection payloads remain actionable instead of falling back to
  raw JSON or generic HTTP 400 text.
- 2026-06-15: Added shared provider error-message extraction for OpenAI-
  compatible, Anthropic, and OpenAI Codex Responses providers. The parser now
  handles string, root-level, and object-shaped `error.detail`/`message` payloads
  with whitespace sanitization. Focused provider tests, ktlint, and the full
  offline fast loop passed.
- 2026-06-15: Extended the same sanitization path into OpenAI Codex OAuth error
  formatting, including bare `error_description` payloads that some OAuth
  servers return without an `error` code. Also ignored local agent-tooling
  artifacts (`.claude/`, `.codex`, `skills/`, `HANDOFF.md`) so future git
  audits show only repo-intended changes.
- 2026-06-15: Hardened `sessions.compact` boundary validation so the tool only
  stores a compaction boundary that exists in the active session. This prevents
  stale or cross-session message ids from producing a summary that claims older
  messages were hidden while the UI cannot actually apply the boundary.
- 2026-06-15: Added an explicit active-session existence check to
  `sessions.compact`. The tool now fails with `MISSING_SESSION` instead of
  reporting success when invoked with a stale/nonexistent session id.
- 2026-06-15: Made `MessageRepository` a required dependency of the built-in
  tool registry so `sessions.compact` boundary validation cannot be accidentally
  disabled by future registry wiring.
- 2026-06-15: Added startup maintenance repair for stale or cross-session
  compaction boundaries. Invalid boundaries are cleared while summary text is
  preserved, preventing legacy/corrupted state from pretending old messages are
  hidden when the boundary cannot be applied.
- 2026-06-15: Hardened task tool numeric parsing so model-provided automation
  arguments cannot overflow into wrapped retry counts or unsafe interval
  durations. Oversized `maxRetries` and `repeatEveryMinutes` values now fail as
  `INVALID_ARGUMENTS` instead of creating surprising tasks or escaping the tool
  failure path.
- 2026-06-15: Hardened memory search/list limit parsing. Explicit malformed,
  non-positive, or over-maximum `limit` values now fail with
  `INVALID_MEMORY_LIMIT` instead of silently falling back to defaults or relying
  on repository clamping, keeping model-provided memory tool arguments typed and
  bounded at the boundary.
- 2026-06-15: Hardened task target-session resolution for
  `targetSessionAlias=current`. Task create/update now verifies the session id
  from the tool execution context still exists before storing it, preventing
  stale contexts from creating automations that point at missing sessions while
  later payload rendering falls back to main.
- 2026-06-15: Fixed `tasks.update` metadata-only patches so they preserve the
  existing schedule and next-run timestamp instead of revalidating an unchanged
  historical schedule. This keeps completed/past once tasks editable for name,
  prompt, retry, session, and precision metadata without requiring an unrelated
  reschedule.
- 2026-06-15: Restored compacted-history reveal behavior in chat. The UI now has
  an explicit show/hide affordance, hidden durable messages can be revealed
  without surfacing compact-command plumbing, and opening a search hit inside a
  compacted range reveals the history so the match can actually be highlighted.
- 2026-06-15: Hardened automation target-session handling for archived
  sessions. Task tools now reject archived explicit/current session targets, and
  runtime execution falls back to main when a previously valid task target has
  since been archived so scheduled output does not disappear into inactive chat.
- 2026-06-15: Bounded provider timeout settings at the settings boundary and
  HTTP-client boundary. Corrupt/imported zero or negative timeouts now recover
  to the safe default, oversized values clamp to the maximum, and the settings UI
  rejects out-of-range edits instead of letting unsafe transport configuration
  reach OkHttp.
- 2026-06-15: Hardened cron schedule parsing for malformed automation inputs.
  Empty list items, repeated step separators, and non-numeric values now fail
  with stable bounded `IllegalArgumentException` messages instead of raw number
  parser text, and tests cover Sunday `7` normalization plus invalid cron forms.
- 2026-06-15: Hardened interval schedule persistence and next-run math. Persisted
  schedule JSON now rejects missing required fields, blank cron zones, and
  zero/negative interval durations with stable errors, while
  `NextRunCalculator` rejects invalid interval durations before division.
- 2026-06-16: Hardened task repository reads against corrupted persisted
  schedule rows. `getTask`, task observation, and due-task queries now skip
  invalid schedule records instead of crashing the task list or scheduler scan,
  with repository tests covering all three read paths.
- 2026-06-16: Hardened manual task-form schedule parsing. The Compose form now
  uses a testable parser with stable user-facing errors for invalid once,
  interval, and cron inputs, shares the safe interval-duration bound with task
  tools, and rejects cron expressions that produce no next run.
- 2026-06-16: Made nullable schedule deserialization explicit for task reads.
  `ScheduleSerializer.fromJsonOrNull` now centralizes invalid/malformed
  schedule JSON handling, and task repository coverage verifies malformed
  persisted schedule blobs are skipped across direct, observed, and due queries.
- 2026-06-16: Hardened event-log repository mapping for forward-compatible or
  corrupted persisted rows. Unknown stored categories now surface as `System`
  and unknown levels as `Warn` instead of crashing health/log observation, with
  repository coverage preserving message/details visibility.
- 2026-06-16: Bounded event-log message and detail text at repository write and
  read boundaries. Oversized provider/tool diagnostics are truncated before
  persistence, and legacy oversized rows are clipped before reaching health UI
  flows, preserving the lightweight bounded-log requirement.
- 2026-06-16: Bounded message repository query limits. Recent-message and search
  queries now return empty results for non-positive limits and cap oversized
  requests before reaching SQLite, while large `getMessagesByIds` lookups are
  chunked to avoid oversized `IN` clauses without dropping requested messages.
- 2026-06-16: Bounded event-log recent-query limits. `observeRecent` now returns
  an empty flow for non-positive limits and caps oversized limits before hitting
  SQLite, closing the remaining bounded-log query gap after event text bounding.
- 2026-06-16: Bounded session search limits. Session title searches now return
  empty results for non-positive limits and cap oversized requests before
  SQLite, aligning session search with bounded message and event-log queries.
- 2026-06-16: Bounded persisted session titles and summaries at repository write
  and read boundaries. Titles are capped before persistence/search projection,
  summaries share the 4,000-character compact-summary budget, and legacy
  oversized rows are clipped before reaching prompt/export/UI flows.
- 2026-06-16: Hardened task retry counters at repository boundaries. Negative
  retry/failure counts from direct callers or corrupted persisted rows now clamp
  to zero before storage or domain mapping, keeping task UI and retry planning in
  non-negative automation state.
- 2026-06-16: Bounded task-run diagnostic text at repository write and read
  boundaries. Oversized automation failure messages and result summaries now
  truncate before persistence, while legacy oversized run rows are clipped before
  reaching task history UI and tool payloads.
- 2026-06-16: Hardened memory repository boundaries. Memory text and source
  message ids are now bounded before persistence and again on legacy reads,
  non-positive search/list limits return empty results, and blank owners expose
  no active-memory data or observed count.
- 2026-06-16: Hardened skill repository boundaries. Imported skill names,
  descriptions, markdown instructions, parse errors, base paths, and eligibility
  reasons are bounded before persistence and on legacy reads, while malformed
  persisted frontmatter/reason JSON now degrades to null/empty data instead of
  crashing skill lists.
- 2026-06-16: Bounded prompt assembly for phone-sized provider requests. System
  prompts now cap enabled skill count/instructions, tool descriptors/aliases,
  and cross-session memory item text, with explicit omitted-item markers so
  imported skills or caller-provided memories cannot inflate prompt context
  without bound.
- 2026-06-16: Bounded AgentRunner skill metadata outside the system prompt.
  Provider-facing `enabledSkills` metadata and persisted "Active skills" message
  suffixes now share the prompt caps for skill count and text lengths, preventing
  oversized imported skill names or instructions from bypassing prompt assembly
  bounds.
- 2026-06-16: Bounded message repository payloads. Chat message content,
  provider metadata, and tool/task reference ids are now capped before
  persistence and again on legacy reads/search projections, keeping oversized
  provider/tool output from inflating UI, exports, prompt history, or search
  results without bound.
- 2026-06-16: Hardened repository search blank-query boundaries. Session and
  message searches now return empty results for blank or whitespace-only queries
  instead of issuing bounded full-table `LIKE %%` scans, matching chat UI
  behavior and keeping accidental direct calls cheap.
- 2026-06-16: Hardened provider endpoint settings text. DataStore now trims and
  bounds provider base URLs and model ids on write/read, while blank remote
  endpoint values fall back to provider defaults so corrupted or imported
  settings cannot reach providers as empty or oversized transport config.
- 2026-06-16: Bounded task repository text fields. Task names, prompts, and
  target-session ids now truncate at repository write/read boundaries, including
  legacy oversized persisted rows, so automation lists, scheduler scans, and
  task payload routing cannot carry unbounded text from direct callers or
  corrupted storage.
- 2026-06-16: Hardened skill filesystem storage segments. Workspace skill scans
  and local skill imports now route session ids and imported skill keys through
  deterministic bounded path segments, preserving exact safe names while keeping
  unsafe or oversized names inside the intended skill roots. The root `skills/`
  ignore rule is anchored so new runtime skill tests remain trackable.
- 2026-06-16: Hardened skill configuration storage. Skill config skill keys,
  config paths, and values are now trimmed, bounded, and blank-safe before
  touching DataStore, with deterministic hashed suffixes for oversized
  identifiers and matching in-memory fixture behavior for manager tests.
- 2026-06-16: Hardened skill secret storage. Skill secret identifiers and values
  are now trimmed, bounded, blank-safe, and written under delimiter-safe v2
  encrypted preference keys, while safe legacy keys remain readable for existing
  installs and test fixtures mirror the production normalization.
- 2026-06-16: Hardened provider secret storage. API keys and OpenAI Codex OAuth
  credentials now trim and cap required/optional fields on write and read,
  blank required OAuth tokens clear corrupted credentials, and secret recovery
  notices include both API-key and OAuth encrypted slots.
- 2026-06-16: Hardened OpenAI Codex OAuth parsing. Token response fields now use
  explicit caps before credential construction, oversized JWT payload segments
  are ignored before decoding, identity fields are bounded before UI display,
  and overflowed `expires_in` values no longer wrap expiry timestamps.
- 2026-06-16: Hardened repository SQL LIKE searches. Session-title and
  message-content search now use bounded escaped literal patterns, so `%`, `_`,
  and backslash queries no longer broaden into accidental wildcard scans while
  normal active-session search behavior is preserved.
- 2026-06-16: Bounded session compaction boundary ids. Summary compaction writes
  now trim, cap, and drop blank boundary ids, legacy reads apply the same bound,
  and boundary maintenance queries no longer surface rows whose persisted
  boundary normalizes to null.
- 2026-06-16: Bounded crash marker diagnostics. Crash thread names,
  exception types, messages, and stack traces now normalize on write and again
  on legacy reads, with blank legacy text falling back to safe display values so
  health diagnostics exports cannot be inflated by untrusted crash payloads.
- 2026-06-16: Bounded direct chat share-text payloads. Full session exports
  and share-as-file paths still preserve the transcript, while Android
  Sharesheet `EXTRA_TEXT` output now caps oversized sessions with a visible
  truncation notice that points users to file export for the complete history.
- 2026-06-16: Bounded health diagnostics reports. Copied/exported diagnostics
  now cap arbitrary UI-state fields, tool/kind lists, event counts, and event
  details at the formatter boundary while including the already-bounded crash
  stack trace so bug reports stay useful without unbounded payload growth.
- 2026-06-16: Bounded live assistant streaming previews. Provider text deltas
  now cap the transient Compose preview during a running turn with a visible
  truncation notice, preventing oversized streams from growing UI state before
  the final assistant message reaches repository persistence bounds.
- 2026-06-16: Bounded AgentRunner streaming text boundaries. Runtime streamed
  response accumulation, fallback preview deltas, returned assistant messages,
  and memory-capture inputs now share the message-content cap before repository
  persistence, while emitted live-preview deltas use the bounded preview notice.
- 2026-06-16: Bounded chat draft input before Compose state growth. Pasted or
  programmatic draft text now caps at the message-content budget and shows a
  truncation notice, keeping the phone composer responsive before the message
  reaches runtime and repository persistence bounds.
- 2026-06-16: Bounded chat history search input before Compose state growth.
  Pasted or programmatic search text now caps at the same SQLite LIKE query
  budget used by repository search and shows a truncation notice, preventing
  oversized dialog input from bypassing bounded search persistence safeguards.
- 2026-06-16: Bounded manual task-form text before Compose state growth. Task
  names and prompts now share repository text budgets in the scheduler screen,
  schedule text fields use small explicit UI budgets, and truncation notices are
  cleared without overwriting unrelated validation errors.
- 2026-06-16: Bounded provider settings drafts before Compose state growth.
  Base URLs, model ids, timeout text, and API-key drafts now cap in
  `SettingsViewModel`, protecting both Settings and onboarding provider forms
  before DataStore or secret-store persistence bounds are reached.
- 2026-06-16: Bounded skill configuration drafts before Compose state growth.
  Secret and config values entered in the skill configuration dialog now share
  the existing secret/config store budgets and surface a truncation notice before
  oversized text can inflate dialog state.
- 2026-06-16: Bounded chat session rename drafts before Compose state growth.
  The chat screen now caps local session-title edits at the repository title
  budget and displays a truncation notice, closing the remaining unbounded local
  chat text field before title persistence.
- 2026-06-16: Bounded skill import status summaries. Import completion UI now
  reports total imported/replaced counts, lists only a small capped sample of
  skill names, and includes omitted-name counts so large ZIP imports cannot
  create unbounded status text.
- 2026-06-16: Bounded skill UI status and dialog messages. Skill recovery,
  import/load failures, save failures, and saved-configuration notices now pass
  through a shared UI-message cap so thrown exceptions or oversized skill names
  cannot inflate the Skills screen state.
- 2026-06-16: Bounded provider settings status messages. OpenAI Codex OAuth
  progress/failure text, validation failures, generic exceptions, recovered-key
  notices, and device-code instructions now pass through a shared status cap
  before reaching Settings or onboarding UI state.
- 2026-06-16: Bounded chat UI error and notice messages. External-action
  completion/failure text, turn-failure display text, and export/share exception
  messages now pass through a shared chat UI-message cap before reaching Compose
  state while event logging still preserves repository-bounded diagnostics.
- 2026-06-16: Bounded task action messages. Task creation, enable/disable,
  run-now, not-found, and delete notices now pass through a shared scheduler
  screen action-message cap before reaching Compose state, with deterministic
  coverage for oversized and blank action text.
- 2026-06-16: Bounded health diagnostics notices. Copy/export/share diagnostics
  success and failure text now passes through a shared health notice cap before
  reaching Compose state, preventing oversized platform exception messages from
  inflating the Health screen.
- 2026-06-16: Bounded tool result summaries at the registry boundary. Handler
  success/failure summaries and thrown exception messages now cap before they
  become tool-result chat messages, model tool context, or structured exception
  payloads, keeping typed tools concise even when handlers misbehave.
- 2026-06-16: Bounded tool registry metadata. Unknown requested tool names,
  tool log context ids, provided argument-name lists, permission labels, and
  result error codes now cap before result payload or event-detail serialization,
  so malformed model tool calls cannot inflate tool failure metadata in memory.
- 2026-06-16: Stabilized the task usage-summary ViewModel test by explicitly
  cancelling remaining Turbine emissions after the asserted provider-usage
  state, preventing valid late state updates from failing the full unit suite.
- 2026-06-16: Bounded health UI list state. Tool registry names and scheduler
  capability kinds now cap item text and list length before entering
  `HealthUiState`, with omitted-count markers so oversized registries cannot
  inflate the Health screen while diagnostics exports retain their own caps.
- 2026-06-16: Bounded health UI text fields. Provider status/issues,
  automation result details, worker stop reasons, crash summaries, and crash
  stack traces now cap at the Health ViewModel boundary before reaching Compose
  state, while diagnostics-report formatting keeps its own export-specific caps.
