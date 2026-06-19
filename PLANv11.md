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

The active goal is to make the current Android-native single-APK app more useful,
legible, and verifiable with each cycle. As of 2026-06-16, the short-term focus
is feature-first: prefer small user-visible or agent-usable contract features,
while still keeping the normal validation gate green.

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

- 2026-06-20: Continued the review-report completion pass with the remaining medium-term fixes that were previously only deferred. Chat export/share now uses a streaming writer and paged Room reads for TXT/MD/JSON file output with explicit truncation metadata instead of building whole exports in memory. Message and memory search now have Room token-index tables, repository-side token maintenance on create/update/copy/restore, and bounded startup repair for legacy rows missing tokens while preserving LIKE fallbacks for literal searches. Local/workspace imported skills now require an explicit enable confirmation that displays untrusted source, dispatch target, and active tool-dispatch scope. Added a checked-in startup baseline profile seed for startup/chat/settings/tasks/skills paths and a security-focused CI job for dependency verification, redaction/origin/endpoint tests, production lint, and non-blocking test-source lint audit. Confirmed the Google Drive mapping for this repo is `mode=tree`, so Drive receives tracked files in the same nested folder layout as GitHub rather than only a `.tar.gz` archive. Ktlint, focused token-index tests, and the full offline fast suite passed.
- 2026-06-20: Implemented the 2026-06-19 review-report hardening pass across
  P1/P2/P3 focus areas. Added registry-level tool origin/risk gating for model,
  scheduled-model, slash, and internal origins; provider prompts now receive
  only origin-allowed descriptors, and execution enforces the same policy. Tool
  arguments now carry inferred type/sensitive metadata, schema output includes
  typed/enum/sensitive fields, and explicit high-priority type/enum validation
  can fail bad inputs before handler execution without breaking legacy JSON/object
  built-in tools. Provider and direct tool-call transcripts are redacted before
  persistence, and chat export/share sanitizes legacy raw tool-call JSON before
  leaving the app. Chat export payload construction, message fetching, export
  writes, share-file writes, and Android encrypted provider/skill secret-store
  operations now run behind IO dispatch boundaries. Provider base URLs now have
  shared policy validation for malformed URLs, unsupported schemes, embedded
  credentials, query/fragment suffixes, and non-loopback HTTP warnings. The
  scheduler no longer treats every generic exception as `WORK_INTERRUPTED`;
  cancellation propagates, and unexpected failures are classified into IO,
  invalid argument, invalid state, and runtime buckets. SKILL.md parsing now
  rejects oversized documents/frontmatter/bodies and excessive frontmatter
  fields, ZIP skill import rejects excessive entries and per-entry sizes, and
  local/workspace skills are labeled as untrusted imports in the UI. Memory
  search now uses a database-side text candidate query before bounded in-memory
  scoring, improving recall beyond the recent scan window without a heavy FTS
  migration. Reproducibility changes made `mavenLocal()` opt-in, enabled strict
  Gradle dependency verification with checked-in metadata, documented build
  prerequisites, and added an opt-in test-source lint probe property. Focused
  regression suites, ktlint, the full offline fast suite, and QA/release
  packaging passed; connected testing is blocked in this environment because the
  available AVD exits without KVM permission. Full physical namespace splitting
  of `BuiltInTools.kt` remains a larger follow-up because the current file still
  contains many private cross-namespace helpers; this pass began reducing policy sprawl by
  moving shared provider endpoint policy out to the data/provider boundary.
- 2026-06-18: Added `tasks.duplicate.example` /
  `task.duplicate.example` / `tasks.copy.example` / `task.copy.example` plus
  automation duplicate aliases as an Automation Contract usability feature.
  Agents can now request safe disabled/enabled duplicate argument examples
  without resolving a source task, copying automations, scheduling WorkManager
  work, mutating task state, or exposing source prompt bodies, provider
  metadata, secrets, or run history. The payload includes source-field copy
  expectations, enabled-copy scheduling implications, suggested follow-up
  tools, optional markdown, and explicit example-only /
  executes-task-duplicate=false / duplicates-automation=false /
  creates-automation=false / schedules-work=false / mutates-tasks=false flags.
  Focused `BuiltInToolsTest`, ktlint, and the full offline fast loop passed.
- 2026-06-18: Added `tasks.update.example` /
  `task.update.example` / `tasks.patch.example` / `task.patch.example` plus
  automation update aliases as an Automation Contract usability feature. Agents
  can now request safe metadata, once, interval, or cron patch examples without
  updating tasks, scheduling WorkManager work, mutating automation state, or
  exposing user prompt bodies, provider metadata, secrets, or run history. The
  payload includes patch fields, example and preview arguments where applicable,
  supported patch/schedule kinds, execution modes, target-session aliases,
  suggested follow-up tools, optional markdown, and explicit example-only /
  executes-task-update=false / updates-automation=false / schedules-work=false
  / mutates-tasks=false flags. Focused `BuiltInToolsTest`, ktlint, and the full
  offline fast loop passed.
- 2026-06-17: Added `tasks.create.example` /
  `task.create.example` / `tasks.schedule.example` /
  `task.schedule.example` plus automation example aliases as an Automation
  Contract usability feature. Agents can now request safe once / interval /
  cron creation examples without creating tasks, scheduling WorkManager work,
  mutating automation state, or exposing user prompt bodies, provider metadata,
  secrets, or run history. The payload includes example and preview arguments,
  supported schedule kinds, execution modes, target-session aliases, suggested
  follow-up tools, optional markdown, and explicit example-only /
  executes-task-creation=false / creates-automation=false / schedules-work=false
  / mutates-tasks=false flags. Focused `BuiltInToolsTest`, ktlint, and the full
  offline fast loop passed.
- 2026-06-17: Added `skills.setup.matrix` /
  `skill.setup.matrix` / `skills.setup.all` / `skill.setup.all` plus
  readiness/onboarding aliases as a Skill Contract readiness-inventory feature.
  Agents can now inspect a bounded all-skill setup matrix with READY /
  DISABLED / NOT_ELIGIBLE / NEEDS_CONFIG / NEEDS_SECRETS /
  NEEDS_CONFIG_AND_SECRETS status, ready and needs-setup counts, optional
  disabled-skill filtering, optional requirement details, optional markdown,
  ready-skill ids, suggested follow-up tools, and explicit read-only /
  executes-setup=false / writes-config=false / writes-secret=false /
  instruction/config/secret/base-dir/raw-frontmatter omission flags. Focused
  `BuiltInToolsTest`, ktlint, and the full offline fast loop passed.
- 2026-06-17: Added `skills.setup` /
  `skill.setup` / `skills.quickstart` / `skill.quickstart` plus readiness
  aliases as a Skill Contract setup-guide feature. Agents can now inspect one
  skill's enablement, resolution, eligibility, command dispatch, declared
  secret/config readiness, recovery notices, and missing setup requirements
  without returning SKILL.md instructions, config values, secret values, base
  directories, or raw frontmatter; the payload includes READY / DISABLED /
  NOT_ELIGIBLE / NEEDS_CONFIG / NEEDS_SECRETS /
  NEEDS_CONFIG_AND_SECRETS status, suggested follow-up tools, optional
  markdown, and explicit read-only / executes-setup=false / writes-config=false
  / writes-secret=false flags. Focused `BuiltInToolsTest`, ktlint, and the
  full offline fast loop passed.
- 2026-06-17: Added `providers.setup.matrix` /
  `provider.setup.matrix` / `providers.setup.all` / `provider.setup.all` plus
  readiness/onboarding aliases as a Provider/OAuth readiness-inventory
  feature. Agents can now inspect all providers in one bounded read-only matrix
  with per-provider READY / NEEDS_AUTH / NEEDS_ENDPOINT /
  NEEDS_AUTH_AND_ENDPOINT status, ready and needs-setup counts, optional
  requirement details, optional markdown, ready-provider ids, suggested
  follow-up tools, and explicit executes-setup=false / mutates-settings=false /
  writes-credential=false / secret/API-key/OAuth-token omission flags. Focused
  `BuiltInToolsTest`, ktlint, and the full offline fast loop passed.
- 2026-06-17: Added `providers.setup` /
  `provider.setup` / `providers.quickstart` / `provider.quickstart` plus
  readiness aliases as a Provider/OAuth setup-guide feature. Agents can now
  inspect one provider's combined auth, endpoint, and selection readiness
  without mutating settings or credentials, receive READY / NEEDS_AUTH /
  NEEDS_ENDPOINT / NEEDS_AUTH_AND_ENDPOINT status, bounded setup requirements,
  suggested follow-up tools, optional markdown, and explicit read-only /
  executes-setup=false / mutates-settings=false / writes-credential=false /
  secret/API-key/OAuth-token omission flags. Focused `BuiltInToolsTest`,
  ktlint, and the full offline fast loop passed.
- 2026-06-17: Added `providers.auth.example` /
  `provider.auth.example` / `providers.credentials.example` /
  `provider.credentials.example` plus login-example aliases as a Provider/OAuth
  usability feature. Agents can now request non-secret credential setup
  guidance for API-key, OpenAI Codex OAuth, and local/offline providers without
  mutating credentials, see current non-secret auth status, safe status/clear
  example arguments, suggested follow-up tools, optional markdown, and explicit
  example-only / executes-authentication=false / writes-credential=false /
  secret/API-key/OAuth-token omission flags. Focused `BuiltInToolsTest`,
  ktlint, and the full offline fast loop passed.
- 2026-06-17: Added `providers.configure.example` /
  `provider.configure.example` / `providers.endpoint.example` plus config
  example aliases as a Provider/OAuth usability feature. Agents can now prepare
  non-secret endpoint configuration examples for remote providers without
  mutating settings, compare current and default endpoints, see suggested
  follow-up tools for credentials/catalog, get local-provider no-op guidance,
  optional markdown, and explicit example-only / executes-configuration=false /
  secret/API-key/OAuth-token omission flags. Focused `BuiltInToolsTest`,
  ktlint, and the full offline fast loop passed.
- 2026-06-17: Added `skills.command.example` /
  `skill.command.example` / `skills.slash.example` / `skill.slash.example`
  plus command-sample aliases as a Skill Contract usability feature. Agents can
  now request a safe slash-command invocation skeleton by command name, slash
  command, skill id, skill key, or display name, see model/tool dispatch
  readiness, duplicate command counts, tool-dispatch targets with a suggested
  `tools.example` follow-up, optional markdown, and explicit example-only /
  executes-command=false / instruction-body and secret omission flags without
  invoking the skill command. Focused `BuiltInToolsTest`, ktlint, and the full
  offline fast loop passed.
- 2026-06-17: Added `tools.example` /
  `tool.example` / `tools.invoke.example` / `tool.invoke.example` plus sample
  aliases as a Tool Contract usability feature. Agents can now ask for a safe
  invocation skeleton for any typed tool by canonical name or alias, choose
  required-only or optional argument coverage, receive generated example JSON
  and optional markdown, and get explicit example-only / executes-tool=false /
  secret-omission flags so large tool surfaces are easier to use without
  accidentally running the target. Focused `BuiltInToolsTest`, ktlint, and the
  full offline fast loop passed.
- 2026-06-17: Added `runtime.portability.audit` /
  `runtime.backup.audit` / `runtime.restore.audit` plus AndroidClaw/system
  aliases as a Runtime Contract restore-readiness feature. Agents can now pass
  a set of exported component payloads and receive a no-body audit of
  recognized, missing, duplicate, and unknown export formats, complete-backup
  and restore-ready booleans, recommended missing exports, recognized restore
  order, optional markdown, and explicit secret/component-payload omission
  flags before attempting confirmed imports. Focused `BuiltInToolsTest`,
  ktlint, and the full offline fast loop passed.
- 2026-06-17: Promoted event diagnostics into the `runtime.portability` /
  `runtime.backup.plan` / `runtime.restore.plan` playbook as a first-class
  portable component. The playbook now reports nine components, includes
  `events.export` after memory export, includes `events.import` as the final
  restore step, exposes event diagnostic format ids and restore metadata, and
  records event-log counts plus markdown guidance while still omitting raw
  event details by default. Focused `BuiltInToolsTest`, ktlint, and the full
  offline fast loop passed.
- 2026-06-17: Added `events.import` /
  `event.import` / `logs.import` / `diagnostics.import` plus restore aliases
  as the companion diagnostics portability feature for `events.export`. Agents
  can now dry-run or confirm import of bounded exported diagnostics, skip
  invalid event entries, import details only when requested, write local log
  entries with new ids/timestamps, and receive explicit source-id and
  source-timestamp non-preservation plus detail, secret, provider-metadata, and
  message-body omission flags. Focused `BuiltInToolsTest`, ktlint, and the full
  offline fast loop passed.
- 2026-06-17: Added `events.export` /
  `event.export` / `logs.export` / `diagnostics.export` plus backup aliases
  as a diagnostics portability feature. Agents can now export bounded recent
  event-log diagnostics with stable `androidclaw.events.export.v1`
  format/version metadata, scan and result limits, category/level filters,
  aggregate category/level counts, optional bounded details, optional
  markdown, and explicit secret, API-key, OAuth-token, provider-metadata, and
  message-body omission flags by default. Focused `BuiltInToolsTest`, ktlint,
  and the full offline fast loop passed.
- 2026-06-17: Added `runtime.portability` /
  `runtime.backup.plan` / `runtime.restore.plan` / `androidclaw.portability`
  plus system portability aliases as a Runtime Contract coordination feature.
  Agents can now inspect a safe backup/restore playbook across runtime,
  providers, skills, tools, sessions, messages, automations, and memory with
  recommended export/restore order, supported format ids and tool names,
  current aggregate counts, optional markdown, dry-run/confirmation guidance,
  and explicit secret, OAuth token, message body, prompt body, skill
  instruction, memory text, event detail, component-payload, and desktop
  runtime omission flags. Focused `BuiltInToolsTest`, ktlint, and the full
  offline fast loop passed.
- 2026-06-17: Added `sessions.import` /
  `session.import` / `sessions.restore` / `session.restore` plus chat-session
  import aliases as the companion Session Contract portability feature for
  `sessions.export`. Agents can now dry-run or confirm import of bounded
  session metadata shells, skip archived source sessions when requested,
  preserve archived state when requested, import summaries without preserving
  stale compaction boundaries, keep imported source-main sessions as normal
  sessions to avoid duplicate main rows, and receive explicit transcript-body,
  provider-metadata, source-created/updated time, source-main, and compaction
  boundary non-preservation flags. Focused `BuiltInToolsTest`, ktlint, and the
  full offline fast loop passed.
- 2026-06-17: Added `sessions.export` /
  `session.export` / `sessions.backup` / `session.backup` plus chat-session
  export aliases as a Session Contract portability feature. Agents can now
  export bounded session metadata with stable export format/version, optional
  single-session focus, optional archived-session inclusion, optional summary
  text, message/count/role statistics, optional markdown, and explicit
  transcript-body and provider-metadata omission flags. Focused
  `BuiltInToolsTest`, ktlint, and the full offline fast loop passed.
- 2026-06-17: Added `runtime.export` /
  `runtime.manifest` / `androidclaw.export` / `androidclaw.manifest` plus
  system manifest aliases as a versioned Runtime Contract capability feature.
  Agents can now export a stable `androidclaw.runtime.export.v1` manifest with
  app-host invariants, contract order, session/tool/skill/automation stats,
  provider/auth status without credential values, scheduler capability
  metadata, memory/event counts, optional markdown, and explicit message body,
  prompt body, skill instruction, memory text, event detail, API-key, OAuth
  token, tool schema, and desktop-runtime-state omission flags. Focused
  `BuiltInToolsTest`, ktlint, and the full offline fast loop passed.
- 2026-06-17: Added `tools.export` /
  `tool.export` / `tools.backup` / `tool.backup` plus catalog-export aliases
  as a Tool Contract portability feature. Agents can now export bounded typed
  native tool descriptors with stable export format/version, optional
  single-tool focus, optional namespace and availability filtering, optional
  input schemas, optional markdown, aggregate stats, and explicit secret-value,
  execution-result, and runtime-state omission flags. Focused
  `BuiltInToolsTest`, ktlint, and the full offline fast loop passed.
- 2026-06-17: Added `skills.import` /
  `skill.import` / `skills.restore` / `skill.restore` plus package-import
  aliases as the companion Skill Contract portability feature for
  `skills.export`. Agents can now dry-run or confirm import of bounded
  exported skill definitions into local skill storage, skip disabled source
  entries when requested, keep imported skills disabled by default unless
  `enableImported=true`, optionally restore non-secret config values, preserve
  secret omission, and receive explicit raw-frontmatter/base-dir omission
  flags. Focused `BuiltInToolsTest`, ktlint, and the full offline fast loop
  passed.
- 2026-06-17: Added `skills.export` /
  `skill.export` / `skills.backup` / `skill.backup` plus package-export
  aliases as a Skill Contract portability feature. Agents can now export
  bounded non-secret skill definitions with stable export format/version,
  optional single-skill focus, optional disabled-skill filtering, bounded
  SKILL.md instruction bodies, optional non-secret config values, optional
  markdown, and explicit secret-value/base-dir/raw-frontmatter omission flags.
  Focused `BuiltInToolsTest`, ktlint, and the full offline fast loop passed.
- 2026-06-17: Added `providers.import` /
  `provider.import` / `providers.restore` / `provider.restore` plus settings
  import aliases as the companion Provider/OAuth portability feature for
  `providers.export`. Agents can now dry-run or confirm import of bounded
  non-secret provider endpoint settings, optionally restore exported current
  provider selection, skip local/offline provider entries, keep credentials and
  auth state untouched, and receive explicit API-key/OAuth-token/credential
  omission flags. Focused `BuiltInToolsTest`, ktlint, and the full offline
  fast loop passed.
- 2026-06-17: Added `providers.export` /
  `provider.export` / `providers.backup` / `provider.backup` plus settings
  export aliases as a Provider/OAuth portability feature. Agents can now export
  the selected provider and bounded non-secret endpoint settings with stable
  export format/version, optional single-provider focus, optional endpoint
  default omission, optional markdown, accurate custom endpoint/model/timeout
  flags, and explicit API-key/OAuth-token/credential/auth-state omission flags.
  Focused `BuiltInToolsTest`, ktlint, and the full offline fast loop passed.
- 2026-06-17: Added `tasks.import` /
  `task.import` / `automations.import` / `automation.import` plus restore
  aliases as the companion Automation Contract portability feature for
  `tasks.export`. Agents can now dry-run or confirm import of a bounded
  `tasks` array or `export.tasks` payload, parse once/interval/cron schedules,
  skip disabled entries when requested, keep restored automations disabled by
  default unless `enableImported=true`, preserve target-session ids only when
  explicitly requested and locally active, hide prompt bodies from result
  payloads by default, and receive explicit run-history/provider omission
  flags. Focused `BuiltInToolsTest`, ktlint, and the full offline fast loop
  passed.
- 2026-06-17: Added `tasks.export` /
  `task.export` / `automations.export` / `automation.export` plus backup
  aliases as an Automation Contract portability feature. Agents can now export
  bounded automation definitions with stable export format/version, optional
  disabled-task inclusion, full prompt omission by default, optional full prompt
  inclusion for explicit backups, target-session metadata, schedule and
  execution-mode stats, optional markdown, and explicit run-history/provider
  omission flags. Focused `BuiltInToolsTest`, ktlint, and the full offline fast
  loop passed.
- 2026-06-17: Added `messages.import` /
  `message.import` / `transcript.import` / `chat.import` plus
  session-message import alias as the companion Session Contract portability
  feature for `messages.export`. Agents can now dry-run or confirm import of a
  bounded `messages` array or `export.messages` payload into a new or existing
  session, optionally import summary text without preserving stale compaction
  boundaries, omit message bodies from result payloads, and receive explicit
  source-created-at/source-id/provider-metadata non-preservation flags. Focused
  `BuiltInToolsTest`, ktlint, and the full offline fast loop passed.
- 2026-06-17: Added `messages.export` /
  `message.export` / `transcript.export` / `chat.export` plus
  session-message export alias as a Session Contract portability feature.
  Agents can now export a bounded start or recent transcript window with a
  stable export format/version, optional body and summary omission, role stats,
  optional markdown, compaction metadata, and explicit provider-metadata
  omission flags. Focused `BuiltInToolsTest`, ktlint, and the full offline fast
  loop passed.
- 2026-06-17: Added `memory.import` /
  `memories.import` / `memory.ingest` / `memories.ingest` plus export-restore
  aliases as the companion Memory Contract portability feature for
  `memory.export`. Agents can now import a bounded `memories` array or
  `export.memories` payload with explicit `confirm=CONFIRM`, dry-run preview,
  optional deleted-entry reactivation, source/provenance preservation,
  source-type normalization, and explicit owner-id/message-body/provider
  omission flags. Focused `BuiltInToolsTest`, ktlint, and the full offline fast
  loop passed.
- 2026-06-17: Added `memory.export` /
  `memories.export` / `memory.backup` / `memories.backup` plus dump aliases
  as a Memory Contract portability feature. Agents can now prepare a bounded
  local memory export with a stable export format/version, optional deleted
  memory inclusion, optional text omission, source/provenance metadata,
  optional markdown, and explicit owner-id/message-body/provider-metadata
  omission flags. Focused `BuiltInToolsTest`, ktlint, and the full offline fast
  loop passed.
- 2026-06-17: Added `memory.timeline` /
  `memories.timeline` / `memory.activity` / `memories.activity` plus history
  aliases as a Memory Contract lifecycle feature. Agents can now inspect a
  bounded local-memory timeline ordered by update/delete activity, optionally
  include deleted memories, omit memory text from payloads and markdown, and
  receive explicit owner-id/message-body/provider-metadata omission flags.
  Focused `BuiltInToolsTest`, ktlint, and the full offline fast loop passed.
- 2026-06-17: Added `tasks.timeline` /
  `task.timeline` / `tasks.calendar` / `task.calendar` plus automation
  timeline/calendar aliases as a global Automation Contract planning feature.
  Agents can now inspect a bounded cross-task schedule timeline with global
  and per-task occurrence caps, optional lower/upper ISO bounds, optional
  disabled-task inclusion, target-session metadata, optional markdown, and
  explicit prompt-body omission flags without mutating tasks or enqueueing
  work. Focused `BuiltInToolsTest`, ktlint, and the full offline fast loop
  passed.
- 2026-06-17: Added `providers.auth.clear` /
  `provider.auth.clear` / `providers.credentials.clear` /
  `provider.credentials.clear` plus logout/sign-out aliases as a confirmed
  Provider/OAuth credential-management feature. Agents can now clear API-key or
  OpenAI Codex OAuth credential slots through typed tools with
  `confirm=CONFIRM`, receive before/after non-secret auth status, unsupported
  auth-type and missing-secret-store failures, and explicit secret/OAuth-token
  omission flags. Focused `BuiltInToolsTest`, ktlint, and the full offline
  fast loop passed.
- 2026-06-17: Added `providers.catalog` /
  `provider.catalog` / `providers.models` / `provider.models` plus endpoint
  aliases as a Provider/OAuth catalog feature. Agents can now inspect bounded
  provider default/current base URLs, model ids, timeout settings, selection
  state, remote/auth metadata, customization flags, optional markdown, and
  explicit secret/OAuth-token omission flags. Focused `BuiltInToolsTest`,
  ktlint, and the full offline fast loop passed.
- 2026-06-17: Added `skills.commands` /
  `skill.commands` / `skills.slash` / `skill.slash` plus command-index aliases
  as a Skill Contract command inventory feature. Agents can now inspect
  bounded slash-command dispatch metadata, invocability, model/tool dispatch
  readiness, duplicate command names, optional disabled/non-user-invocable
  entries, optional markdown, and explicit SKILL.md instruction/secret-value
  omission flags. Focused `BuiltInToolsTest`, ktlint, and the full offline
  fast loop passed.
- 2026-06-17: Added `tasks.agenda` /
  `task.agenda` / `automations.agenda` / `automation.agenda` plus handoff
  aliases as a compact Automation Contract agenda feature. Agents can now
  prepare bounded due and future automation sections with aggregate scheduler
  stats, target-session state, scheduling precision metadata, optional prompt
  snippets, optional markdown, and explicit prompt-body omission flags. Focused
  `BuiltInToolsTest`, ktlint, and the full offline fast loop passed.
- 2026-06-17: Added `memory.provenance` /
  `memories.provenance` / `memory.trace` / `memory.context` aliases plus
  `/memory provenance <id>` command dispatch as a Memory Contract context
  feature. Agents can now inspect one memory's source session and source
  message references with resolved/missing counts, optional memory text,
  optional source-message snippets, optional markdown, and explicit owner-id,
  full-message-body, and provider-metadata omission flags. Focused
  `BuiltInToolsTest`, ktlint, and the full offline fast loop passed.
- 2026-06-17: Added `messages.handoff` /
  `message.handoff` / `messages.snapshot` / `message.snapshot` plus transcript
  aliases as a compact transcript context-transfer tool. Agents can now hand
  off bounded start/recent transcript windows with role stats, optional
  snippets, optional markdown, chronological ordering, and explicit
  full-message-body/provider-metadata omission flags. Focused
  `BuiltInToolsTest`, ktlint, and the full offline fast loop passed.
- 2026-06-17: Added `events.handoff` /
  `event.handoff` / `logs.handoff` / `log.handoff` plus snapshot aliases as a
  compact event-log context-transfer feature. Agents can now hand off bounded
  recent runtime diagnostics with optional category/level filters, aggregate
  category/level counts, optional markdown, recent-first ordering, and explicit
  raw-details omission flags. Focused `BuiltInToolsTest`, ktlint, and the full
  offline fast loop passed.
- 2026-06-17: Added `events.doctor` /
  `event.doctor` / `logs.doctor` / `log.doctor` plus health/check aliases as
  a dedicated local event-log diagnostics tool. Agents can now scan recent
  runtime events by optional category for warning/error events, aggregate
  category/level counts, bounded event checks, actionable category-specific
  remediation, optional markdown, and explicit raw-details omission flags.
  Focused `BuiltInToolsTest`, ktlint, and the full offline fast loop passed.
- 2026-06-17: Added `messages.doctor` /
  `message.doctor` / `messages.health` / `message.health` plus transcript
  aliases as a dedicated transcript diagnostics tool. Agents can now inspect
  the active or selected session for archived transcript usage, empty or
  role-incomplete transcripts, large unsummarized transcripts, blank messages,
  max-length stored messages, and tool-call/tool-result messages missing
  `toolCallId` with OK/WARN/ERROR status, bounded recent-message checks,
  optional markdown, and explicit message-body/provider-metadata omission
  flags. Focused `BuiltInToolsTest`, ktlint, and the full offline fast loop
  passed.
- 2026-06-17: Added `memory.doctor` /
  `memories.doctor` / `memory.health` / `memories.health` plus check aliases
  and `/memory doctor` command dispatch as a dedicated Memory Contract
  diagnostics tool. Agents can now inspect local memory enablement, aggregate
  counts, bounded recent-memory metadata, disabled-with-stored-memory state,
  unknown source types, automatic memories without provenance, and max-length
  memory records with OK/WARN/ERROR status, optional markdown, and explicit
  owner-id/text omission flags. Focused `BuiltInToolsTest`, ktlint, and the
  full offline fast loop passed.
- 2026-06-17: Added `tools.doctor` /
  `tool.doctor` / `tools.health` / `tool.health` plus diagnostics aliases as
  a dedicated Tool Contract diagnostics tool. Agents can now scan all typed
  tools or one namespace for unavailable, permission-gated, foreground-only,
  disabled, empty-namespace, and no-available-tool conditions with OK/WARN/ERROR
  status, bounded issue/check payloads, optional markdown, aggregate registry
  stats, and explicit input-schema omission. Focused `BuiltInToolsTest`,
  ktlint, and the full offline fast loop passed.
- 2026-06-17: Added `sessions.doctor` /
  `session.doctor` / `sessions.check` / `session.check` plus chat aliases as a
  dedicated Session Contract diagnostics tool. Agents can now inspect all
  sessions or a named session for missing/duplicate main-session invariants,
  archived main sessions, blank titles, compacted sessions without summaries,
  missing or cross-session compaction boundaries, and large unsummarized
  transcripts with OK/WARN/ERROR status, bounded issue/check payloads,
  optional markdown, and transcript/summary body omission flags. Focused
  `BuiltInToolsTest`, ktlint, and the full offline fast loop passed.
- 2026-06-17: Added `providers.doctor` /
  `provider.doctor` / `providers.check` / `provider.check` plus health aliases
  as a dedicated Provider/OAuth diagnostics tool. Agents can now inspect the
  selected provider, a named provider, or all providers for missing API keys,
  unknown secret-store state, expired OpenAI Codex OAuth credentials, missing
  OAuth profile metadata, invalid base URLs, blank model ids, and invalid
  timeouts with OK/WARN/ERROR status, bounded issue payloads, optional
  markdown, and explicit secret/token omission flags. Focused
  `BuiltInToolsTest`, ktlint, and the full offline fast loop passed.
- 2026-06-17: Added `tasks.doctor` /
  `task.doctor` / `tasks.check` / `task.check` plus automation aliases as a
  dedicated Automation Contract diagnostics tool. Agents can now scan enabled
  or all automations for due backlog, disabled schedules, enabled tasks with no
  next run, exhausted retry budgets, empty prompts, archived/missing target
  sessions, and precise-scheduling degradation with OK/WARN/ERROR status,
  bounded issue payloads, optional markdown, and prompt-body omission flags.
  Focused `BuiltInToolsTest`, ktlint, and the full offline fast loop passed.
- 2026-06-17: Added `skills.doctor` /
  `skill.doctor` / `skills.check` / `skill.check` as a dedicated Skill
  Contract diagnostics tool. Agents can now scan enabled or all skills for
  parse/frontmatter failures, disabled invocability, shadowed definitions,
  eligibility failures, missing command tools, missing secrets/config, and
  inconsistent model/tool dispatch settings with OK/WARN/ERROR status,
  bounded issue payloads, optional markdown, and explicit instruction/secret
  omission flags. Focused `BuiltInToolsTest`, ktlint, and the full offline
  fast loop passed.
- 2026-06-17: Added `runtime.doctor` /
  `health.doctor` / `runtime.check` / `androidclaw.doctor` plus system alias
  as a read-only readiness diagnostic. Agents can now inspect provider auth and
  endpoint readiness, main-session invariants, due automation backlog, memory
  repository wiring, skill parse/eligibility issues, tool availability, and
  scheduler/event-log wiring with OK/WARN/ERROR status and actionable
  remediation messages without mutating state or exposing secrets. Focused
  `BuiltInToolsTest`, ktlint, and the full offline fast loop passed.
- 2026-06-17: Added `runtime.handoff` /
  `runtime.snapshot` / `androidclaw.handoff` / `androidclaw.snapshot` plus
  system aliases as a top-level context-transfer feature. Agents can now
  prepare a compact cross-contract runtime snapshot with selected provider
  auth status, session/task/memory/skill/tool/event counts, bounded recent
  session activity, bounded upcoming automations, optional markdown, and
  explicit heavy-content/secret omission flags. Focused `BuiltInToolsTest`,
  ktlint, and the full offline fast loop passed.
- 2026-06-17: Added `providers.handoff` /
  `provider.handoff` / `providers.snapshot` / `provider.snapshot` as a
  Provider/OAuth context-transfer feature. Agents can now prepare a compact
  provider inventory handoff with selected-provider metadata, non-secret auth
  status, endpoint customization details, optional provider filtering, optional
  markdown, and explicit secret/token omission flags. Focused
  `BuiltInToolsTest`, ktlint, and the full offline fast loop passed.
- 2026-06-17: Added `tools.handoff` / `tool.handoff` /
  `tools.snapshot` / `tool.snapshot` as a Tool Contract context-transfer
  feature. Agents can now prepare a compact bounded registry handoff with
  aggregate stats, namespace and availability filters, argument and permission
  metadata, optional markdown, and no input schemas in the included tool
  entries. Focused `BuiltInToolsTest`, ktlint, and the full offline fast loop
  passed.
- 2026-06-17: Added `skills.handoff` / `skill.handoff` /
  `skills.snapshot` / `skill.snapshot` as a Skill Contract context-transfer
  tool. Agents can now prepare a compact bounded skill inventory handoff with
  aggregate stats, enablement and eligibility metadata, optional disabled-skill
  filtering, optional markdown, and no full `SKILL.md` instruction bodies in
  the payload. Focused `BuiltInToolsTest`, ktlint, and the full offline fast
  loop passed.
- 2026-06-17: Added `memory.handoff` /
  `memories.handoff` / `memory.snapshot` / `memories.snapshot` as a Memory
  Contract context-transfer tool. Agents can now prepare a compact local-memory
  handoff with bounded active memory payloads, aggregate source-type stats,
  optional markdown, and no install owner id exposure; `/memory handoff` also
  supports a bounded count argument. Focused `BuiltInToolsTest`, ktlint, and
  the full offline fast loop passed.
- 2026-06-17: Added `tasks.handoff` / `task.handoff` /
  `tasks.snapshot` / `task.snapshot` plus automation aliases as an Automation
  Contract context-transfer tool. Agents can now prepare a compact handoff for
  one persisted automation with bounded schedule metadata, optional prompt
  snippet, recent run-history payloads, retry/precision state, and optional
  markdown without mutating the task or enqueueing work. Focused
  `BuiltInToolsTest`, ktlint, and the full offline fast loop passed.
- 2026-06-17: Added `sessions.handoff` / `session.handoff` /
  `sessions.snapshot` / `session.snapshot` plus `chat.handoff` as a Session
  Contract context-transfer tool. Agents can now prepare a compact handoff for
  the active or specified session with bounded metadata, optional summary
  snippet, compacted-boundary state, recent chronological message snippets, and
  optional markdown without loading or exporting the full transcript. Focused
  `BuiltInToolsTest`, ktlint, and the full offline fast loop passed.
- 2026-06-17: Added `tasks.preview.occurrences` /
  `task.preview.occurrences` plus automation preview aliases as an Automation
  Contract schedule-planning tool for unsaved schedules. Agents can now provide
  a once, interval, or cron schedule payload and inspect a bounded sequence of
  upcoming run instants before creating or updating an automation, with no task
  persistence or work enqueueing. Focused `BuiltInToolsTest`, ktlint, and the
  full offline fast loop passed.
- 2026-06-17: Added `tasks.occurrences` / `task.occurrences` /
  `tasks.schedule.occurrences` / `task.schedule.occurrences` plus automation
  aliases as an Automation Contract schedule-planning tool. Agents can now
  preview a bounded sequence of future scheduled run times for one persisted
  automation from an optional exclusive ISO lower bound without mutating the
  task, recording runs, or enqueueing work. Focused `BuiltInToolsTest`,
  ktlint, and the full offline fast loop passed.
- 2026-06-17: Added `events.delete` / `event.delete` /
  `logs.delete` / `log.delete` plus remove aliases as a Tool Contract exact
  diagnostics retention control. Agents can now delete one local runtime event
  log by id only with `confirm=CONFIRM`, preserving neighboring diagnostics
  while returning deleted event metadata and count. Focused `BuiltInToolsTest`,
  ktlint, and the full offline fast loop passed.
- 2026-06-17: Added `events.clear` / `event.clear` / `logs.clear` /
  `log.clear` as a Tool Contract diagnostics retention control. Agents can now
  clear local runtime event logs only with `confirm=CONFIRM`, receiving a
  deleted-count payload while the registry still records the completion event
  for auditability. Focused `BuiltInToolsTest`, ktlint, and the full offline
  fast loop passed.
- 2026-06-17: Added `tasks.run.delete` / `task.run.delete` /
  `tasks.run.remove` plus automation run-delete aliases as an Automation
  Contract exact run-history retention control. Agents can now delete one
  specified task-run row only with `confirm=CONFIRM`, preserving the parent
  automation and sibling runs while returning deleted run metadata and count.
  Focused `BuiltInToolsTest`, ktlint, and the full offline fast loop passed.
- 2026-06-17: Added `tasks.runs.clear_status` /
  `task.runs.clear_status` / `tasks.runs.status.clear` plus automation
  status-clear aliases as an Automation Contract run-history retention control.
  Agents can now clear only Pending, Running, Success, Failure, or Skipped
  run-history rows after `confirm=CONFIRM`, preserving other statuses while
  returning the deleted count for focused cleanup. Focused `BuiltInToolsTest`,
  ktlint, and the full offline fast loop passed.
- 2026-06-17: Added `tasks.runs.clear` / `task.runs.clear` /
  `tasks.history.clear` plus automation run-history clear aliases as an
  Automation Contract per-task retention control. Agents can now clear all
  persisted run-history rows for one specified automation only with
  `confirm=CONFIRM`, preserving the task and other automations' histories while
  returning the deleted count. Focused `BuiltInToolsTest`, ktlint, and the full
  offline fast loop passed.
- 2026-06-17: Added `tasks.runs.trim` / `task.runs.trim` /
  `tasks.runs.prune` plus automation run-history trim aliases as an Automation
  Contract retention control. Agents can now delete task-run history scheduled
  before an explicit ISO-8601 cutoff only with `confirm=CONFIRM`, preserving
  boundary-and-newer runs while returning deleted counts for local history
  maintenance. Focused `BuiltInToolsTest`, ktlint, and the full offline fast
  loop passed.
- 2026-06-17: Added `tasks.disable_all` / `task.disable_all` /
  `tasks.pause_all` plus automation pause aliases and `tasks.enable_all` /
  `task.enable_all` / `tasks.resume_all` plus automation resume aliases as an
  Automation Contract bulk control. Agents can now pause every enabled
  automation or resume every disabled automation only with `confirm=CONFIRM`,
  while receiving bounded changed-task payloads and counts so global scheduler
  state can be managed without one tool call per task. Focused
  `BuiltInToolsTest`, ktlint, and the full offline fast loop passed.
- 2026-06-17: Added `sessions.summary.update` /
  `session.summary.update` / `sessions.summary.set` /
  `session.summary.set` plus summary-clear aliases as a Session Contract
  lightweight-summary control. Agents can now replace the active or specified
  session summary without compacting or loading the transcript, preserve an
  existing compaction boundary when refreshing a compacted summary, and clear
  summary plus boundary metadata only with `confirm=CONFIRM`. Focused
  `BuiltInToolsTest`, ktlint, and the full offline fast loop passed.
- 2026-06-17: Added `sessions.compare` / `session.compare` /
  `sessions.diff` / `session.diff` plus compare-transcript aliases as a
  Session Contract planning tool. Agents can now compare two active or
  specified sessions without loading full transcripts, including per-side
  summary/compaction metadata, aggregate message/content counts, role stats,
  bounded recent snippets, and right-minus-left deltas for merge/fork planning.
  Focused `BuiltInToolsTest`, ktlint, and the full offline fast loop passed.
- 2026-06-17: Added `sessions.merge` / `session.merge` /
  `sessions.merge_into` / `sessions.combine` plus singular aliases as a
  Session Contract transcript-composition control. Agents can now copy one
  session's messages into an active or specified target session, optionally
  archive the source with `confirm=CONFIRM`, and optionally copy source summary
  plus remapped compaction-boundary metadata into an empty or explicitly
  replaced target summary slot. Focused `BuiltInToolsTest`, ktlint, and the
  full offline fast loop passed.
- 2026-06-17: Added `messages.create` / `message.create` /
  `messages.add` / `messages.append` plus chat-message append aliases as a
  Session Contract transcript-construction control. Agents can now append one
  typed `user`, `assistant`, `tool_call`, `tool_result`, or `system` message to
  the active or specified session, preserve optional tool-call/task-run
  references, and receive the persisted message id plus bounded content
  metadata. Focused `BuiltInToolsTest`, ktlint, and the full offline fast loop
  passed.
- 2026-06-17: Added `messages.move` / `message.move` /
  `messages.transfer` / `message.transfer` plus chat-message move aliases as a
  Session Contract transcript-organization control. Agents can now move one
  message into a different active or specified target session only with
  `confirm=CONFIRM`, preserve content/role/provider metadata/tool and task
  references under a fresh message id, delete the source message, and clear a
  stale source compaction boundary while preserving the source summary. Focused
  `BuiltInToolsTest`, ktlint, and the full offline fast loop passed.
- 2026-06-17: Added `messages.copy` / `message.copy` /
  `messages.duplicate` / `message.duplicate` plus chat-message copy aliases as
  a Session Contract transcript-branching control. Agents can now copy one
  message into the active or specified target session, duplicate it in place by
  default, preserve role/content with optional provider metadata and tool/task
  reference copying, and receive source/target session metadata plus the new
  message id. Focused `BuiltInToolsTest`, ktlint, and the full offline fast
  loop passed.
- 2026-06-17: Added `messages.update` / `message.update` /
  `messages.edit` / `message.edit` plus chat-message aliases as a Session
  Contract transcript-editing control. Agents can now replace one message's
  content only with `confirm=CONFIRM`, preserve session linkage, provider/tool
  metadata, summaries, and compaction-boundary ids, and receive before/after
  snippets plus truncation metadata. Focused `BuiltInToolsTest`, ktlint, and
  the full offline fast loop passed.
- 2026-06-17: Added `messages.delete` / `message.delete` /
  `messages.remove` / `message.remove` as a Session Contract transcript-editing
  control. Agents can now delete one message only with `confirm=CONFIRM`,
  preserve the rest of the session, and automatically clear stale compaction
  boundary metadata when the deleted message was the boundary while preserving
  the saved summary. Focused `BuiltInToolsTest`, ktlint, and the full offline
  fast loop passed.
- 2026-06-17: Added `sessions.delete` /
  `session.delete` / `sessions.remove` / `session.remove` plus destroy aliases
  as a Session Contract deletion control. Agents can now permanently delete a
  normal session and its transcript only with `confirm=CONFIRM`; main-session
  deletion is rejected, and the payload reports deleted transcript count plus
  prior summary/compaction metadata. Focused `BuiltInToolsTest`, ktlint, and
  the full offline fast loop passed.
- 2026-06-17: Added `sessions.clear` /
  `session.clear` / `sessions.messages.clear` / `session.messages.clear` plus
  transcript clear aliases as a Session Contract transcript reset control.
  Agents can now clear all messages from an active or specified session only
  with `confirm=CONFIRM`, preserve the session row and summary by default,
  optionally clear the summary, and always clear stale compaction-boundary
  message ids after transcript deletion. Focused `BuiltInToolsTest`, ktlint,
  and the full offline fast loop passed.
- 2026-06-17: Added `tools.namespaces` /
  `tool.namespaces` / `tools.namespace` / `tool.namespace` plus group aliases
  as a Tool Contract namespace-discovery helper. Agents can now summarize
  canonical tool namespaces such as `sessions`, `tasks`, `skills`, `providers`,
  and `tools`, or list bounded tools within one namespace with availability
  stats, aliases, argument counts, and permission counts without loading full
  descriptor schemas. Focused `BuiltInToolsTest`, ktlint, and the full offline
  fast loop passed.
- 2026-06-17: Added `tools.validate` /
  `tool.validate` / `tools.check` / `tool.check` plus dry-run aliases as a
  Tool Contract invocation preflight helper. Agents can now resolve a target
  tool, validate candidate arguments, see missing required and unknown provided
  fields, inspect availability, and confirm whether the invocation would pass
  registry checks without executing the target tool. Focused `BuiltInToolsTest`,
  ktlint, and the full offline fast loop passed.
- 2026-06-17: Added `tools.permissions` /
  `tool.permissions` / `tools.permission` / `tool.permission` plus
  by-permission aliases as a Tool Contract Android permission-discovery helper.
  Agents can now summarize permission requirements or list bounded tools that
  require one permission by name, suffix, or display name, including
  availability stats and matching permission metadata without loading full
  schemas. Focused `BuiltInToolsTest`, ktlint, and the full offline fast loop
  passed.
- 2026-06-17: Added `tools.availability` /
  `tool.availability` / `tools.status` / `tool.status` plus readiness aliases
  as a Tool Contract readiness-discovery helper. Agents can now summarize
  availability status counts or list bounded tools for one status, with
  foreground-required filtering, aliases, permissions, and argument counts
  without loading every descriptor schema. Focused `BuiltInToolsTest`, ktlint,
  and the full offline fast loop passed.
- 2026-06-17: Added `tools.arguments` /
  `tool.arguments` / `tools.by_argument` / `tool.by_argument` plus short
  argument aliases as a Tool Contract metadata-discovery helper. Agents can now
  summarize declared argument names across the registry or list bounded tools
  that declare a specific argument, with required-only filtering, availability,
  aliases, and matching argument metadata without loading every schema. Focused
  `BuiltInToolsTest`, ktlint, and the full offline fast loop passed.
- 2026-06-17: Added `tools.resolve` / `tool.resolve` /
  `tools.alias` / `tool.alias` as a Tool Contract alias-resolution helper.
  Agents can now resolve a requested canonical name or alias to the canonical
  descriptor, explicit alias/canonical status, availability, arguments, and
  alias list without trial execution. Focused `BuiltInToolsTest`, ktlint, and
  the full offline fast loop passed.
- 2026-06-17: Added `sessions.uncompact` /
  `session.uncompact` / `sessions.decompact` / `sessions.expand` plus singular
  aliases as a Session Contract compaction recovery tool. Agents can now clear
  an active or specified session's compaction boundary so older messages become
  visible again while preserving the saved summary by default; deleting the
  summary is supported only with `clearSummary=true` and `confirm=CONFIRM`.
  Focused `BuiltInToolsTest`, ktlint, and the full offline fast loop passed.
- 2026-06-17: Added `sessions.activity` /
  `session.activity` / `sessions.timeline` / `session.timeline` plus recent
  aliases as a Session Contract activity overview. Agents can now inspect a
  bounded list of active or optionally archived sessions ordered by latest
  message/update activity, including message counts, summary/compaction flags,
  and latest-message snippets without loading transcripts. Focused
  repository/tool tests, ktlint, and the full offline fast loop passed.
- 2026-06-17: Added `messages.page` / `message.page` /
  `chat.page` / `messages.transcript` / `chat.transcript` /
  `session.transcript` as a Session Contract transcript pagination tool.
  Agents can now inspect bounded chronological pages from the start, recent
  tail, before an anchor, or after an anchor without loading full histories,
  with anchor validation, provider/tool reference flags, session metadata, and
  repository-level paging helpers. Focused repository/tool tests, ktlint, and
  the full offline fast loop passed.
- 2026-06-17: Added `sessions.fork` / `session.fork` /
  `sessions.duplicate` / `session.duplicate` plus copy aliases as a Session
  Contract branching tool. Agents can now fork an active or specified session
  into a new active normal session, copy transcript messages with fresh message
  ids, optionally skip message or summary copying, and preserve summary plus
  remapped compaction-boundary metadata when the source boundary message is
  copied. Focused repository/tool tests, ktlint, and the full offline fast loop
  passed.
- 2026-06-17: Added `sessions.summaries` /
  `session.summaries` / `sessions.summarized` / `sessions.compacted` plus
  compacted aliases as a Session Contract summary discovery tool. Agents can
  now list bounded sessions that carry summary text or compaction boundary
  metadata, include archived sessions on request, and inspect summary snippets,
  truncation state, timestamps, and compaction boundaries without loading full
  histories. Focused repository/tool tests, ktlint, and the full offline fast
  loop passed.
- 2026-06-17: Added `messages.role` / `message.role` /
  `chat.role` plus by-role aliases as a Session Contract transcript filter.
  Agents can now list bounded recent messages for one role within the active or
  specified session, reusing repository caps and payload snippets with session
  metadata, while invalid roles fail with typed argument errors. Focused
  repository/tool tests, ktlint, and the full offline fast loop passed.
- 2026-06-17: Added `messages.reference` /
  `message.reference` / `chat.reference` plus reference aliases as a Session
  and Tool Contract provenance lookup. Agents can now list bounded recent chat
  messages linked to exactly one `toolCallId` or automation `taskRunId`, with
  session titles/archive flags, snippets, and stored reference ids while
  preserving repository caps and rejecting ambiguous reference arguments.
  Focused repository/tool tests, ktlint, and the full offline fast loop passed.
- 2026-06-17: Added `messages.context` / `message.context` /
  `chat.context` plus around aliases as a Session Contract transcript-window
  tool. Agents can now inspect a bounded chronological message window around an
  exact message id, including before/anchor/after markers, session metadata,
  provider/tool reference flags, and repository-capped side limits without
  loading full histories. Focused repository/tool tests, ktlint, and the full
  offline fast loop passed.
- 2026-06-17: Added `memory.message` / `memories.message` /
  `memory.by_message` / `memories.by_message` plus source-message aliases as a
  Memory Contract provenance inspector. Agents can now list bounded active
  memories captured from one source message id, using the same source-message id
  normalization as persistence, while deleted rows and owner ids stay hidden.
  Focused repository/tool tests, ktlint, and the full offline fast loop passed.
- 2026-06-17: Added `memory.source` / `memories.source` /
  `memory.by_source` / `memories.by_source` plus source-list aliases as a
  Memory Contract provenance filter. Agents can now list bounded active manual
  or automatic memories by source type, with explicit typed failures for
  missing/invalid source types, deleted rows excluded, and owner ids still
  hidden from payloads. Focused repository/tool tests, ktlint, and the full
  offline fast loop passed.
- 2026-06-17: Added `memory.session` / `memories.session` /
  `memory.by_session` / `memories.by_session` plus session-list aliases as a
  Memory Contract source-session inspector. Agents can now list bounded active
  memories captured from the current or specified source session, with deleted
  and other-owner memories excluded and the local install owner id still hidden.
  Focused repository/tool tests, ktlint, and the full offline fast loop passed.
- 2026-06-17: Added `skills.secret.clear` /
  `skill.secret.clear` / `skills.secrets.clear` / `skill.secrets.clear` plus
  delete aliases as a Skill Contract secret hygiene control. Agents can now
  clear a declared saved skill secret only with `confirm=CONFIRM`, receive the
  updated non-secret configuration payload, and get typed
  `SKILL_SECRET_NOT_FOUND` failures for undeclared env names without ever
  reading, setting, or returning secret values. Focused `BuiltInToolsTest`,
  ktlint, and the full offline fast loop passed.
- 2026-06-17: Added `skills.config.update` /
  `skill.config.update` / `skills.configuration.update` /
  `skill.configuration.update` plus concise set aliases as a Skill Contract
  configuration writer. Agents can now set or clear declared non-secret config
  values for a skill, receive the updated non-secret configuration payload, and
  get typed `SKILL_CONFIG_NOT_FOUND` failures for undeclared paths while secret
  values remain unsupported by this tool. Focused `BuiltInToolsTest`, ktlint,
  and the full offline fast loop passed.
- 2026-06-17: Added `skills.config.get` /
  `skill.config.get` / `skills.configuration.get` / `skill.configuration.get`
  plus concise config aliases as a Skill Contract configuration inspector.
  Agents can now inspect declared config values, secret configured/missing
  booleans, and recovery notices for one skill without exposing secret values,
  making skill setup legible from typed tools instead of only the GUI. Focused
  `BuiltInToolsTest`, ktlint, and the full offline fast loop passed.
- 2026-06-17: Added `skills.refresh` /
  `skill.refresh` / `skills.rescan` / `skill.rescan` as a Skill Contract
  inventory control. Agents can now force a bounded reload of bundled, local,
  and active-session workspace skills after imports or filesystem changes, with
  optional session scoping and compact metadata payloads that avoid returning
  full `SKILL.md` bodies. Focused `BuiltInToolsTest`, ktlint, and the full
  offline fast loop passed.
- 2026-06-17: Added `tasks.run.retry` /
  `task.run.retry` / `tasks.retry_run` / `task.retry_run` plus automation
  aliases as an Automation Contract recovery control. Agents can now queue a
  manual retry directly from a failed or skipped run id returned by run-history
  tools, while successful/in-progress runs are rejected with typed
  `TASK_RUN_NOT_RETRYABLE` failures and future schedules remain unchanged.
  Focused `BuiltInToolsTest`, ktlint, and the full offline fast loop passed.
- 2026-06-17: Added `tasks.runs.status` /
  `task.runs.status` / `tasks.status_runs` / `task.status_runs` plus
  automation aliases as an Automation Contract diagnostics filter. Agents can
  now inspect bounded recent automation runs by `Pending`, `Running`,
  `Success`, `Failure`, or `Skipped` status with parent task metadata,
  complementing recent/all and failure-only run tools. Focused
  `BuiltInToolsTest`, ktlint, and the full offline fast loop passed.
- 2026-06-17: Added `tasks.runs.recent` /
  `task.runs.recent` / `tasks.recent_runs` / `task.recent_runs` plus
  automation aliases as an Automation Contract diagnostics tool. Agents can now
  inspect bounded recent automation runs across all tasks and statuses with
  parent task metadata, complementing per-task history and failure-only triage.
  Focused repository/tool tests, ktlint, and the full offline fast loop passed.
- 2026-06-17: Added `tasks.failures` / `task.failures` /
  `tasks.failed_runs` / `task.failed_runs` plus automation aliases as an
  Automation Contract diagnostics tool. Agents can now inspect bounded recent
  failed automation runs across all tasks with parent task metadata, enabling
  quick recovery triage before using run inspectors, reschedule, or snooze.
  Focused repository/tool tests, ktlint, and the full offline fast loop passed.
- 2026-06-17: Added `tasks.preview` / `task.preview` /
  `tasks.schedule.preview` / `task.schedule.preview` plus automation aliases as
  an Automation Contract planning tool. Agents can now validate once, interval,
  or cron schedule payloads and see the next run time without creating or
  mutating a task, reusing the same parser and scheduler bounds as task
  creation. Focused `BuiltInToolsTest`, ktlint, and the full offline fast loop
  passed.
- 2026-06-17: Added `tasks.reschedule` / `task.reschedule` /
  `tasks.recompute_next` / `task.recompute_next` plus automation aliases as an
  Automation Contract recovery control. Agents can now recompute a task's next
  scheduled run from its schedule without executing it, clear stale retry
  state, and re-enqueue or cancel work according to the recalculated future.
  Focused `BuiltInToolsTest`, ktlint, and the full offline fast loop passed.
- 2026-06-17: Added `tasks.snooze` / `task.snooze` /
  `tasks.postpone` / `task.postpone` plus automation aliases as an Automation
  Contract triage control. Agents can now postpone a currently due automation
  by bounded minutes or an explicit ISO instant without recording a run,
  reschedule the task immediately, and receive the updated task payload.
  Focused `BuiltInToolsTest`, ktlint, and the full offline fast loop passed.
- 2026-06-17: Added `tasks.skip` / `task.skip` /
  `tasks.skip_due` / `task.skip_due` plus automation aliases as an Automation
  Contract triage control. Agents can now skip one currently due run without
  executing its prompt, persist a `Skipped` run-history entry, advance or clear
  the next scheduled run, and receive the updated task payload. Focused
  `BuiltInToolsTest`, ktlint, and the full offline fast loop passed.
- 2026-06-17: Added `tasks.due` / `task.due` /
  `tasks.overdue` / `task.overdue` plus automation aliases as a lightweight
  Automation Contract triage tool. It lists bounded enabled scheduled tasks
  that are due at the current clock time, reports overdue age in seconds, and
  skips disabled, unscheduled, future, or malformed schedule rows before
  returning results. Focused repository/tool tests, ktlint, and the full
  offline fast loop passed.
- 2026-06-16: Shifted the active slice selection to feature-first per user
  direction. Paused the interrupted provider-metadata boundary hardening WIP and
  selected a small Session/Tool Contract feature: a typed `sessions.rename`
  built-in tool so the agent can retitle the active or specified chat session
  without relying on manual UI actions.
- 2026-06-16: Added `sessions.rename` / `session.rename` as a typed built-in
  tool. It renames the active session by default, can target an explicit
  session id, returns the previous/stored title in payloads, and is covered by
  JVM registry tests. Focused `BuiltInToolsTest`, ktlint, and the full offline
  fast loop passed.
- 2026-06-16: Added `sessions.create` / `session.create` as a typed built-in
  tool so the agent can start a new normal chat session with a title and receive
  the persisted session id/title payload. Focused `BuiltInToolsTest`, ktlint,
  and the full offline fast loop passed.
- 2026-06-16: Added agent-usable session visibility controls:
  `sessions.archive` / `session.archive`, `sessions.unarchive` /
  `session.unarchive`, and `sessions.list(includeArchived=true)`. The main
  session remains protected from archival, archived sessions can be discovered
  and restored by id, and repository/tool tests cover the round trip. Focused
  registry/repository tests, ktlint, and the full offline fast loop passed.
- 2026-06-16: Added `sessions.get` / `session.get` as a lightweight typed
  session inspector. The tool defaults to the active session or accepts an
  explicit session id, returns title, timestamps, archive/main flags, compact
  summary/boundary metadata, and a message count without loading full history.
  Focused `BuiltInToolsTest`, ktlint, and the full offline fast loop passed.
- 2026-06-16: Added `sessions.search` / `session.search` as a typed session
  discovery tool over active session titles. It reuses the bounded repository
  title search, supports an optional result limit, excludes archived sessions,
  and returns id/title payloads for navigation. Focused `BuiltInToolsTest`,
  ktlint, and the full offline fast loop passed.
- 2026-06-16: Added `messages.search` / `message.search` / `chat.search` as a
  typed active-transcript lookup tool. It reuses the existing message-content
  search over non-archived sessions, returns session/message/role metadata with
  compact content snippets, and lets the agent find prior context without
  loading complete histories. Focused `BuiltInToolsTest`, ktlint, and the full
  offline fast loop passed.
- 2026-06-16: Added `messages.recent` / `message.recent` / `chat.recent` as a
  typed recent-transcript lookup tool. It resolves the active or specified
  session, returns bounded recent-first message snippets with role/time metadata
  plus total/returned counts, and avoids full-history loading for quick context
  inspection. Focused `BuiltInToolsTest`, ktlint, and the full offline fast
  loop passed.
- 2026-06-16: Added `messages.get` / `message.get` / `chat.message.get` as an
  exact-message inspector for ids returned by search/recent tools. It returns
  session title/archive metadata, role/time, bounded content snippets, content
  length/truncation state, and tool/task reference ids without exposing full
  provider metadata. Focused `BuiltInToolsTest`, ktlint, and the full offline
  fast loop passed.
- 2026-06-16: Added `tasks.runs` / `task.runs` / `tasks.history` /
  `task.history` as a typed automation run-history inspector. It uses a new
  bounded DAO/repository query instead of observing all runs, returns newest
  runs first with status/time/result/error/output-message metadata, and is
  covered by repository plus tool tests. Focused tests, ktlint, and the full
  offline fast loop passed.
- 2026-06-16: Added `tasks.search` / `task.search` as a typed automation
  discovery tool over persisted task names and prompts. It uses a bounded DAO
  search with escaped SQL LIKE patterns, returns compact prompt snippets plus
  schedule/enabled/target metadata instead of full prompts, and is covered by
  repository plus tool tests. Focused tests, ktlint, and the full offline fast
  loop passed.
- 2026-06-16: Added `tasks.run.get` / `task.run.get` / `taskrun.get` as an
  exact automation-run inspector for ids returned by `tasks.runs`. The
  repository now supports direct run lookup, and the tool returns parent task
  metadata plus the bounded run payload. Focused tests, ktlint, a reproducing
  `ChatViewModelTest` rerun after a transient executor failure, and a clean full
  offline fast-loop rerun passed.
- 2026-06-16: Added `tasks.duplicate` / `task.duplicate` / `tasks.copy` /
  `task.copy` as a typed automation cloning tool. It copies the persisted
  schedule, prompt, execution mode, target session, precision request, and retry
  budget, defaults the new copy to disabled for safe editing, and can
  immediately enable/schedule the copy when requested. Focused `BuiltInToolsTest`,
  ktlint, and the full offline fast loop passed.
- 2026-06-16: Added `skills.get` / `skill.get` as a typed Skill Contract
  inspector. Agents can now load one skill by id, key, or display name and
  receive eligibility/configuration metadata, frontmatter, and bounded
  `SKILL.md` instructions without relying only on the summary list. Focused
  `BuiltInToolsTest`, ktlint, and the full offline fast loop passed.
- 2026-06-16: Added `skills.enable` / `skill.enable` and `skills.disable` /
  `skill.disable` as typed Skill Contract controls. The built-in tool registry
  now exposes the full skill inventory instead of only currently effective model
  skills, so agents can discover disabled or ineligible skills and toggle them
  through the same `SkillManager` path used by the GUI. Focused
  `BuiltInToolsTest`, ktlint, and the full offline fast loop passed.
- 2026-06-16: Added `skills.search` / `skill.search` as a bounded Skill
  Contract discovery tool over skill id/key/name, frontmatter metadata, command
  tool names, and `SKILL.md` instructions. It returns compact result payloads
  that include enabled/eligibility state and command dispatch metadata, allowing
  agents to find relevant disabled or ineligible skills before calling
  `skills.get` or toggling them. Focused `BuiltInToolsTest`, ktlint, and the
  full offline fast loop passed.
- 2026-06-16: Added `tools.list` / `tool.list`, `tools.get` / `tool.get`, and
  `tools.search` / `tool.search` as typed Tool Contract introspection tools.
  Agents can now inspect canonical tool names, aliases, availability,
  permissions, arguments, and input schemas without relying on the coarse
  `health.status` payload. Focused `BuiltInToolsTest`, ktlint, and the full
  offline fast loop passed.
- 2026-06-16: Added `providers.list` / `provider.list`, `providers.current` /
  `provider.current`, and `providers.get` / `provider.get` as typed provider
  introspection tools. They expose the selected provider, protocol/auth mode,
  and non-secret endpoint/model/timeout settings for all configured provider
  families, improving provider/OAuth legibility without exposing stored
  credentials. Focused `BuiltInToolsTest`, ktlint, and the full offline fast
  loop passed.
- 2026-06-16: Added `providers.select` / `provider.select` /
  `providers.use` / `provider.use` so agents can switch the active model
  provider through the same settings path as the GUI. The tool accepts provider
  id, storage value, enum name, or display name and returns the selected
  provider's non-secret metadata. Focused `BuiltInToolsTest`, ktlint, and the
  full offline fast loop passed.
- 2026-06-16: Added `providers.configure` / `provider.configure` /
  `providers.update` / `provider.update` for non-secret provider endpoint
  changes. Agents can update base URL, model id, and timeout seconds for remote
  providers without changing the currently selected provider or exposing stored
  credentials. Focused `BuiltInToolsTest`, ktlint, and the full offline fast
  loop passed.
- 2026-06-16: Added `providers.auth.status` / `provider.auth.status` /
  `providers.auth` / `provider.auth` as a non-secret provider authentication
  inspector. It reports whether API-key and OpenAI Codex OAuth credentials are
  configured, selected provider state, OAuth expiry metadata, and not-required
  auth status for local providers without returning secret values. Focused
  `BuiltInToolsTest`, ktlint, and the full offline fast loop passed.
- 2026-06-16: Added `memory.get` / `memories.get` plus `/memory get <id>` as
  exact Memory Contract inspectors for ids returned by search/list tools. The
  repository now supports owner-scoped active memory lookup, payloads include
  source message ids and update timestamps without exposing the local install
  owner id, and missing/deleted memories return typed `MEMORY_NOT_FOUND`
  failures. Focused memory/tool tests, ktlint, and the full offline fast loop
  passed.
- 2026-06-16: Added `memory.update` / `memories.update` plus
  `/memory update <id> <text>` so agents can correct exact local memories after
  inspecting them. Updates are owner-scoped, only affect active memories,
  preserve source provenance, normalize/bound replacement text through the
  repository, refresh `updatedAt`, and return the same non-secret memory payload
  as `memory.get`. Focused memory/tool tests, ktlint, and the full offline fast
  loop passed.
- 2026-06-16: Added consistent singular `task.*` aliases for task tools that
  only exposed `tasks.*` names (`task.get`, `task.create`, `task.update`,
  `task.enable`, `task.disable`, `task.delete`, and `task.run_now`). This keeps
  automation tool naming aligned with the already-supported singular aliases for
  list/search/history/copy, and `tools.get` now verifies alias resolution for
  `task.create`. Focused `BuiltInToolsTest`, ktlint, and the full offline fast
  loop passed.
- 2026-06-16: Added `events.recent` / `logs.recent` as a bounded Tool Contract
  diagnostics feature. Agents can now inspect recent local runtime events with
  optional category/level filters, details are hidden by default and only
  returned as bounded payloads when explicitly requested, and invalid filters
  return typed argument failures. Focused `BuiltInToolsTest`, ktlint, and the
  full offline fast loop passed.
- 2026-06-16: Added `events.get` / `event.get` / `logs.get` / `log.get` for
  exact event-log lookup by id. The EventLog DAO/repository now supports direct
  bounded reads, the tool hides details by default but can include bounded
  details when requested, and missing ids return typed `EVENT_NOT_FOUND`
  failures. Focused event repository/tool tests, ktlint, and the full offline
  fast loop passed.
- 2026-06-16: Added `events.search` / `event.search` / `logs.search` /
  `log.search` for bounded diagnostics search over recent local event logs.
  Agents can search event ids, categories, levels, messages, and details, while
  matching details remain hidden by default unless `includeDetails=true` is
  passed. Category/level filters and no-match results are covered by focused
  tool tests, and ktlint plus the full offline fast loop passed.
- 2026-06-16: Added `events.stats` / `event.stats` / `logs.stats` /
  `log.stats` for aggregate diagnostics over recent local event logs without
  returning event details. The tool reports scanned/matched counts,
  category/level histograms, optional category/level filters, and newest/oldest
  timestamps for the matched window. Focused `BuiltInToolsTest`, ktlint, and the
  full offline fast loop passed.
- 2026-06-16: Added `events.trim` / `event.trim` / `logs.trim` / `log.trim`
  to prune local event logs older than an explicit ISO-8601 cutoff after
  `confirm=CONFIRM`. The tool returns typed confirmation/argument failures,
  uses the existing EventLogRepository retention path, preserves newer events,
  and focused `BuiltInToolsTest`, ktlint, plus the full offline fast loop
  passed.
- 2026-06-16: Added `messages.stats` / `message.stats` / `chat.stats` as a
  lightweight transcript aggregate tool. It resolves the active or specified
  session, uses a bounded Room aggregate query instead of loading full message
  history, and returns total message/content counts, oldest/newest timestamps,
  and per-role histograms for agent context planning. Focused repository/tool
  tests, ktlint, and the full offline fast loop passed.
- 2026-06-16: Added `tasks.stats` / `task.stats` / `automations.stats` /
  `automation.stats` as a lightweight Automation Contract aggregate tool. It
  uses Room aggregate queries over tasks and task runs to report enabled,
  disabled, scheduled, due, schedule-kind, execution-mode, and run-status
  counts without loading every task/run payload. Focused repository/tool tests,
  ktlint, and the full offline fast loop passed.
- 2026-06-16: Added `memory.stats` / `memories.stats` plus `/memory stats` as
  a lightweight local Memory Contract aggregate tool. It reports enabled state,
  active/deleted/total owner-scoped memory counts, active source-session counts,
  source-type histograms, and active timestamp bounds without exposing the
  install owner id. Focused repository/tool tests, ktlint, and the full offline
  fast loop passed.
- 2026-06-16: Added `sessions.stats` / `session.stats` /
  `chat.sessions.stats` as a lightweight Session Contract aggregate tool. It
  reports total, active, archived, main, summarized, and compacted session
  counts plus timestamp bounds without loading transcripts or message payloads.
  Focused repository/tool tests, ktlint, and the full offline fast loop passed.
- 2026-06-16: Added `skills.stats` / `skill.stats` as a lightweight Skill
  Contract aggregate tool. It reports enabled/disabled, eligible/ineligible,
  model-ready, tool-dispatch, frontmatter/parse-error, source-type,
  eligibility, dispatch, resolution, secret, and config counts without loading
  full `SKILL.md` instruction bodies. Focused `BuiltInToolsTest`, ktlint, and
  the full offline fast loop passed.
- 2026-06-16: Added `tools.stats` / `tool.stats` as a lightweight Tool Contract
  aggregate tool. It reports total tools, aliases, argument metadata,
  foreground requirements, permission requirements, and availability/permission
  histograms without returning full input schemas. Focused `BuiltInToolsTest`,
  ktlint, and the full offline fast loop passed.
- 2026-06-16: Added `providers.stats` / `provider.stats` as a lightweight
  provider/OAuth aggregate tool. It summarizes provider inventory, selected
  provider metadata, protocol/auth-mode histograms, endpoint customization
  counts, and non-secret API-key/OAuth status counts without exposing stored
  credentials. Focused `BuiltInToolsTest`, ktlint, and the full offline fast
  loop passed.
- 2026-06-16: Added `providers.reset` / `provider.reset` /
  `providers.defaults` / `provider.defaults` for agent-usable provider endpoint
  recovery. It resets a configurable provider's non-secret base URL, model id,
  and timeout to defaults without changing the selected provider or touching
  credentials, and rejects local providers with typed errors. Focused
  `BuiltInToolsTest`, ktlint, and the full offline fast loop passed.
- 2026-06-16: Added `memory.restore` / `memories.restore` plus
  `/memory restore <id>` as a Memory Contract recovery feature. It reactivates
  soft-deleted local memories only while memory is enabled, reports whether the
  memory was restored or already active, and keeps disabled-memory opt-out
  behavior intact while preserving delete/clear management paths. Focused
  `BuiltInToolsTest`, ktlint, and the full offline fast loop passed.
- 2026-06-16: Added `memory.deleted` / `memories.deleted` /
  `memory.trash` plus `/memory deleted` and `/memory trash` so agents can
  discover restorable soft-deleted memory ids. The tool returns bounded deleted
  memory payloads with deletion timestamps, hides owner identifiers, and stays
  blocked while memory is disabled. Focused `BuiltInToolsTest`, ktlint, and the
  full offline fast loop passed.
- 2026-06-16: Added `tasks.next` / `task.next` / `tasks.upcoming` /
  `task.upcoming` plus automation aliases as a lightweight Automation Contract
  planning tool. It lists bounded enabled scheduled tasks by next run time,
  marks due tasks, reports seconds until run, and skips disabled, unscheduled,
  or malformed schedule rows before returning results. Focused repository/tool
  tests, ktlint, and the full offline fast loop passed.

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
- 2026-06-16: Bounded provider-facing tool descriptor metadata. AgentRunner now
  caps tool descriptor count, aliases, descriptions, permissions, availability
  reasons, arguments, and custom input-schema JSON before provider requests,
  while ToolRegistry rejects oversized identity metadata so tool names and
  argument keys remain executable rather than silently truncated.
- 2026-06-16: Bounded provider response accumulation. OpenAI-compatible,
  Anthropic, and OpenAI Codex response parsers now cap returned assistant text,
  tool call ids/names, tool-call count, and tool-argument payload size before
  streaming deltas or parsed responses can inflate runtime memory ahead of
  repository persistence bounds.
- 2026-06-16: Bounded AgentRunner provider tool-call execution. Runtime now
  revalidates provider tool-call count, ids, names, and serialized arguments
  before persisting tool requests or dispatching tools, so even custom test or
  future providers cannot bypass parser-level bounds and inflate chat/tool
  execution state.
- 2026-06-16: Bounded AgentRunner tool-use assistant text. Provider text that
  accompanies tool calls now caps before it is added to the in-memory tool-loop
  transcript and sent back to the provider, preventing custom or future
  providers from bypassing parser/repository text bounds during multi-round
  tool execution.
- 2026-06-16: Bounded remote provider request payloads. OpenAI-compatible,
  Anthropic, and OpenAI Codex providers now defensively cap direct
  `ModelRequest` ids, system prompt text, message-history count/content, and
  transcript tool-call metadata before HTTP serialization, with oversized
  outbound tool arguments rejected before request bodies are built.
- 2026-06-16: Bounded direct provider tool descriptors. The shared remote
  provider request sanitizer now also caps direct `ModelRequest.toolDescriptors`
  count, descriptions, aliases, permissions, argument metadata, and custom input
  schemas before HTTP serialization, while rejecting blank or oversized tool
  names so direct provider callers cannot bypass AgentRunner descriptor bounds.
