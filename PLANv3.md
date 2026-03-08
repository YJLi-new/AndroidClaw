# AndroidClaw Execution Plan v3

> Status: proposed active exec plan for the next development phase.
>
> This file is intended to become the **single active implementation plan**. If it is adopted, either:
>
> 1. rename or copy it to `PLAN.md`, or
> 2. update `AGENTS.md` so it points to `PLANv3.md`.
>
> Do **not** keep `PLAN.md`, `PLANv2.md`, and `PLANv3.md` as competing live plans. Split truth is a build hazard for both humans and Codex. This recommendation follows OpenAI’s agent-first repository guidance: keep `AGENTS.md` short, treat docs as the system of record, and keep versioned execution plans in-repo as first-class artifacts. [R1]

This is a living document. The sections **Progress**, **Discoveries**, **Decision Log**, **Blockers**, and **Outcomes** must be updated continuously as work proceeds.

---

## Read Order

Before making any non-trivial code change, read in this order:

1. `AGENTS.md`
2. `docs/ARCHITECTURE.md`
3. `docs/SCHEDULER.md`
4. `docs/SKILLS_COMPAT.md`
5. `PLANv3.md` (or `PLAN.md` once this plan is adopted)

`AGENTS.md` remains the map. `docs/` remains the system of record. This file is the active execution plan that turns those documents into ordered implementation steps. [R1]

---

## Purpose / Big Picture

AndroidClaw is a **lightweight Android-native local assistant host** inspired by NanoClaw and OpenClaw.

The compatibility goal is **behavioral compatibility**, not a desktop runtime port:

- **Session Contract**: main session, normal sessions, durable history, lightweight summaries
- **Tool Contract**: typed tools with explicit availability and structured failures
- **Skill Contract**: OpenClaw-style `SKILL.md` runtime skills with precedence and enable/disable lifecycle
- **Automation Contract**: `once`, `interval`, and `cron`, with main-session and isolated execution modes

This direction is justified by upstream reality:

- OpenClaw’s Android app is currently a **companion node**, not a local gateway host. [R2]
- OpenClaw runtime skills are `SKILL.md`-based directories loaded from bundled, local, and workspace locations with precedence rules. [R3][R4]
- OpenClaw’s modern tools are typed, first-class tools rather than shell-first skills. [R5]
- OpenClaw cron is a persisted scheduler that can wake the agent and optionally deliver results back to chat. [R6]
- NanoClaw’s core ideas include a main channel, isolated group/session context, scheduled tasks, and lightweight orchestration, but it still assumes desktop/server capabilities such as containers. [R8]

Therefore, AndroidClaw should stay **Kotlin-first, Android-native, single-APK, low-memory, and honest about background limits** rather than trying to embed a desktop runtime.

---

## Current Repository Snapshot (starting point for this plan)

The repository is no longer in bootstrap state. It already contains:

- a single-module Android app
- Compose navigation shell
- Room database and repositories
- session/message/task/skill/event models
- `FakeProvider`
- a small `ToolRegistry`
- bundled skill loading and parsing
- scheduler preview semantics (`once`, `interval`, `cron`)
- JVM coverage for most repository/runtime/viewmodel surfaces
- audit remediation for structured tool/provider failure handling, cached bundled skills, explicit `runNow` unsupported state, and removal of the plain-text API-key placeholder

The latest known validated state from the repository handoff is:

- `:app:testDebugUnitTest` passed
- `:app:assembleDebug` passed
- `:app:lintDebug` passed
- `:app:connectedDebugAndroidTest` was **not** runnable because the Linux-side SDK `adb` could not see the emulator even though a Windows-side LDPlayer `adb` could see `emulator-5554`

Treat that device-test issue as a **real engineering blocker**, not as a reason to skip instrumentation forever. Android’s official guidance for build-managed devices exists precisely to make instrumented testing reproducible from Gradle without relying on ad-hoc local emulator setups. [R15]

### What is already done and must not be redone

- App shell bootstrap
- Room v1 schema
- repository mappings
- basic chat persistence
- feature ViewModels and dependency bundles
- initial scheduler pure functions
- bundled skill parsing
- first round of runtime failure hardening

### What is clearly still missing

- one real network provider path
- durable tool-call contract beyond demo tools
- real scheduler execution (`TaskExecutionWorker` is still a stub)
- working `runNow`
- skill import + precedence across bundled/local/workspace
- durable skill enable/disable lifecycle
- session-lane concurrency protection
- instrumentation path owned by the repo
- baseline profiles
- release-grade DB migration policy

---

## Progress

Keep this section concise and current.

Format:

- `[x] (YYYY-MM-DD) slug — one-line summary`
- `[ ] slug — pending or in progress`

Seeded current state:

- [x] (2026-03-08) bootstrap-shell — Gradle, CI, single-module Compose app shell, manual DI container, and fake-provider chat path shipped.
- [x] (2026-03-08) room-v1 — initial Room schema, DAOs, repositories, and exported schema shipped.
- [x] (2026-03-08) scheduler-preview — `once` / `interval` / `cron` parsing and next-run preview shipped.
- [x] (2026-03-08) bundled-skills — bundled `SKILL.md` loading, parsing, eligibility, and demo skills shipped.
- [x] (2026-03-08) audit-remediation — structured runtime failure handling, bundled-skill cache, explicit unsupported `runNow`, and missing JVM tests shipped.
- [ ] m0-plan-adoption
- [ ] m1-validation-harness
- [ ] m2-provider-v1
- [ ] m3-tools-v1
- [ ] m4-scheduler-core
- [ ] m5-scheduler-precision-and-diagnostics
- [ ] m6-skills-lifecycle
- [ ] m7-runtime-turn-loop
- [ ] m8-gui-completion
- [ ] m9-persistence-hardening
- [ ] m10-performance-and-baseline-profiles
- [ ] m11-release-candidate

---

## Discoveries

Add only facts that change implementation choices.

Seeded discoveries:

- OpenClaw’s Android app is a node, not a host. AndroidClaw is therefore a new host implementation, not a straight port. [R2]
- OpenClaw skills are runtime directories with precedence rules and config/eligibility semantics; that is the correct compatibility model for AndroidClaw. [R3][R4]
- WorkManager is the official durable background-work path on Android; exact alarms are intentionally special and should be reserved for precise user-visible cases. [R9][R11][R12]
- App Standby Buckets and Android 16 quota behavior affect WorkManager-backed jobs, so scheduler diagnostics cannot be an afterthought. [R14]
- Build-managed devices are the most repo-owned path to reproducible instrumentation in this project. [R15]

---

## Decision Log

Log every meaningful trade-off here.

Seeded decisions:

- Decision: AndroidClaw remains a single-module Android app for v0.
  Rationale: less build complexity, less agent confusion, faster iteration.

- Decision: No Node.js, Docker, Chromium, Playwright, or remote-first gateway in the base app.
  Rationale: directly conflicts with the single-APK lightweight goal.

- Decision: Scheduler core uses `OneTimeWorkRequest` plus our own next-run calculation, not `PeriodicWorkRequest` as the semantic center.
  Rationale: cron, manual run, pause/resume, history, and exact-alarm fallback all fit better into a single one-shot scheduling model. [R9][R10]

- Decision: Security is not the primary optimization target, but API keys must still avoid plain-text DataStore storage.
  Rationale: the repository already removed the plain-text placeholder; real provider support needs a minimal but non-embarrassing secret seam.

- Decision: Isolated task execution in v0 means isolated logical context and isolated file root, **not** desktop-style container isolation.
  Rationale: correct for Android, much lighter, and sufficient for the v0 compatibility contract.

---

## Blockers

Update whenever work stops for reasons outside the current diff.

Seeded blocker:

- Instrumentation is not reliably runnable from the same SDK/`adb` environment used by Gradle. The first unblocker is to add a repo-owned test device path (preferably Gradle Managed Devices) instead of relying on the current LDPlayer split-brain setup. [R15]

---

## Outcomes / Retrospective

Leave empty until milestones finish. Each completed milestone should add one short entry answering:

- What shipped?
- What did we intentionally not ship?
- What changed in the plan because of discoveries?
- What should the next agent know immediately?

---

## Non-Negotiable Product Constraints

Unless the user explicitly changes scope, all implementation work must respect these constraints.

1. **Single installable APK / AAB first**
   - No external daemon.
   - No extra desktop host required.
   - No second companion process as a prerequisite.

2. **Runtime lightness first**
   - No embedded Node.js.
   - No Docker.
   - No Chromium/browser automation.
   - No always-on foreground service while idle.

3. **Android-native background honesty**
   - Use WorkManager for durable background execution. [R9][R10]
   - Use exact alarms only for precise user-visible tasks. [R11][R12]
   - Respect while-in-use permission limits for camera/location/microphone. [R13]
   - Surface standby/permission/background restrictions in the UI. [R14]

4. **Manual DI, boring dependencies**
   - No Hilt/Koin.
   - Prefer Room, WorkManager, coroutines, DataStore, OkHttp, kotlinx serialization.
   - Add new dependencies only when they remove real code complexity or unlock required validation.

5. **Security enough, not maximal**
   - Do not spend time on full sandboxing, multi-tenant auth, enterprise policy, or remote sync security.
   - Do not regress into plain-text secret persistence.

6. **Agent-legible repository**
   - Small diffs.
   - Clear package boundaries.
   - Update the living plan during work.
   - Do not hide architecture decisions in chat history alone. [R1]

---

## External Facts That Shape the Design

This section exists so Codex does not need to re-derive platform truth every time.

### Agent-first repository guidance

OpenAI’s harness engineering guidance recommends:

- short `AGENTS.md`
- structured docs as the system of record
- versioned execution plans with progress and decision logs
- repository-local knowledge instead of Slack/Google Docs memory [R1]

This repo should keep following that pattern.

### OpenClaw compatibility facts

- Android is currently a companion node, not the host. [R2]
- Skills are `SKILL.md` directories with YAML frontmatter and instructions. [R3]
- Skill precedence is workspace > managed/local > bundled. [R4]
- `skills.entries.<key>` maps by skill name unless `metadata.openclaw.skillKey` overrides it. [R4]
- Tools are typed and are meant to replace shell-first skill patterns. [R5]
- Cron jobs persist, wake the agent, and can deliver back to chat. [R6]
- OpenClaw uses compact skill metadata in prompt space because skills and tools have real context-window cost. [R21]
- OpenClaw serializes runs per session lane to avoid transcript races. [R22]

### NanoClaw facts worth preserving semantically

NanoClaw exposes the user-visible concepts we care about:

- main/private control channel
- isolated contexts
- scheduled tasks
- lightweight orchestration [R8]

AndroidClaw should preserve those semantics without inheriting the containerized desktop runtime.

### Android platform facts

- WorkManager is the recommended persistent background-work mechanism and persists scheduled work across app restarts and device reboots. [R9][R10]
- WorkManager supports unique work, tags, input data, and retry/backoff policies. [R10]
- Exact alarms are denied by default for most newly installed apps targeting Android 13+ on Android 14+, and the app should check `canScheduleExactAlarms()` and react to permission state changes. [R11]
- Exact alarms are intended for precise, time-sensitive interruptions, not general recurring background work. [R12]
- Camera, microphone, and location foreground-service types are constrained by while-in-use permissions; some background starts are disallowed. [R13]
- App Standby Buckets directly affect jobs and alarms, and Android 16 changes job runtime quotas further. [R14]
- `UsageStatsManager.getAppStandbyBucket()` and adb standby-bucket commands can be used for diagnosis/testing. [R14]
- Build-managed devices are an official, Gradle-owned path for reproducible instrumented tests. [R15]
- Room migrations should be explicit and migration tests should run on a device; `MigrationTestHelper` exists for that purpose. [R16]
- App-specific internal storage and Room are the reliable private storage choices; SAF is the right import path for user-chosen skill archives/files. [R17][R18]
- Baseline Profiles typically improve first-launch runtime by about 30% and also smooth navigation/scrolling. [R19]
- WorkManager provides worker test builders and diagnostics hooks; do not treat workers as untestable. [R20]

---

## Release Target for v0

A v0 release is complete when a new user can do the following with a single install:

1. install the app
2. open the main session
3. send and persist messages
4. configure either `FakeProvider` or one real provider
5. create additional sessions and switch between them
6. create a `once`, `interval`, or `cron` task
7. use `Run now`
8. see run history, next run, and failure reason
9. import or enable a skill
10. use a slash-invoked skill
11. inspect scheduler/permission health
12. restart the app and find all of the above still intact

### v0 must not depend on

- browser automation
- remote desktop pairing
- Telegram/Discord/Gmail/Slack/WhatsApp
- arbitrary shell access
- cloud sync
- continuous voice/camera/screen workflows
- plugin hot-loading
- ClawHub sync
- generalized secret vaults

---

## Architecture Invariants for the Remaining Work

### Layering

Keep the existing package-level layering:

- `app/` — app bootstrap and container
- `feature/` — screen-specific UI + ViewModels
- `data/` — Room, repositories, persistent models
- `runtime/` — providers, orchestrator, scheduler, skills, tools
- `ui/` — navigation/theme/shared UI
- `platform/` — add only if Android wrappers become necessary

Rules:

- UI never touches DAO directly.
- Runtime never depends on Compose.
- Room entities do not leak into Composables.
- ViewModels consume repositories/runtime services, not DAOs.
- Android SDK wrapping belongs in `platform/` or leaf runtime classes, not in parser/model code.

### Storage layout

Use app-private storage unless there is a real sharing need. Android’s own storage guidance recommends internal/app-specific storage for private app data and Room for structured data. [R17]

Planned layout:

- `files/skills/local/<skill-id>/`
- `files/workspaces/<session-id>/skills/<skill-id>/`
- `files/workspaces/<session-id>/context/`
- `files/imports/`
- `cache/runs/<task-run-id>/` for ephemeral isolated-run artifacts
- Room DB for durable structured state
- DataStore for small settings
- minimal secret store for provider API key(s)

Use SAF to select import files; do not request broad storage permissions for skill import. [R18]

### Scheduler design invariant

All schedule kinds are normalized to:

- durable DB state
- one next due time
- one unique pending work item or one exact alarm (when justified)
- explicit run history

Do not switch to a daemon mindset. WorkManager is the durable engine; exact alarms are a narrow enhancement path. [R9][R10][R11][R12]

### Skill design invariant

AndroidClaw compatibility is with **OpenClaw runtime skills**, not NanoClaw’s Claude-Code-driven code-mod skills.

The core AndroidClaw skill surface is:

- `SKILL.md`
- frontmatter + body
- precedence
- enable/disable
- slash invocation
- eligibility reporting
- optional tool dispatch
- import into app-private storage

### Session concurrency invariant

Interactive chat and scheduled runs must not write to the same session concurrently. OpenClaw’s session-lane serialization exists for a reason. AndroidClaw should implement the smallest sufficient equivalent: a per-session mutex or lane coordinator inside the runtime. [R22]

---

## Error Model and Observability Requirements

The repository already started moving toward structured failures. Continue in that direction.

### Stable error-code families

Use these families consistently:

- `PROVIDER_*`
  - `PROVIDER_NOT_CONFIGURED`
  - `PROVIDER_HTTP_ERROR`
  - `PROVIDER_TIMEOUT`
  - `PROVIDER_RESPONSE_INVALID`
  - `PROVIDER_AUTH_FAILED`

- `TOOL_*`
  - `TOOL_UNKNOWN`
  - `TOOL_PERMISSION_REQUIRED`
  - `TOOL_FOREGROUND_REQUIRED`
  - `TOOL_UNAVAILABLE`
  - `TOOL_EXECUTION_FAILED`

- `SKILL_*`
  - `SKILL_INVALID`
  - `SKILL_MISSING_TOOL`
  - `SKILL_BRIDGE_ONLY`
  - `SKILL_IMPORT_FAILED`
  - `SKILL_CONFLICT`

- `TASK_*`
  - `TASK_NOT_FOUND`
  - `TASK_DISABLED`
  - `TASK_NOT_DUE`
  - `TASK_RUN_FAILED`
  - `TASK_DELIVERY_FAILED`
  - `TASK_FOREGROUND_REQUIRED`
  - `TASK_EXACT_ALARM_DENIED`

- `RUNTIME_*`
  - `RUNTIME_BUSY`
  - `RUNTIME_LANE_CONFLICT`
  - `RUNTIME_PERSIST_FAILED`

### Event log taxonomy

Use `EventLogEntity.category` consistently:

- `provider`
- `tool`
- `skill`
- `scheduler`
- `task-run`
- `runtime`
- `settings`
- `permission`
- `migration`

Do not spam success logs. Log:

- configuration changes
- failures
- degraded mode entries
- scheduler reschedule events
- exact-alarm permission changes
- worker stop reasons where available on supported APIs [R14]

### UI failure handling

The UI must never wedge because of:

- provider failure
- tool failure
- malformed skill
- exact-alarm denial
- unavailable foreground-only capability
- missing permission

Every such case must produce one of:

- persistent system message in chat
- task run failure record
- health log record
- inline actionable explanation

---

## How Codex Should Work Against This Plan

1. Do not ask for “next steps” if a milestone below clearly names them.
2. Pick the next incomplete milestone or sub-step.
3. Make the smallest diff that leaves the app green.
4. Run the smallest relevant validation immediately.
5. Update **Progress**, **Discoveries**, **Decision Log**, and **Blockers** before stopping.
6. Do not refactor unrelated code unless the milestone explicitly requires it.
7. If the worktree is dirty with unrelated changes, leave them untouched unless the milestone truly requires editing the same file.
8. If blocked, write down the blocker and take the next unblocked task from the same milestone where possible.

### Validation rhythm

Minimum commands before declaring a milestone complete:

- `./gradlew :app:assembleDebug`
- `./gradlew :app:testDebugUnitTest`
- `./gradlew :app:lintDebug`

When instrumented tests exist for the touched area, also run the managed-device or device-backed instrumentation task.

When baseline profiles are introduced, also run the profile generation/verification task for profile-affecting changes. [R19]

---

## Resolved Design Choices

These choices are settled unless new evidence forces a change.

### Real provider strategy

Implement exactly one real network provider first:

- `OpenAiCompatibleProvider`
- non-streaming first
- `FakeProvider` remains default for tests/debug
- no vendor-specific SDKs in v0
- use OkHttp + kotlinx serialization

Rationale: small dependency surface, testability, portability to multiple compatible backends.

### Secret strategy

Do **not** design a generalized secret system yet.

Implement only what is needed for one real provider path:

- one minimal secret store abstraction
- one provider API-key slot (or a very small map keyed by provider id)
- back it with Android Keystore or a tiny Keystore-backed encrypted persistence seam
- keep non-secret settings in DataStore

This is the smallest responsible path and aligns with current repo direction. [R17]

### Skill-content persistence strategy

Do not duplicate full skill markdown into the database by default.

Use:

- file system for skill content and resources
- Room for index/override/cache metadata
- in-memory cache for loaded session snapshots

This keeps skill updates/imports simple and avoids large DB rows.

### Prompt-assembly strategy

Because tools and skills cost real context budget, follow OpenClaw’s compact-skill-list lesson. [R21]

For v0:

- normal turns: inject compact skill metadata, not every `SKILL.md` body
- slash-invoked skill: inline that skill’s instructions or directly dispatch to its tool path
- do not build a huge “all skills full text” prompt

### Isolated task strategy

For v0 isolated runs:

- use isolated logical context
- use isolated file root
- optionally post only the final summary back to the target session
- keep detailed run state in `TaskRun`
- do not attempt container or OS-level sandboxing

### Web capability strategy

If a lightweight web tool is added, keep it to plain HTTP fetch/search patterns. OpenClaw itself distinguishes lightweight web tools from full browser automation. [R23]

That means:

- no embedded browser automation
- no JS execution
- return structured “unsupported for JS-heavy/login sites” when appropriate

---

## Milestone Order Overview

This plan uses a strictly ordered sequence because the remaining work has real dependencies.

1. **m0-plan-adoption**
2. **m1-validation-harness**
3. **m2-provider-v1**
4. **m3-tools-v1**
5. **m4-scheduler-core**
6. **m5-scheduler-precision-and-diagnostics**
7. **m6-skills-lifecycle**
8. **m7-runtime-turn-loop**
9. **m8-gui-completion**
10. **m9-persistence-hardening**
11. **m10-performance-and-baseline-profiles**
12. **m11-release-candidate**

Do not jump to the release milestone just because the app “basically works.” The current repository still lacks durable automation, a real provider, instrumentation, and migration discipline.

---

# Milestones

## m0-plan-adoption — make the plan canonical

### Goal

Adopt this plan cleanly so Codex does not operate with split truth.

### Ordered steps

1. Choose one of:
   - replace `PLAN.md` with this file, or
   - update `AGENTS.md` to point to `PLANv3.md` as the active plan
2. Preserve `PLANv2.md` as historical reference only.
3. Add a short note near the top of the inactive plan(s) indicating they are archived or superseded.
4. If desired, create:
   - `docs/exec-plans/active/`
   - `docs/exec-plans/completed/`
   and move older plans there later, matching the repository pattern described in OpenAI’s harness article. [R1]
5. Do not leave two files both claiming to be the active plan.

### Validation

No build is required unless touched files affect Gradle or code generation. At minimum:

- markdown files read coherently
- `AGENTS.md` points to the true plan

### Acceptance criteria

- there is one unambiguous active plan
- older plans are clearly historical
- Codex can read the repo and know where the active instructions live

### Not in this milestone

- any code changes unrelated to plan adoption

---

## m1-validation-harness — own the instrumentation path

### Goal

Unblock reproducible instrumentation and smoke testing from the repo itself.

### Why this milestone is first

The current repo already has good JVM coverage, but Android-specific behavior still lacks a repo-owned validation path. The emulator/`adb` mismatch described in the handoff should not become a permanent excuse to skip instrumentation. Official guidance points to build-managed devices for consistent Gradle-owned test environments. [R15]

### Ordered steps

1. Add `app/src/androidTest/java/ai/androidclaw/...`.
2. Add the smallest smoke test:
   - launch `MainActivity`
   - verify the app renders and the top-level navigation shell is present
   - optionally verify the default chat view appears
3. Configure a **Gradle Managed Device** for API 36.
   - one phone profile is enough initially
   - prefer a standard Google/Pixel-like device profile
4. Add documentation comments in Gradle or a short doc note naming the canonical instrumentation command.
5. Add one instrumentation test for Room integration or navigation smoke.
6. Keep current host-side JVM tests; do not replace them.
7. If managed devices are too heavy for every PR in CI, keep them available locally and wire them into a manual/nightly or main-branch-only workflow later.
8. Add a note in the plan about the exact managed-device task name once known.
9. Do not waste time debugging the current split Windows/Linux `adb` setup unless it becomes cheaper than managed-device setup.

### Validation

- existing fast suite:
  - `./gradlew :app:assembleDebug`
  - `./gradlew :app:testDebugUnitTest`
  - `./gradlew :app:lintDebug`
- new device-backed path:
  - managed-device instrumentation task, or
  - `:app:connectedDebugAndroidTest` if a repo-owned device path is now reliable

### Acceptance criteria

- the repo contains at least one runnable instrumented smoke test
- the test runs on a Gradle-owned or otherwise reproducible device path
- instrumentation no longer depends on a manually coordinated LDPlayer/dual-`adb` setup

### Not in this milestone

- macrobenchmarks
- baseline profile generation
- broad UI test suites

---

## m2-provider-v1 — real provider contract and minimal settings

### Goal

Move from `FakeProvider`-only to a stable provider abstraction with one real network provider path.

### Why now

Everything else becomes more meaningful once the app can run against a real backend while still keeping `FakeProvider` for deterministic development and tests.

### Ordered steps

1. Audit current provider types:
   - `ModelRequest`
   - `ModelResponse`
   - `ProviderRegistry`
   - `SettingsDataStore`
2. Replace the current overly-thin provider contract with one that can support:
   - session id
   - conversation history slice
   - system prompt text
   - enabled skill metadata
   - tool descriptors
   - run mode (`interactive` vs `scheduled`)
   - optional correlation id / request id
3. Keep streaming **out of scope** for this milestone. Non-streaming first.
4. Introduce a typed provider settings model:
   - provider type
   - base URL
   - model id
   - timeout
   - optional extra headers if truly needed
5. Add a minimal secret seam for API keys.
   - non-secret settings stay in DataStore
   - secret is stored outside plain-text DataStore
   - keep this narrowly scoped; one provider secret is sufficient for v0
6. Implement `OpenAiCompatibleProvider` with:
   - explicit request DTOs
   - explicit response DTOs
   - timeout handling
   - structured non-2xx handling
   - malformed JSON handling
   - structured auth/config errors
7. Keep `FakeProvider` as:
   - the default in tests
   - a selectable local development mode
8. Ensure provider failure never wedges the UI:
   - persist a system error message to chat
   - log an event
   - return clean state to the ViewModel
9. Add tests using a local mock HTTP server or equivalent deterministic stub.
10. Update the Settings screen/ViewModel so a human can actually configure and switch between `fake` and `openai-compatible`.

### Validation

- unit tests for:
  - settings mapping
  - provider DTO parsing
  - timeout/error handling
  - missing-secret path
- standard fast suite:
  - `./gradlew :app:assembleDebug`
  - `./gradlew :app:testDebugUnitTest`
  - `./gradlew :app:lintDebug`
- manual smoke:
  - fake provider still works
  - real provider can be configured and exercised against a test endpoint or real endpoint

### Acceptance criteria

- the app supports at least `FakeProvider` and one real provider
- provider settings persist across restart
- API key is not stored in plain-text DataStore
- provider/network failures produce structured user-visible degradation, not a wedged chat screen

### Not in this milestone

- streaming responses
- multimodal inputs
- multi-provider orchestration
- vendor SDKs

---

## m3-tools-v1 — first useful typed tools

### Goal

Upgrade the current demo tool registry into a usable typed tool system.

### Why now

Skills and the scheduler both need a durable tool contract. OpenClaw’s direction is clear: typed tools, not shelling. [R5]

### Ordered steps

1. Expand `ToolDescriptor` to include:
   - canonical name
   - optional aliases
   - short description
   - foreground requirement
   - permission requirement metadata
   - availability state
2. Add a simple validation/argument layer.
   - Keep it lightweight.
   - Do not add a large JSON-schema dependency unless it becomes clearly necessary.
3. Add alias support so AndroidClaw can expose stable canonical names while still accepting common compatibility names where useful.
4. Define a stable `ToolExecutionResult` shape:
   - `success`
   - `summary`
   - structured payload
   - structured error code when failed
5. Implement a first useful tool set:
   - `health.status`
   - `sessions.list`
   - `tasks.list`
   - `skills.list`
   - `notifications.post`
   - one lightweight HTTP fetch tool if scope allows
6. If adding HTTP fetch:
   - keep it plain HTTP
   - no JS execution
   - return structured unsupported notes for JS-heavy/login-dependent pages
7. Add availability reporting:
   - unavailable
   - permission required
   - foreground required
   - disabled by config
8. Update skill eligibility to use richer tool availability, not just “name exists”.
9. Add tests for:
   - unknown tool
   - handler exception
   - foreground-required state
   - permission-blocked state
   - alias resolution
10. Keep tools deterministic and small. No shell execution.

### Validation

- JVM tests for tool registry and built-in tools
- manual slash-tool skill flow using bundled demo skills
- standard fast suite

### Acceptance criteria

- the tool registry exposes more than demo placeholders
- tools fail structurally, not by throwing to UI
- skill eligibility can explain missing or blocked tools
- at least one slash-dispatched skill executes through the upgraded tool system

### Not in this milestone

- camera
- microphone
- location
- screen capture
- browser automation
- arbitrary file-system access outside app-private scope

---

## m4-scheduler-core — durable task execution

### Goal

Turn the scheduler from preview semantics into real durable execution.

### Why now

This is one of the user’s most important compatibility goals, and it is still effectively missing.

### Ordered steps

1. Introduce a scheduler service API around the current repository layer:
   - `scheduleTask(taskId)`
   - `cancelTask(taskId)`
   - `rescheduleAll()`
   - `runNow(taskId)`
2. Keep **OneTimeWorkRequest** as the canonical work primitive for all schedule kinds. [R9][R10]
3. Build a `TaskPlanner` / `TaskRescheduler` that:
   - calculates next due time for `once`
   - calculates next due time for `interval`
   - calculates next due time for `cron`
   - chooses retry/backoff behavior after failure
4. Use unique work names per task, for example:
   - `task-next:<taskId>`
   - `task-run-now:<taskId>`
5. Implement `TaskExecutionWorker` for real:
   - load task from DB
   - create a `TaskRun` record
   - transition pending -> running -> success/failure/skipped
   - call runtime execution
   - persist output summary
   - reschedule the next occurrence if task remains enabled
6. Make `Run now` real:
   - do not mutate the schedule definition
   - do create a distinct `TaskRun`
   - do keep the task’s future schedule intact
7. Add lightweight retry/backoff policy.
   - WorkManager supports retry/backoff; use it judiciously. [R10]
   - Keep retry state visible in DB/UI
8. Implement both execution modes:
   - `MAIN_SESSION`
   - `ISOLATED_SESSION`
9. Define isolated-run semantics for v0:
   - separate logical context
   - separate file root
   - detailed run stays in `TaskRun`
   - optional final summary is delivered to target session
10. Make task execution idempotent enough to tolerate process death/retry.
11. Add worker tests using WorkManager’s test builders. [R20]
12. Add at least one instrumentation smoke that creates a task and verifies durable DB effects.

### Validation

- unit tests for planner/backoff
- worker tests with `TestWorkerBuilder` or equivalent [R20]
- instrumentation smoke on managed device
- standard fast suite

### Acceptance criteria

- `Run now` works
- `once`, `interval`, and `cron` all execute through real workers
- each run creates durable history
- recurring tasks reschedule themselves
- process death no longer erases pending tasks

### Not in this milestone

- exact alarms
- boot-time exact-alarm restoration
- advanced scheduler diagnostics UI polish

---

## m5-scheduler-precision-and-diagnostics — exact alarms, standby awareness, and health

### Goal

Add the narrow exact-alarm path and the diagnostics needed to debug Android background behavior honestly.

### Why after core scheduler

WorkManager-first behavior should work before exact-alarm complexity is added. Exact alarms are a constrained enhancement path, not the base system. [R11][R12]

### Ordered steps

1. Define two precision modes in code:
   - approximate
   - precise user-visible
2. Map current `precise: Boolean` to that concept or migrate to a better explicit enum if needed.
3. Only allow precise scheduling when:
   - the task is genuinely user-visible
   - the capability does not require forbidden background foreground-service starts
4. Add exact-alarm capability checks with `canScheduleExactAlarms()`. [R11]
5. Add a receiver/listener path for exact-alarm permission changes and reschedule affected tasks accordingly. [R11]
6. For exact alarms:
   - schedule only the **next** occurrence
   - receiver should do very little
   - receiver should enqueue actual work execution and return quickly
7. Add scheduler diagnostics surface:
   - supports exact alarms?
   - exact alarm granted?
   - current standby bucket if available
   - last worker stop reason where available
   - next run preview
8. Log standby bucket and stop-reason information when tasks execute or are stopped on supported APIs. Android 16 explicitly recommends logging stop reasons to debug quota behavior. [R14]
9. Make the Tasks and Health screens show degraded modes clearly:
   - “Exact alarm permission denied; falling back to approximate”
   - “Foreground required”
   - “App is in restricted bucket”
10. Add adb-based manual QA instructions to the repo:
    - set standby bucket
    - inspect jobscheduler
    - request WorkManager diagnostics [R14][R20]

### Validation

- standard fast suite
- instrumentation/manual validation of exact-alarm permission degradation path
- adb/manual diagnostics on a debug device or managed device where possible

### Acceptance criteria

- approximate scheduling remains the default path
- precise scheduling is explicit and guarded
- exact-alarm denial degrades cleanly
- health/task screens expose enough diagnostics to understand why a job did not run on time

### Not in this milestone

- alarm-clock app privileges
- unsupported attempts to bypass OEM/background policy
- background camera/microphone/location task execution

---

## m6-skills-lifecycle — import, precedence, persistence, and enable/disable

### Goal

Upgrade skills from bundled-read-only parsing to a real lifecycle.

### Why now

Once provider, tools, and scheduler are real, skills become the main compatibility differentiator.

### Ordered steps

1. Introduce a `SkillSourceScanner` or equivalent that resolves skills from:
   - bundled assets
   - local imported skills
   - workspace/session skills
2. Preserve OpenClaw precedence:
   - workspace > local > bundled [R4]
3. Keep local imported skills in app-private storage:
   - `files/skills/local/<skill-id>/`
4. Keep workspace skills in:
   - `files/workspaces/<session-id>/skills/<skill-id>/`
5. Use SAF import, not broad storage permissions. [R18]
6. Support at least:
   - import from `.zip`
   - import from a chosen `SKILL.md` file or a directory selection if practical
7. Sanitize zip extraction paths.
   - Even though security is not the top priority, path traversal bugs are correctness bugs.
8. Extend `SkillRecordEntity` only as needed to persist:
   - source type
   - enabled state
   - display metadata
   - eligibility summary
   - import/update timestamps
   - optional file hash / path
9. Preserve and display OpenClaw-like compatibility semantics where feasible:
   - `metadata.openclaw.skillKey`
   - `requires.config`
   - `requires.env`
   - missing tool
   - bridge-only / unsupported locally [R4]
10. Keep local Android implementation intentionally narrower than desktop OpenClaw:
    - no binary installers
    - no plugin-shipped skills
    - no shell-based dependency resolution
11. Add manual refresh/invalidate behavior.
    - watcher/hot reload can wait
12. Add session-level skill snapshot caching to avoid reparsing everything every turn; OpenClaw’s docs explicitly highlight the context cost of tools/skills and the value of snapshots. [R21]
13. Update the Skills screen so the user can:
    - refresh
    - inspect details
    - enable/disable
    - see source and precedence
    - import a new skill
    - see why a skill is ineligible

### Validation

- parser and precedence JVM tests
- import/extraction tests
- manual import smoke
- standard fast suite

### Acceptance criteria

- local and workspace skills can coexist with bundled skills
- precedence works correctly
- enable/disable survives restart
- slash invocation works for imported eligible skills
- ineligible skills explain why they cannot run locally

### Not in this milestone

- ClawHub sync
- plugin skills
- automatic file watchers
- desktop binary installation

---

## m7-runtime-turn-loop — bounded orchestration and session-lane safety

### Goal

Make the agent runtime more faithful to the intended contracts without becoming overly complex.

### Why now

By this point the app will have a real provider, real tools, durable tasks, and real skills. The turn loop must unify them.

### Ordered steps

1. Expand `AgentRunner` into explicit phases:
   - load session
   - resolve skill snapshot
   - assemble prompt
   - call provider
   - handle tool calls or direct slash-tool dispatch
   - persist outputs
2. Add a per-session lane/mutex coordinator.
   - Interactive chat and scheduled task delivery to the same session must not race. [R22]
3. Keep the runtime bounded:
   - small maximum tool-call loop count
   - explicit timeout
   - structured loop exit reason
4. Improve `ModelRequest` assembly to include:
   - compact skill metadata
   - recent conversation slice
   - optional session summary
   - available tool metadata
   - run mode
5. Keep normal-turn prompt injection small:
   - skill metadata by default
   - full instructions for slash-invoked or explicitly selected skills only
6. Add a minimal `PromptAssembler` class so prompt construction is testable instead of hidden in the provider call site.
7. Keep slash-command behavior explicit:
   - slash-only messages can bypass the model when `command-dispatch: tool`
   - slash skill should still validate eligibility before execution
8. Improve scheduled-run execution path:
   - main-session mode writes directly into the target session
   - isolated mode uses a temporary context and then optionally posts a summary to target session
9. Add event logging for:
   - lane busy
   - runtime timeout
   - tool-call loop exhaustion
   - scheduled delivery failures

### Validation

- JVM tests for:
  - slash parsing
  - prompt assembly
  - session-lane serialization
  - tool-call loop bounds
- manual smoke with:
  - interactive chat
  - slash-invoked tool skill
  - scheduled isolated run
- standard fast suite

### Acceptance criteria

- session transcript writes are serialized per session
- slash skills behave deterministically
- normal turns use a testable prompt assembler
- scheduled and interactive execution share one coherent runtime path

### Not in this milestone

- sub-agents
- streaming tool-call orchestration
- remote bridge execution

---

## m8-gui-completion — every screen becomes a real control surface

### Goal

Make the GUI complete enough that a human can manage the whole v0 app without repo knowledge.

### Ordered steps

1. **Chat screen**
   - session list / switcher
   - create session
   - rename/archive session
   - input box
   - visible assistant/system/error messages
   - send-state handling
   - slash skill affordances or suggestions
2. **Tasks screen**
   - list tasks
   - create task form
   - `once`, `interval`, `cron`
   - precise vs approximate
   - main vs isolated
   - enable/disable
   - `Run now`
   - delete
   - recent run history
   - exact-alarm capability and degradation banners
3. **Skills screen**
   - list sources
   - enable/disable
   - import action
   - refresh action
   - parse/eligibility details
   - precedence explanation
4. **Settings screen**
   - fake vs real provider
   - base URL
   - model id
   - API key entry/update
   - timeouts
   - simple save/apply UX
5. **Health screen**
   - provider id
   - available tools
   - exact-alarm support/grant
   - standby bucket if available
   - recent event logs
   - quick debug hints
6. Reduce placeholder text to near zero. If a feature is still deferred, say exactly what is missing and why.
7. Keep visuals simple:
   - lazy lists
   - minimal chrome
   - no heavy animations or blurred surfaces

### Validation

- instrumentation smoke should cover at least one end-to-end user path through the app
- standard fast suite
- manual walkthrough of all five screens

### Acceptance criteria

- every top-level destination is functional
- there are no “fake” management screens left for core v0 features
- a new tester can discover how to configure provider, tasks, and skills without reading code

### Not in this milestone

- marketing polish
- custom design system
- advanced theming
- non-essential animations

---

## m9-persistence-hardening — schema migration discipline and reboot robustness

### Goal

Turn the current development-friendly persistence posture into a release-worthy one.

### Why this is a dedicated milestone

The repository still uses `fallbackToDestructiveMigration()` in Room v1. That is acceptable for bootstrap but not acceptable once real users may store sessions, tasks, and imported skills. Room explicitly supports incremental migrations, and migration testing on device is recommended. [R16]

### Ordered steps

1. Audit whether upcoming scheduler/skills/runtime work requires schema changes.
2. If schema changes are needed, bump DB version and add explicit migrations.
3. Remove `fallbackToDestructiveMigration()` before the first external RC/beta.
4. Add `MigrationTestHelper`-based instrumentation tests using exported schemas. [R16]
5. Ensure all important persistent user data is recoverable across:
   - app restart
   - process death
   - app upgrade across schema versions
6. Review task reschedule behavior after startup.
7. If exact alarms are supported, add boot/resume restoration for exact-alarm tasks where necessary.
8. Add maintenance logic for trimming:
   - old task-run logs
   - old event logs
   - ephemeral isolated-run artifacts
9. Do not overbuild retention policy. Keep one simple pruning policy with documented constants.

### Validation

- migration instrumentation tests
- managed-device smoke on upgrade path if possible
- standard fast suite

### Acceptance criteria

- destructive fallback is gone before release
- migrations are explicit and tested
- task/session/skill/user settings survive app upgrades

### Not in this milestone

- multi-account sync
- cloud backup orchestration
- encrypted database

---

## m10-performance-and-baseline-profiles — lock in the lightweight promise

### Goal

Translate the “lightweight and fluid” product goal into concrete measurements and code paths.

### Why now

Performance work is most effective once the main user journeys exist. Android’s guidance shows Baseline Profiles provide meaningful first-launch and interaction improvements, especially relevant for a small Compose app targeting ordinary devices. [R19]

### Ordered steps

1. Audit startup path:
   - no eager full-table loads on launch
   - no reparsing all skills on startup
   - no blocking network on startup
2. Audit memory posture:
   - bounded in-memory caches
   - lazy history loading
   - lazy run-history loading
   - no permanent foreground service
3. Add a baseline-profile module or generator path.
   - Keep it test-only.
   - Do not split production code into more modules just for this.
4. Generate a baseline profile covering:
   - app launch
   - opening chat
   - opening tasks
   - opening skills
   - opening settings/health
   - sending one message
5. Add simple before/after measurement notes or benchmark evidence in the repo.
6. Keep package size honest:
   - avoid large new dependencies
   - avoid feature creep
7. If useful, add one startup macrobenchmark or a minimal benchmark harness later.

### Validation

- baseline profile generation task
- profile verification task if configured [R19]
- standard fast suite
- manual cold-start smoke on at least one representative device/emulator

### Acceptance criteria

- a baseline profile is checked into the repo or generated by the repo workflow
- startup-critical and first-interaction paths are covered
- no obvious startup regressions from eager loading or unnecessary heavy dependencies

### Not in this milestone

- deep GPU animation optimization
- multi-module performance tuning
- speculative premature micro-optimizations

---

## m11-release-candidate — prove the product works end to end

### Goal

Freeze scope for v0 and prove the release story with repeatable evidence.

### Ordered steps

1. Confirm the release target flows listed above all pass.
2. Ensure the canonical validation matrix is documented and runnable:
   - assemble
   - unit tests
   - lint
   - instrumentation smoke
   - baseline profile generation/verification if present
3. Add a short manual QA checklist to the repo.
4. Run manual QA for:
   - fake provider
   - real provider
   - main session persistence
   - create/switch sessions
   - create tasks
   - run now
   - skill import/enable/disable
   - slash skill invoke
   - health diagnostics
5. Test degraded cases:
   - exact alarm denied
   - provider auth failure
   - network timeout
   - ineligible skill
   - task foreground-required failure
6. Build release artifacts:
   - release APK for side-load testing
   - AAB if distribution path needs it
7. Record known limitations explicitly.
   - OEM background variance
   - no browser automation
   - no external channels
   - no remote bridge
8. Tag the release candidate scope in the plan and stop adding new feature categories.

### Validation

Minimum release gate:

- `./gradlew :app:assembleRelease`
- `./gradlew :app:assembleDebug`
- `./gradlew :app:testDebugUnitTest`
- `./gradlew :app:lintDebug`
- managed-device instrumentation smoke
- baseline profile generation/verification if configured

### Acceptance criteria

- all v0 target flows pass
- known degraded behaviors are user-visible and understandable
- no destructive DB fallback remains
- release artifact can be produced cleanly

### Not in this milestone

- external-channel integrations
- remote bridge
- plugin ecosystem
- voice/camera/screen feature expansion
- feature-module architecture

---

# Cross-Cutting Implementation Notes

## A. Database evolution guidance

If a milestone forces schema changes, prefer the smallest compatible change set.

### Likely next schema additions

Only add these if needed by shipped behavior:

- `TaskRun.triggerKind`
- `TaskRun.attempt`
- `TaskRun.stopReason`
- `TaskRun.workRequestId`
- optional cached skill path/hash fields
- optional last task delivery summary fields

Avoid speculative tables.

## B. Task retry policy

OpenClaw’s current CLI docs describe recurring cron retry backoff after consecutive errors (30s → 1m → 5m → 15m → 60m) before returning to the normal schedule after success. [R7]

AndroidClaw does not need to clone that exactly, but it should adopt a similarly simple bounded policy:

- first failure: 30 seconds
- then 1 minute
- then 5 minutes
- then 15 minutes
- then cap at 60 minutes

Use WorkManager’s retry/backoff support where possible, but keep the resulting user-visible behavior deterministic and record it in DB/UI. [R10]

## C. Standby/OEM testing strategy

Because OEM behavior is variable, do not promise perfect zero-config punctuality on every device.

Instead:

- test with adb standby-bucket commands where possible [R14]
- expose standby/permission/diagnostic state in-app
- prefer user-visible degradation messages over silent misses
- add a short post-v0 doc or help note for aggressive OEM battery settings if real devices prove it necessary

## D. Skill import strategy

For v0, support imports that are easy to reason about:

- single-skill zip with one root folder
- direct `SKILL.md` file import with generated folder id

Do not block the whole project on perfect archive UX.

## E. Provider test strategy

Every provider change must be verifiable without a real API key by using:

- `FakeProvider`
- mock HTTP server tests
- deterministic failure fixtures

## F. Worker test strategy

Every non-trivial worker change must ship with:

- host-side worker logic test using WorkManager test builders [R20]
- one higher-fidelity instrumentation smoke if the behavior touches Android integration

## G. Prompt budget discipline

Prompt context is a finite resource. OpenClaw explicitly documents that tools and skills both cost context tokens. [R21]

Practical implications for AndroidClaw:

- do not inline all skill bodies on every turn
- keep tool descriptors concise
- prefer summaries and selected context
- preserve a summary slot in sessions even if automatic compaction is deferred

---

# Immediate Next Actions (the first three concrete work packets)

If Codex starts from this plan with no further human steering, do these next:

## Packet 1
Adopt this plan cleanly:
- make `PLANv3.md` the canonical plan or point `AGENTS.md` to it
- archive/supersede older plan references

## Packet 2
Unblock instrumentation:
- add `androidTest`
- add one smoke test
- configure one Gradle Managed Device
- document the canonical instrumentation command

## Packet 3
Start provider v1:
- harden provider/request models
- add minimal secret seam
- implement one real network provider with deterministic tests
- wire settings UI for provider selection/config

Do **not** start browser tools, external channels, or remote bridge work before these three packets are complete.

---

# Canonical Validation Commands

Keep this list updated as tasks are added.

## Fast local suite

```bash
./gradlew :app:assembleDebug
./gradlew :app:testDebugUnitTest
./gradlew :app:lintDebug
```

## Instrumentation

Replace with the exact task name once managed devices are configured:

```bash
./gradlew :app:connectedDebugAndroidTest
# or the managed-device task name that becomes canonical
```

## Release / performance

Add as soon as relevant:

```bash
./gradlew :app:assembleRelease
# baseline profile generation / verification task(s)
```

---

# Definition of Done for Each Milestone

A milestone is only done when all of the following are true:

1. The code compiles.
2. Relevant tests exist and pass.
3. Fast suite is green.
4. Device/instrumentation validation exists where the feature truly needs it.
5. `Progress` is updated.
6. Any new discoveries or trade-offs are recorded.
7. User-visible behavior matches the acceptance criteria.
8. There is no known red test or lint error being ignored “for later”.

---

# Deferred Work (explicitly not for v0)

Keep these out unless the user changes the brief:

- remote bridge mode
- Telegram / Discord / Gmail / Slack / WhatsApp
- browser automation / Chromium
- arbitrary shell execution
- plugin system
- ClawHub sync
- voice wake-word / continuous microphone
- background camera/screen/location workflows
- cloud sync
- enterprise security hardening
- full sandboxing

---

# References

- **[R1]** OpenAI, *Harness engineering: leveraging Codex in an agent-first world* — repository-local docs as system of record, short `AGENTS.md`, versioned execution plans.  
  <https://openai.com/index/harness-engineering/>

- **[R2]** OpenClaw Docs, *Android App* — Android is a companion node app; Android does not host the Gateway.  
  <https://docs.openclaw.ai/platforms/android>

- **[R3]** OpenClaw Docs, *Skills* — each skill is a directory containing `SKILL.md` with YAML frontmatter and instructions.  
  <https://docs.openclaw.ai/tools/skills>

- **[R4]** OpenClaw Docs, *Skills* and *Skills Config* — precedence is workspace > managed/local > bundled; `skillKey`, `env`, `apiKey`, and config semantics.  
  <https://docs.openclaw.ai/tools/skills>  
  <https://docs.openclaw.ai/tools/skills-config>

- **[R5]** OpenClaw Docs, *Tools* — tools are first-class, typed, and replace shell-first skill patterns.  
  <https://docs.openclaw.ai/tools>

- **[R6]** OpenClaw Docs, *Cron Jobs* — cron persists jobs, wakes the agent, and can deliver output back to chat.  
  <https://docs.openclaw.ai/automation/cron-jobs>

- **[R7]** OpenClaw Docs, *CLI `cron`* — isolated-job delivery defaults and current retry backoff behavior.  
  <https://docs.openclaw.ai/cli/cron>

- **[R8]** NanoClaw GitHub README — main channel, isolated group context, scheduled tasks, container isolation.  
  <https://github.com/qwibitai/nanoclaw/blob/main/README.md>

- **[R9]** Android Developers, *Task scheduling / persistent background work* — WorkManager is recommended for work that must persist across restarts/reboots.  
  <https://developer.android.com/develop/background-work/background-tasks/persistent>

- **[R10]** Android Developers, *Define work requests* and WorkManager docs — unique work, input data, retry, backoff, and tags.  
  <https://developer.android.com/develop/background-work/background-tasks/persistent/getting-started/define-work>  
  <https://developer.android.com/develop/background-work/background-tasks/persistent>

- **[R11]** Android Developers, *Schedule exact alarms are denied by default* — exact alarms denied by default for many new installs; use `canScheduleExactAlarms()` and permission-state handling.  
  <https://developer.android.com/about/versions/14/changes/schedule-exact-alarms>

- **[R12]** Android Developers, *Schedule alarms* — exact alarms are critical, time-sensitive interruptions and should be used narrowly.  
  <https://developer.android.com/develop/background-work/services/alarms>

- **[R13]** Android Developers, *Foreground service types are required* — while-in-use restrictions for camera, location, and microphone foreground services.  
  <https://developer.android.com/about/versions/14/changes/fgs-types-required>

- **[R14]** Android Developers, *App Standby Buckets* and Android 16 behavior changes — jobs/alarms are constrained by buckets; Android 16 changes quota behavior; diagnostics via standby bucket and stop reasons matter.  
  <https://developer.android.com/topic/performance/appstandby>  
  <https://developer.android.com/about/versions/16/behavior-changes-all>

- **[R15]** Android Developers, *Scale your tests with build-managed devices* — managed devices improve consistency and reliability for instrumented tests.  
  <https://developer.android.com/studio/test/managed-devices>

- **[R16]** Android Developers, *Migrate your Room database* and *Test and debug your database* — explicit migrations and device-backed migration testing guidance.  
  <https://developer.android.com/training/data-storage/room/migrating-db-versions>  
  <https://developer.android.com/training/data-storage/room/testing-db>

- **[R17]** Android Developers, *Data and file storage overview* — internal app-specific storage, preferences, and Room are the right private-data defaults.  
  <https://developer.android.com/training/data-storage>

- **[R18]** Android Developers, *Open files using the Storage Access Framework* — SAF is the correct system-picker import path for user-selected files.  
  <https://developer.android.com/guide/topics/providers/document-provider>

- **[R19]** Android Developers, *Baseline Profiles overview* and Compose baseline profile guidance — first-launch and runtime improvements, roughly 30% for code execution from first launch.  
  <https://developer.android.com/topic/performance/baselineprofiles/overview>  
  <https://developer.android.com/develop/ui/compose/performance/baseline-profiles>

- **[R20]** Android Developers, *Testing Worker implementation* and *Debug WorkManager* — worker test builders and diagnostics exist and should be used.  
  <https://developer.android.com/develop/background-work/background-tasks/testing/persistent/worker-impl>  
  <https://developer.android.com/develop/background-work/background-tasks/testing/persistent/debug>

- **[R21]** OpenClaw Docs, *System Prompt* and *Context* — OpenClaw injects compact skill metadata because skills/tools have real context-window cost.  
  <https://docs.openclaw.ai/concepts/system-prompt>  
  <https://docs.openclaw.ai/concepts/context>

- **[R22]** OpenClaw Docs, *Agent Loop* — runs are serialized per session lane and skills are loaded from snapshots in the session/workspace preparation phase.  
  <https://docs.openclaw.ai/concepts/agent-loop>

- **[R23]** OpenClaw Docs, *Web Tools* — lightweight web search/fetch are distinct from full browser automation.  
  <https://docs.openclaw.ai/tools/web>
