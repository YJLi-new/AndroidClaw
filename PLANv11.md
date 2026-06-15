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
