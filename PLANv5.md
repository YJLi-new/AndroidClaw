
# AndroidClaw Execution Plan v5

> Historical plan. Superseded by `PLANv6.md` on 2026-03-16.
>
> Keep this file for execution history only. `PLANv6.md` is the active plan.

---

## 1. Why this plan exists

AndroidClaw is no longer a greenfield prototype and no longer primarily blocked on “can this architecture work on Android?”. The latest repository snapshot already contains:

- a single-module Kotlin-first Android app
- Compose UI for chat, tasks, skills, settings, and health
- Room persistence with exported schemas and tested migrations
- a scheduler with `once`, `interval`, and `cron`
- exact-alarm degradation and diagnostics
- bundled / local / workspace `SKILL.md` loading
- a deterministic `FakeProvider`
- an OpenAI-compatible provider path
- Windows-emulator and Android instrumentation harnesses
- README, architecture docs, scheduler docs, testing docs, performance docs, release docs, and known-limitations docs

That means the next phase should **not** behave like v1 or v2 planning. The next phase should behave like a **post-RC tightening plan**: finish the highest-leverage contract gaps, make the build/distribution story truthful, reduce size, and turn the repository into something Codex can continue driving with low ambiguity.

`PLANv4.md` did the right thing for the previous phase: it focused on scheduler proof, Windows-emulator migration, persistence hardening, and RC evidence. `PLANv5.md` now shifts the center of gravity to the next set of missing pieces:

1. **tool execution context**, so tools know who called them and under what runtime mode
2. **task tools contract completion**, so automation can be created and managed from the agent runtime, not only from the GUI
3. **an installable optimized build lane**, so we can test shrink/optimization on a real installable artifact
4. **R8/resource shrinking**, because lightweight size is a first-order product goal
5. **CI parity**, so repository automation reflects the new validation truth
6. **skill config surface**, so `docs/SKILLS_COMPAT.md` is no longer ahead of the implementation
7. **beta handoff**, so the app can move from “repo with strong internals” to “artifact other humans can install and test”

This plan is written in an agent-first style on purpose. OpenAI’s harness-engineering writeup explicitly argues for a short `AGENTS.md`, docs as the system of record, and plans as first-class checked-in artifacts with progress and decision logs. That operating model fits AndroidClaw well and should continue to guide the repository. [R1]

---

## 2. Read order and operating rules

Before making any non-trivial code change, read in this order:

1. `AGENTS.md`
2. `README.md`
3. `PLANv5.md`
4. `docs/ARCHITECTURE.md`
5. `docs/SCHEDULER.md`
6. `docs/SKILLS_COMPAT.md`
7. `docs/TESTING.md`
8. `docs/PERFORMANCE.md`
9. `docs/RELEASE_CHECKLIST.md`
10. `docs/KNOWN_LIMITATIONS.md`

Operating rules:

- Treat this file as the active source of truth for sequencing, acceptance criteria, and trade-offs.
- Update the plan before making code changes that materially alter architecture, scope, validation, or risk posture.
- Prefer small diffs and fast validation loops.
- Record discoveries when a fact changes an implementation decision.
- Record decisions when a trade-off becomes intentional rather than accidental.
- Do not silently expand the product surface during this phase.
- If a workstream becomes too large, split it into a child plan under `docs/exec-plans/active/` and link it back here, rather than bloating `AGENTS.md`. This directly follows the “AGENTS as table of contents, docs as system of record” principle from OpenAI’s agent-first guidance. [R1]

---

## 3. Executive summary

### 3.1 Product position for this phase

AndroidClaw remains:

- an **Android-native local host**
- a **single-APK-first product**
- a **Kotlin-first implementation**
- a **behaviorally compatible reinterpretation** of the OpenClaw/NanoClaw ideas that matter most on phone:
  - sessions
  - tools
  - skills
  - scheduled automations

It does **not** become:

- a Node.js port
- a Docker host
- a browser automation shell
- a remote-first companion app
- a cloud sync product
- a chat-channel gateway

OpenClaw’s current public docs still frame Android as a companion node while the Gateway is the core source of truth, and OpenClaw’s modern runtime emphasizes typed tools and `SKILL.md` folders. NanoClaw still lives in the desktop/server world and is explicitly container-oriented. AndroidClaw should therefore continue to be an Android-native host with OpenClaw-like runtime semantics, not a desktop runtime transplant. [R2][R3][R4][R5][R6][R7]

### 3.2 What is already true in the repo

The attached repository already proves these things:

- The single-module approach is viable.
- The core runtime loop is viable.
- The scheduler architecture is viable.
- The skill loader/import flow is viable.
- The FakeProvider-first testing strategy is viable.
- The Windows-emulator fallback path is viable at the repo level.
- The product can already express the central v0 contracts through the UI.

### 3.3 What is still missing or still not fully closed

The most important remaining gaps are:

- tool calls are still effectively **context-free**
- task creation/editing is still effectively **GUI-only**
- the current release lane is **not yet optimized and not yet the artifact we want to beta-test**
- CI still mainly mirrors the **fast loop**, not the **shipping loop**
- the skill compatibility docs promise more config/env semantics than the current UI/runtime actually exposes
- Baseline Profiles are still absent
- several top-level docs still point at `PLANv4.md`, which means repository truth is already beginning to drift

### 3.4 The single most important principle for v5

**Finish leverage before features.**

For this phase, the best work is the work that improves:

- the correctness of tool execution
- the completeness of the task/automation contract
- the installability and size of the shipped artifact
- the truthfulness of validation and documentation

Do **not** spend this phase adding new product categories.

---

## 4. Current repository snapshot (audited from the attached zip)

### 4.1 Build and platform baseline

Current audited baseline from the attached repository:

- module count: one production module (`:app`)
- language/runtime: Kotlin, Java 17 toolchain
- UI: Jetpack Compose
- persistence: Room
- background work: WorkManager + AlarmManager exact path
- networking: OkHttp
- serialization: kotlinx serialization + SnakeYAML
- `compileSdk = 36`
- `targetSdk = 36`
- `minSdk = 26`

This remains aligned with the Android-native, light-dependency goal.

### 4.2 Current top-level repository shape

The current repo already contains:

- `AGENTS.md`
- `README.md`
- `PLANv1.md`
- `PLANv2.md`
- `PLANv3.md`
- `PLANv4.md`
- `docs/ARCHITECTURE.md`
- `docs/SCHEDULER.md`
- `docs/SKILLS_COMPAT.md`
- `docs/TESTING.md`
- `docs/PERFORMANCE.md`
- `docs/RELEASE_CHECKLIST.md`
- `docs/KNOWN_LIMITATIONS.md`
- `docs/qa/*`
- `.github/workflows/android.yml`
- `scripts/*` for Windows-emulator and exact-alarm testing

This is already a good agent-first repository layout. The main plan-level problem is simply that the live pointers still target `PLANv4.md`.

### 4.3 Current runtime surface

The current app/runtime surface includes:

- chat with session persistence
- deterministic fake model provider
- OpenAI-compatible provider path
- tool-call loop in `AgentRunner`
- direct slash-tool dispatch
- bundled/local/workspace skill discovery and precedence
- task storage and run history
- exact-alarm permission handling and degradation
- health diagnostics and recent event logs
- notification tool
- scheduler restore on:
  - `BOOT_COMPLETED`
  - `MY_PACKAGE_REPLACED`
  - `TIME_CHANGED`
  - `TIMEZONE_CHANGED`

### 4.4 Current built-in tools

The audited built-in tool set is intentionally small:

- `tasks.list`
- `health.status`
- `sessions.list`
- `skills.list`
- `notifications.post`

This is a strong minimal baseline, but it also makes the next gap obvious: the agent can inspect tasks, but it cannot yet create/edit/delete/enable/run them through a stable typed tool surface.

### 4.5 Current test surface

The repo currently contains:

- broad JVM coverage across data, repositories, providers, scheduler, skill manager, tool registry, and view models
- Android instrumentation tests for:
  - startup smoke
  - startup maintenance
  - Room migration
  - exact-alarm regression
  - task worker smoke
- a GitHub Actions workflow that currently runs:
  - `:app:assembleDebug`
  - `:app:testDebugUnitTest`
  - `:app:lintDebug`

This matches the current “fast loop first” posture, but not the next-phase packaging/beta posture.

### 4.6 Current validation status from the latest handoff

The latest explicit handoff states that the repo-side validations are green for:

- `:app:testDebugUnitTest`
- `:app:assembleDebug`
- `:app:assembleDebugAndroidTest`
- `:app:lintDebug`

The current repository also contains release and RC documentation from the previous phase, but this v5 phase should **revalidate** packaging from scratch instead of assuming older release evidence remains sufficient after future changes.

### 4.7 Current high-leverage gaps discovered during the v5 audit

1. `AGENTS.md` still tells agents to treat `PLANv4.md` as canonical.
2. `README.md` still links to `PLANv4.md`.
3. Tool handlers do not yet receive an explicit execution context.
4. The task tool surface is incomplete relative to the product thesis.
5. The release artifact path is still documented as an unshrunk unsigned APK.
6. No App Bundle / internal testing lane is documented as part of the core loop.
7. CI does not yet mirror packaging or androidTest compile truth strongly enough for the next phase.
8. `docs/SKILLS_COMPAT.md` is ahead of the runtime on config/env editing.
9. Baseline Profiles are still a documented deferral rather than an implemented optimization.
10. The beta handoff packet needed to be made explicit instead of leaving release/QA/install truth scattered across older RC-era docs.

---

## 5. Product goals for the rest of this phase

### 5.1 What “done” looks like for v5

The phase is done when all of the following are true:

1. `PLANv5.md` is canonical and all top-level pointers are updated.
2. Tool execution has a stable context object passed through the runtime.
3. The agent can create and manage tasks through typed tools, not just inspect them.
4. There is an installable release-like build lane for shrink/optimization/device smoke.
5. R8 and resource shrinking are enabled and measured, or explicitly proven unsafe and reverted with evidence.
6. CI mirrors the new packaging truth, not only the debug fast loop.
7. The skill config surface supports the subset already claimed by docs for v5:
   - `enabled`
   - `skillKey`
   - `primaryEnv` / `apiKey`-style secret storage
   - declared config-path values
8. The repository can produce a beta handoff packet:
   - installable artifact
   - repeatable validation commands
   - known limitations
   - size numbers
   - artifact/testing instructions

### 5.2 What “done” does **not** require

This phase does **not** require:

- browser automation
- remote bridge mode
- multi-device sync
- channel integrations (Telegram / WhatsApp / Gmail / Slack / Discord)
- arbitrary shell execution
- voice wake mode
- camera/screen/continuous media pipelines
- major security hardening
- production Play launch

### 5.3 Naming the outcome honestly

The target outcome is best described as a **beta-ready local host**, not a final production release.

That wording matters. Android’s background execution model, exact-alarm special access, notification permission state, device quotas, and OEM variability all make “production-complete personal assistant host” a higher bar than the codebase needs to hit in this phase. This plan is about reaching a strong, truthful beta that can survive contact with real testers. [R8][R9][R10][R11][R12][R19][R20]

---

## 6. Non-goals until after this phase

Keep these out of scope unless this plan is explicitly amended:

- browser automation or embedded Chromium
- WebView-heavy agent UI features
- external chat-channel integrations
- remote bridge mode as a baseline requirement
- shell execution / arbitrary command execution
- cloud sync / accounts / auth
- continuous voice workflows
- screen recording workflows
- camera workflows beyond future typed-tool stubs
- large dependency injections frameworks
- repository modularization into many Gradle modules
- broad OEM-specific battery-manager workaround features
- aggressive security projects (sandboxing, attestations, enterprise crypto policies)

### 6.1 Security posture for this phase

The user explicitly set the priority order as **lightweight > security** for this repo phase. That does **not** mean “ignore obvious secret handling,” but it does mean:

- keep existing provider-secret hygiene intact
- avoid expanding plaintext secret handling
- do not spend this phase on heavyweight sandbox/security systems
- prefer clear boundaries over elaborate hardening

In other words: **adequate and boring**, not enterprise-grade.

---

## 7. External facts that shape this plan

This plan is not based only on repository taste. It is shaped by current external constraints.

1. OpenAI’s harness-engineering guidance explicitly recommends a short `AGENTS.md`, a structured docs directory as the system of record, and checked-in execution plans with progress/decision logs. [R1]
2. OpenClaw’s current runtime centers on typed tools, `SKILL.md` folders, skill precedence, and a built-in cron scheduler with main-session and isolated execution modes. [R3][R4][R5][R6]
3. OpenClaw’s Android app is still positioned as a node/companion app, not the Gateway host, which reinforces AndroidClaw’s choice to be a native host reinterpretation rather than a direct port. [R2][R7]
4. Android recommends WorkManager for persistent background work that must survive app/process/device restarts. [R8]
5. WorkManager periodic work has a minimum repeat interval of 15 minutes, so any interval automation contract must acknowledge that platform floor. [R9]
6. Exact alarms are denied by default for most newly installed apps on Android 14+ when targeting API 33+, and apps must check capability, react to permission-state changes, and explain the user flow honestly. [R10][R11]
7. Android 13+ notifications are off by default for new installs until `POST_NOTIFICATIONS` is granted, so “task ran” and “task visibly reminded the user” are different truths. [R12]
8. Build-managed devices improve consistency and reliability for instrumented tests, and Android’s testing guidance still recommends many small tests with relatively few big tests. [R13][R14][R15]
9. Baseline Profiles can materially improve first-launch/runtime performance, with current official guidance still citing roughly 30% code-execution improvement on included paths. [R16]
10. R8 and resource shrinking remain the official path to reducing app size and can be adopted incrementally. [R17]
11. Android vitals monitors startup, wakeups, and wake-lock-related behavior; WorkManager and AlarmManager can attribute wake locks to the app, so scheduler/battery discipline is not optional. [R18][R19]
12. Android 16 changes job quotas and long-running worker behavior, which raises the cost of sloppy background execution and reinforces the need for bounded work. [R20]
13. App Bundles, internal test tracks, internal app sharing, and bundletool are the official ways to distribute and validate release artifacts before production. [R21]
14. Mobile-app testing research continues to show that flaky and overly broad mobile tests are costly; deterministic local tests and narrowly chosen device tests are a better default. [R22][R23][R24][R25]

---

## 8. Architecture invariants for the rest of the project

### 8.1 Module and dependency policy

Keep these invariant unless there is an explicit written decision to change them:

- one production app module (`:app`) through this phase
- one optional test-only `baselineprofile/` module if Baseline Profiles are implemented
- manual dependency wiring only
- no Hilt / Koin / reflection-heavy DI
- no Node.js / Bun / Docker / Chromium / desktop runtime embedding
- prefer Android platform APIs + small Jetpack libraries already in use

### 8.2 Runtime policy

- AndroidClaw remains a **local phone host**, not a thin remote bridge.
- Tools stay **typed**.
- The scheduler remains **hybrid**:
  - WorkManager for reliable persisted background work
  - exact alarms only for narrow, user-visible precise cases
- No background daemon loops.
- No fake “always available” camera/screen/mic claims.
- Event logs stay bounded and human-readable.

### 8.3 Data policy

- Continue using Room for durable product data already modeled there:
  - sessions
  - messages
  - tasks
  - task runs
  - skill records
  - event logs
- Avoid unnecessary schema churn.
- Prefer DataStore / small encrypted stores for lightweight settings/config surfaces that do not need relational queries.
- Do not store secrets in plain text.

### 8.4 UX policy

- The GUI must remain small and self-explanatory.
- New UI work in this phase must serve one of these purposes:
  - expose already-implemented runtime power more clearly
  - close a real compatibility gap
  - make release/testing truth visible
- Avoid introducing settings panels that expose runtime detail without product value.

### 8.5 Performance policy

- Lightweight size is a core product constraint, not a nice-to-have.
- Prefer smaller dependency surfaces and smaller shipped artifacts over theoretical extensibility.
- No idle foreground service.
- No eager loading of everything on app launch.
- No new heavy animation or visual-effect work.
- Size and startup claims must be measured and recorded.

### 8.6 Documentation policy

- `AGENTS.md` stays short.
- `PLANv5.md` stays canonical.
- Docs must say what the repo actually does today, not what it might do later.
- Any new user-facing limitation discovered during the phase must be recorded in `docs/KNOWN_LIMITATIONS.md` before the phase is considered complete.

---

## 9. Delivery strategy for the next phase

The most stable delivery strategy is to run three tracks in order, not all at once.

### Track A — contract completion

Workstreams:

- `ws0-plan-adoption`
- `ws1-tool-execution-context`
- `ws2-task-tools-contract-completion`

This track closes the most important runtime gap: making automation fully agent-manageable.

### Track B — packaging and optimization

Workstreams:

- `ws3-installable-optimized-build-lane`
- `ws4-r8-and-size-reduction`
- `ws5-ci-parity`

This track closes the “can we actually ship/test this as a lightweight artifact?” gap.

### Track C — user-facing compatibility closure and handoff

Workstreams:

- `ws6-skill-config-surface`
- `ws7-optional-baseline-profiles`
- `ws8-beta-handoff`

This track closes the remaining docs/runtime mismatch and produces the artifact/evidence/testing packet.

Do **not** jump to Track C before Track A is done. A nicer settings/config surface is lower leverage than completing the task-tool contract.

---

## 10. Ordered workstreams

## ws0-plan-adoption — make PLANv5 canonical

### Goal

Make `PLANv5.md` the live source of truth and remove top-level drift.

### Why first

OpenAI’s harness-engineering guidance is explicit that repository knowledge should be the system of record and that plans should be first-class. Right now the repo still points agents at `PLANv4.md`, which means drift has already started. [R1]

### Ordered steps

1. Update `AGENTS.md`:
   - change all `PLANv4.md` references to `PLANv5.md`
   - keep the file short
   - do not stuff v5 detail into `AGENTS.md`
2. Update `README.md`:
   - link “Execution plan” to `PLANv5.md`
   - keep product summary otherwise stable
3. Add a short note near the top of `PLANv4.md` saying it is historical and superseded.
4. If any docs explicitly say “current canonical plan = PLANv4”, update them.
5. Do **not** change architecture or scope in this workstream; keep it purely documentary.

### Files likely touched

- `AGENTS.md`
- `README.md`
- `PLANv4.md`
- possibly `docs/TESTING.md` or `docs/RELEASE_CHECKLIST.md` if they reference `PLANv4.md`

### Acceptance criteria

- No top-level file points Codex at `PLANv4.md` as the active plan.
- `AGENTS.md` remains map-like and short.
- `PLANv5.md` becomes the single active execution plan.

### Validation

- no build required if only docs changed
- run a repo grep for `PLANv4.md` and verify only historical mentions remain

---

## ws1-tool-execution-context — make tools context-aware

### Goal

Introduce a stable `ToolExecutionContext` so every tool call knows:

- who invoked it
- from which session
- under what run mode
- whether it is associated with a task run
- which safe audit metadata should be logged

### Why this matters

This is the highest-leverage missing abstraction in the runtime.

Right now tools are effectively just:

- `name`
- `arguments`

That is enough for inspection tools, but not enough for the next level of product behavior:

- creating tasks with sensible defaults
- distinguishing interactive vs scheduled tool use
- recording actionable event logs
- giving better diagnostics
- supporting future task/session tools without smuggling context through ad-hoc arguments

### Design target

Add a small explicit context object. Example shape:

```kotlin
data class ToolExecutionContext(
    val sessionId: String?,
    val taskRunId: String?,
    val origin: ToolInvocationOrigin,
    val runMode: ModelRunMode?,
    val requestedName: String,
    val canonicalName: String,
    val requestId: String?,
    val activeSkillId: String? = null,
)
```

With something like:

```kotlin
enum class ToolInvocationOrigin {
    Model,
    SlashCommand,
    ScheduledModel,
    Internal,
}
```

Notes:

- `sessionId` can be null for fully internal calls.
- `runMode` can be null for non-model calls.
- `activeSkillId` is optional but useful for slash dispatch and event logs.
- Do **not** add large context bags full of unstable values. Keep the object small.

### Ordered steps

1. Create `ToolExecutionContext` and `ToolInvocationOrigin`.
2. Change `ToolRegistry.Entry.handler` from:

   ```kotlin
   suspend (JsonObject) -> ToolExecutionResult
   ```

   to:

   ```kotlin
   suspend (ToolExecutionContext, JsonObject) -> ToolExecutionResult
   ```

3. Change `ToolRegistry.execute(...)` to accept context.
4. Pass context from:
   - direct slash-tool dispatch
   - provider tool-call loop
   - any internal scheduler-triggered direct tool use if added later
5. Update `ToolExecutionResult` callers only as needed; avoid broad redesign unless needed by the new tools.
6. Add bounded tool event logging:
   - log tool start/success/failure in `EventLogRepository`
   - record safe metadata only
   - do not dump secret values or arbitrary raw payloads
7. Keep unknown-tool and failing-tool behavior structured, consistent with the previous remediation work.
8. Update tests first, then code, then docs.

### Files likely touched

- `app/src/main/java/ai/androidclaw/runtime/tools/ToolRegistry.kt`
- `app/src/main/java/ai/androidclaw/runtime/tools/BuiltInTools.kt`
- `app/src/main/java/ai/androidclaw/runtime/orchestrator/AgentRunner.kt`
- possibly `app/src/main/java/ai/androidclaw/runtime/scheduler/TaskRuntimeExecutor.kt`
- possibly `app/src/main/java/ai/androidclaw/data/repository/EventLogRepository.kt`
- tests:
  - `ToolRegistryTest.kt`
  - `AgentRunnerTest.kt`
  - any tool-specific tests

### Acceptance criteria

- All tool handlers receive an explicit context object.
- Slash dispatch and provider tool calls populate the context correctly.
- Tool event logs are visible in health diagnostics without leaking secrets.
- Existing built-in tools still behave exactly as before from the user’s perspective.

### Validation

Fast lane:

- `./gradlew :app:testDebugUnitTest`

Targeted tests to keep honest:

- `ai.androidclaw.runtime.tools.ToolRegistryTest`
- `ai.androidclaw.runtime.orchestrator.AgentRunnerTest`
- `ai.androidclaw.feature.health.HealthViewModelTest`

Full fast loop after landing:

- `./gradlew :app:assembleDebug :app:testDebugUnitTest :app:lintDebug`

---

## ws2-task-tools-contract-completion — let the agent manage automations

### Goal

Finish the minimum task automation tool contract so an agent can:

- inspect tasks
- inspect a specific task
- create tasks
- update tasks
- enable/disable tasks
- delete tasks
- queue “run now”

### Why now

AndroidClaw already claims sessions + tools + skills + automations as its product thesis. But the current tool surface only exposes `tasks.list`. That means the assistant can discuss tasks, but not fully manage them through typed tools. This is the clearest remaining mismatch between product thesis and runtime reality.

OpenClaw’s public runtime also treats cron as a first-class built-in scheduler with typed management surfaces rather than “describe some schedule in prose and hope the UI handles it later.” AndroidClaw should mirror that spirit locally. [R4][R6]

### Scope decision

For v5, the task tool surface should be:

- `tasks.list`
- `tasks.get`
- `tasks.create`
- `tasks.update`
- `tasks.enable`
- `tasks.disable`
- `tasks.delete`
- `tasks.run_now`

Do **not** add more until these are complete and well-tested.

### API shape

Prefer explicit, typed JSON arguments, not natural-language schedule parsing.

#### `tasks.get`

Inputs:

- `taskId` (required)

Output:

- canonical task payload
- current next run
- current enable state
- last run summary if available

#### `tasks.create`

Inputs:

- `name` (required)
- `prompt` (required)
- `scheduleKind` = `once | interval | cron` (required)
- `atIso` for `once`
- `anchorAtIso` + `repeatEveryMinutes` for `interval`
- `cronExpression` + `timezone` for `cron`
- `executionMode` = `MAIN_SESSION | ISOLATED_SESSION`
- `targetSessionId` optional
- `targetSessionAlias` optional: `main | current`
- `precise` optional
- `maxRetries` optional

Rules:

- `once` must be in the future
- `interval` must be `>= minimumBackgroundInterval`
- `cron` must parse successfully
- `timezone` must be valid
- `precise = true` is allowed only as a request; the returned payload must still report the effective degradation/warnings honestly
- if `targetSessionAlias = current`, resolve it from `ToolExecutionContext.sessionId`
- if no target is supplied, default to main-session delivery to match the current app behavior

#### `tasks.update`

Use patch semantics, not replace-all semantics.

Inputs:

- `taskId` (required)
- any subset of:
  - `name`
  - `prompt`
  - schedule fields
  - `executionMode`
  - `targetSessionId`
  - `targetSessionAlias`
  - `precise`
  - `maxRetries`

Rules:

- unspecified fields keep existing values
- schedule updates must recompute `nextRunAt`
- invalid schedule patches must fail structurally with `INVALID_ARGUMENTS`

#### `tasks.enable` / `tasks.disable`

Inputs:

- `taskId` (required)

Behavior:

- toggles task state
- reschedules or cancels future work accordingly
- returns updated task payload

#### `tasks.delete`

Inputs:

- `taskId` (required)

Behavior:

- removes task
- cancels future work
- does not silently leave orphaned scheduled work
- run history retention policy can remain as-is unless repository behavior requires a documented decision

#### `tasks.run_now`

Inputs:

- `taskId` (required)

Behavior:

- queues immediate execution
- does not destroy future recurring schedule
- returns queued result metadata
- must never be a silent no-op

### Implementation notes

1. Reuse current scheduler capability truth:
   - `minimumBackgroundInterval`
   - exact-alarm support
   - notification visibility warnings
2. Keep tool outputs bounded and machine-usable.
3. Return structured failure payloads, never string-only failures.
4. Do not force the agent to know UI-only concepts.
5. If schema validation at the registry layer becomes too awkward, validate inside the handler and return `INVALID_ARGUMENTS` cleanly.
6. Keep the task contract aligned with current repository semantics instead of inventing a new scheduler model.

### Example create payload

```json
{
  "name": "Morning summary",
  "prompt": "Summarize my tasks for today and remind me of anything due soon.",
  "scheduleKind": "cron",
  "cronExpression": "0 8 * * *",
  "timezone": "Europe/London",
  "executionMode": "MAIN_SESSION",
  "targetSessionAlias": "main",
  "precise": false,
  "maxRetries": 3
}
```

### Files likely touched

- `app/src/main/java/ai/androidclaw/runtime/tools/BuiltInTools.kt`
- `app/src/main/java/ai/androidclaw/runtime/tools/ToolRegistry.kt`
- `app/src/main/java/ai/androidclaw/data/repository/TaskRepository.kt`
- `app/src/main/java/ai/androidclaw/runtime/scheduler/SchedulerCoordinator.kt`
- maybe small helpers under `runtime/scheduler/`
- maybe `docs/SCHEDULER.md`
- maybe `docs/ARCHITECTURE.md`
- tests:
  - `BuiltInToolsTest.kt`
  - `ToolRegistryTest.kt`
  - `TaskRepositoryTest.kt`
  - `TaskExecutionWorkerTest.kt`
  - `AgentRunnerTest.kt`

### Acceptance criteria

- An agent can create a valid task entirely from typed tool calls.
- Invalid schedule inputs fail cleanly and structurally.
- `run_now` remains non-silent and preserves future schedule behavior.
- The returned task payload always includes:
  - effective schedule
  - next run
  - enable state
  - precision request/effective warnings
- The task tool surface is documented in code comments and docs.

### Validation

Required:

- `./gradlew :app:testDebugUnitTest`

Targeted:

- task tool unit tests
- scheduler tests for reschedule/cancel behavior
- `TasksViewModelTest` if UI messages change

Recommended manual check:

- use `FakeProvider` or direct slash/tool invocation path to create a task from chat
- verify the task appears in the Tasks screen without app restart

---

## ws3-installable-optimized-build-lane — create a real shrink/test target

### Goal

Create an installable, release-like build lane that can be used to validate optimization and device behavior **before** production signing exists.

### Why now

The current repo can build a release artifact, but the documented artifact is still an unsigned, unshrunk APK. That is not the artifact we need for serious optimization/device smoke work. We need an installable release-like target before turning on R8/resource shrinking.

### Core decision

Introduce one extra build type for local/installable release-like testing.

Suggested name:

- `qa`

Suggested shape:

- `initWith(release)`
- debug signing config for local installability
- explicit note that this is **not** the production Play artifact
- optional `matchingFallbacks` only if needed for test/dependency resolution
- keep the number of build types small

### Why this design is better than overloading `release`

- it preserves a clean distinction between:
  - **production release packaging**
  - **local installable release-like testing**
- it avoids coupling repo progress to production signing keys
- it creates a safe place to validate shrinking/minification/device smoke
- it keeps the product light: one extra build type is cheaper than introducing major distribution tooling

### Ordered steps

1. Add a `qa` build type in `app/build.gradle.kts`.
2. Make it installable locally with debug signing.
3. Keep package identity simple; only add `applicationIdSuffix` if same-device coexistence becomes necessary.
4. Ensure Android tests can build against the `qa` variant if needed:
   - if variant resolution fails, use build-type fallback intentionally
   - do not paper over failures with broad Gradle hacks
5. Add packaging tasks to docs:
   - `:app:assembleQa`
   - `:app:assembleQaAndroidTest` if applicable
6. If the Windows wrapper currently assumes debug-only tasks, extend it to accept:
   - explicit build variant, or
   - explicit Gradle task
7. Keep `release` as the path for eventual AAB/Play artifact generation.
8. Document the distinction in:
   - `docs/TESTING.md`
   - `docs/PERFORMANCE.md`
   - `docs/RELEASE_CHECKLIST.md`

### Files likely touched

- `app/build.gradle.kts`
- `scripts/run_windows_android_test.sh`
- `scripts/run_windows_android_test.ps1`
- maybe `scripts/run_exact_alarm_regression.sh` if variant-aware testing is needed later
- `docs/TESTING.md`
- `docs/PERFORMANCE.md`
- `docs/RELEASE_CHECKLIST.md`

### Acceptance criteria

- `:app:assembleQa` succeeds.
- The resulting APK is installable on an emulator/device without production signing keys.
- If `qa` instrumentation is enabled, the repo can compile `:app:assembleQaAndroidTest`.
- The docs clearly distinguish:
  - debug fast loop
  - installable `qa` optimization lane
  - production `release` / `bundleRelease` packaging lane

### Validation

Minimum:

- `./gradlew :app:assembleQa`

Recommended:

- `./gradlew :app:assembleQaAndroidTest`
- install `qa` APK on the Windows AVD path and launch smoke test manually
- rerun:
  - `:app:assembleDebug`
  - `:app:testDebugUnitTest`
  - `:app:lintDebug`

---

## ws4-r8-and-size-reduction — make the lightweight claim measurable

### Goal

Turn on code shrinking and resource shrinking safely, measure the result, and keep the app installable and functional.

### Why this matters

Lightweight size is one of the product’s primary promises. Android’s official guidance still points to R8 and resource shrinking as the standard path to reduce code and resource size, and current repo docs already admit shrinking is deferred. That is exactly the kind of deferred truth that should be closed in this phase. [R17]

### Strategy

Adopt optimization incrementally.

Phase 1:

- enable R8/code shrinking on `qa`
- leave `release` unchanged until `qa` is proven

Phase 2:

- after `qa` validation is green, enable the same on `release`

Phase 3:

- enable resource shrinking where safe
- measure and record size deltas

### Ordered steps

1. Record the current baseline artifact sizes again in the repo docs before changing anything.
2. Enable minification on `qa`.
3. Build `qa` and verify installability.
4. Run smoke/device validation on `qa`.
5. Fix keep rules only where evidence shows they are needed.
6. When `qa` is stable, enable the same on `release`.
7. Then enable `isShrinkResources = true`.
8. Rebuild and compare:
   - APK size
   - major archive entries
   - install behavior
9. Update:
   - `docs/PERFORMANCE.md`
   - `docs/RELEASE_CHECKLIST.md`
   - `docs/KNOWN_LIMITATIONS.md`
   - a QA evidence doc, ideally `docs/qa/release-size-validation.md`

### Keep-rule guidance

Do **not** pre-emptively add wide keep rules.

Only add keep rules for proven needs such as:

- reflection-using libraries
- serialization paths if broken
- generated code discovery if broken
- startup/reflection entry points R8 cannot infer

Every keep rule must have a reason.

### Suggested measurement outputs

Record at minimum:

- `assembleQa` size before/after
- `assembleRelease` or `bundleRelease` output size before/after
- top large archive entries before/after
- any keep rules added
- whether install/smoke remained green

### Files likely touched

- `app/build.gradle.kts`
- `app/proguard-rules.pro`
- possibly `app/src/main/AndroidManifest.xml` if keep/discovery issues arise
- `docs/PERFORMANCE.md`
- `docs/RELEASE_CHECKLIST.md`
- `docs/KNOWN_LIMITATIONS.md`
- `docs/qa/release-size-validation.md` (new or updated)

### Acceptance criteria

- `qa` build is minified and still installable.
- `release` build is minified and resource-shrunk unless concrete evidence forces a scoped rollback.
- Measured size reduction is recorded in the repo.
- Any added keep rules are minimal and explained.
- No core v0 flow is broken by shrinking:
  - launch
  - chat with `FakeProvider`
  - task creation/listing/run-now
  - skills listing/import toggle
  - settings/provider config
  - health diagnostics

### Validation

Required:

- `./gradlew :app:assembleQa :app:assembleRelease`

Recommended:

- device smoke on `qa`
- `./gradlew :app:testDebugUnitTest :app:lintDebug`
- exact-alarm regression rerun if scheduler code changes were needed during keep-rule fixes

### Rollback rule

If shrinking causes broad regressions and the cause is not isolated quickly:

1. keep `qa` as the experimentation lane
2. revert `release` shrinking temporarily
3. record the blocker honestly
4. continue the rest of the plan

This keeps progress stable.

---

## ws5-ci-parity — make CI reflect packaging truth

### Goal

Evolve CI from “fast loop only” to “fast loop + packaging truth”, without introducing a flaky mandatory emulator gate.

### Why now

The current GitHub Actions workflow is good for fast feedback, but it still mostly mirrors debug correctness. Once we add `qa` and shrinking, CI must at least compile/package the new truth. At the same time, Android’s own testing guidance still favors many small tests and relatively few big tests, and the repo’s explicit device lanes remain environment-sensitive. So the correct move is **parity without premature flake**. [R13][R14][R15][R22][R23]

### Proposed CI shape

#### Required PR/push jobs

1. **fast**
   - `:app:assembleDebug`
   - `:app:testDebugUnitTest`
   - `:app:lintDebug`

2. **packaging**
   - `:app:assembleDebugAndroidTest`
   - `:app:assembleQa`
   - `:app:assembleRelease`
   - `:app:bundleRelease`

3. **artifacts**
   - upload:
     - debug APK (optional)
     - qa APK
     - release AAB
     - lint report
     - test report XML/HTML

#### Optional / non-blocking jobs

4. **device-smoke**
   - `workflow_dispatch`
   - or scheduled/nightly
   - only after a managed-device lane is proven stable on the chosen runner

Do **not** block every PR on emulator/device jobs until the runner story is actually stable.

### Ordered steps

1. Expand `.github/workflows/android.yml` into separate logical jobs.
2. Add packaging tasks.
3. Add artifact upload.
4. Keep caches and job structure simple.
5. Do not add complex matrix expansion yet.
6. If `qa` build type exists, decide whether CI should publish `qa` APK as the main test-install artifact.
7. Optionally add a manual `workflow_dispatch` path for a managed-device job later.
8. Update `docs/TESTING.md` and `README.md` if CI-visible commands change.

### Files likely touched

- `.github/workflows/android.yml`
- `README.md`
- `docs/TESTING.md`

### Acceptance criteria

- CI remains green on the repo after the packaging lane is added.
- PR/push automation now proves at least one installable release-like artifact is buildable.
- Release bundle generation is CI-covered.
- CI artifacts are easy for humans to download and inspect.

### Validation

- push/PR workflow green
- artifact links present in GitHub Actions UI
- local commands still match documented commands

---

## ws6-skill-config-surface — close the docs/runtime gap for skill config

### Goal

Implement the smallest skill configuration surface that makes the current `docs/SKILLS_COMPAT.md` claims materially true in the app.

### Why now

The docs already discuss:

- `skillKey`
- `requires.env`
- `requires.config`
- `apiKey` / `primaryEnv`-style semantics

But the current UI/runtime only really supports enable/disable and import/refresh. That is a docs/runtime mismatch and should be closed before beta handoff.

### Scope decision

For v5, support **this subset**:

- per-skill enabled state (already exists)
- per-skill secret fields for declared env names
- per-skill config values for declared config paths
- `skillKey` as the stable key for those values
- eligibility updates based on stored values

Do **not** promise:

- generic host-process env injection
- arbitrary script execution support
- `requires.bins` / `requires.anyBins`
- auto-installers
- ClawHub sync/install/update flows

### Storage design

Keep it lightweight.

#### Non-secret config

Use a lightweight local settings store, not a new heavy subsystem.

Recommended shape:

- `SkillConfigStore`
- backed by DataStore or another simple local serialization path
- keyed by `skillKey`
- stores:
  - declared config-path values
  - non-secret UI state if needed

#### Secrets

Use a dedicated secret store similar in spirit to the existing provider secret store:

- `SkillSecretStore`
- Keystore-backed local encrypted storage
- key namespace: skill key + env name

Why this split:

- no Room migration required just to add a small config surface
- secrets remain out of plain-text settings
- implementation stays light

### Eligibility model changes

Update skill eligibility so that:

- `requires.env` is satisfied if the relevant secret exists and is nonblank
- `requires.config` is satisfied if the relevant stored config path exists and is nonblank
- `requires.bins` and `requires.anyBins` remain explicitly unsupported on local Android and continue to surface as ineligibility reasons
- required tools continue to use existing tool-availability logic

### UI design

Keep it simple.

Recommended UX:

- Skills screen shows config status inline
- each skill card gets a **Configure** action only when there is something configurable
- configuration opens a bottom sheet or dialog
- secret values are never displayed back after save; show only:
  - “Configured”
  - “Not configured”
  - “Clear”
- config-path values can initially be simple string fields
- unsupported requirement types remain visible with clear reasons

### Ordered steps

1. Introduce `SkillConfigStore`.
2. Introduce `SkillSecretStore`.
3. Extend `SkillManager` eligibility evaluation to use those stores.
4. Add view-model support for reading/saving/clearing per-skill config.
5. Add a small editor UI.
6. Update `docs/SKILLS_COMPAT.md` to describe exactly what is now supported.
7. Add tests:
   - eligibility changes when config/secrets are added or cleared
   - UI state/config persistence
8. Keep the current enable/disable behavior intact.

### Files likely touched

- new store(s) under `app/src/main/java/ai/androidclaw/data/`
- `app/src/main/java/ai/androidclaw/runtime/skills/SkillManager.kt`
- `app/src/main/java/ai/androidclaw/feature/skills/SkillsViewModel.kt`
- `app/src/main/java/ai/androidclaw/feature/skills/SkillsScreen.kt`
- maybe app wiring in `AppContainer.kt`
- `docs/SKILLS_COMPAT.md`
- tests for skills/view model/storage

### Acceptance criteria

- A skill declaring `primaryEnv` / `requires.env` can become eligible after the user configures it.
- Clearing the stored value makes it ineligible again.
- Declared config-path requirements can be stored and reflected in eligibility.
- Unsupported requirement types remain clearly unsupported rather than silently ignored.
- Secret fields are not shown back to the user in plain text after save.

### Validation

Required:

- `./gradlew :app:testDebugUnitTest`

Recommended manual check:

- import a test skill that declares `primaryEnv` or `requires.env`
- configure it
- verify eligibility changes without app restart
- clear it
- verify eligibility reverts

---

## ws7-optional-baseline-profiles — improve startup if the environment allows it

### Goal

Add Baseline Profiles **if** the environment/dependency-resolution blocker is gone, without turning them into a hard blocker for beta.

### Why optional

The repo already documents an environment-specific blocker around fetching benchmark/profile artifacts in the current WSL runtime. Baseline Profiles still matter for a lightweight Android app, and Android’s current docs continue to recommend them strongly, but the repo should not stall indefinitely on this one external condition. [R16]

### Ordered steps

1. Probe whether the dependency-resolution blocker still exists on the chosen host.
2. If it is fixed:
   - add a test-only baseline profile module
   - use the generator/template path or equivalent plugin setup
   - cover:
     - app launch
     - navigate to chat
     - navigate to tasks
     - navigate to skills
     - navigate to settings
     - navigate to health
     - send one `FakeProvider` message
   - generate and verify the profile
   - document the result in `docs/PERFORMANCE.md`
3. If it is still blocked:
   - record that the blocker persists
   - keep Baseline Profiles out of the beta-critical path
   - move on

### Acceptance criteria

Success path:

- Baseline Profile support is checked in and documented.

Fallback path:

- the repo clearly records why it is still deferred, and beta proceeds without pretending otherwise.

### Validation

If implemented:

- the relevant profile-generation and verification tasks are green
- startup-critical smoke still passes

If deferred:

- docs updated honestly, no hidden omission

---

## ws8-beta-handoff — produce a truthful tester packet

### Goal

Produce a beta packet that another human can use to install, validate, and bug-report AndroidClaw without needing hidden knowledge.

### Why this matters

A codebase can be internally coherent and still not be externally testable. This workstream turns the repo into a handoffable product artifact and closes the loop between engineering and real-world testing.

### Output of this workstream

By the end of `ws8`, the repo should have a clear beta packet containing:

1. **Artifacts**
   - installable `qa` APK
   - release AAB
2. **Validation docs**
   - fast loop
   - packaging lane
   - device smoke lane
   - exact-alarm regression lane
3. **Known limitations**
   - exact alarms
   - notification permission
   - OEM variance
   - any remaining Baseline Profile deferral
4. **Size evidence**
   - before/after optimization
5. **Tester instructions**
   - how to install
   - how to create and run tasks
   - how to configure provider and skill secrets
   - what not to expect in v0 beta

### Ordered steps

1. Update docs:
   - `README.md`
   - `docs/TESTING.md`
   - `docs/PERFORMANCE.md`
   - `docs/RELEASE_CHECKLIST.md`
   - `docs/KNOWN_LIMITATIONS.md`
2. Add a new beta-oriented doc if needed, e.g.:
   - `docs/BETA_HANDOFF.md`
3. Add bundle distribution guidance:
   - local bundletool test
   - internal app sharing or internal test track if Play access exists
4. Update QA evidence docs:
   - release/install validation
   - size validation
   - exact-alarm regression
5. Expand required manual QA to include:
   - task creation from chat/tool path
   - skill config editing
   - install/launch of `qa`
6. Freeze scope for the beta packet.
7. Optionally prepare release notes / change summary if that helps testers.

### Acceptance criteria

- Another engineer or tester can install the artifact and follow the docs.
- The repo contains all commands needed to reproduce the beta validation.
- Limitations are explicit.
- No top-level doc still implies “RC done” if the repo is now in beta-handoff mode.

### Validation

- perform the documented beta flow on at least one emulator/device path
- ensure all docs reference the same artifact names and commands

---

## 11. Detailed sequencing recommendation

This is the recommended exact order. Do not reorder casually.

### Packet A — plan adoption

1. land `ws0`
2. make `PLANv5.md` canonical

### Packet B — tool context foundation

3. land `ws1`
4. verify no tool behavior regressions

### Packet C — finish task tools

5. land `ws2`
6. verify agent-managed task creation/update/delete/run-now

### Packet D — installable optimized lane

7. land `ws3`
8. ensure `qa` artifact is real and installable

### Packet E — shrink and measure

9. land `ws4`
10. keep `qa` as the proving ground
11. propagate to `release` only after evidence is green

### Packet F — CI parity

12. land `ws5`
13. make artifacts downloadable in CI

### Packet G — skill config closure

14. land `ws6`
15. verify docs/runtime parity improves materially

### Packet H — optional startup optimization

16. attempt `ws7`
17. if blocked, document and continue

### Packet I — beta packet

18. land `ws8`
19. freeze scope and produce handoff evidence

---

## 12. Validation matrix

### 12.1 Always-run fast matrix

Run after every non-trivial code change:

- `./gradlew :app:assembleDebug`
- `./gradlew :app:testDebugUnitTest`
- `./gradlew :app:lintDebug`

### 12.2 Packaging matrix

Run after build-type / shrink / release / CI changes:

- `./gradlew :app:assembleDebugAndroidTest`
- `./gradlew :app:assembleQa` (after `qa` exists)
- `./gradlew :app:assembleRelease`
- `./gradlew :app:bundleRelease`

### 12.3 Device smoke matrix

Preferred current smoke tests:

- `ai.androidclaw.app.MainActivitySmokeTest`
- `ai.androidclaw.runtime.scheduler.TaskExecutionWorkerSmokeTest`

Current wrapper path from WSL:

```bash
ANDROIDCLAW_JAVA_HOME=/path/to/jdk17 ./scripts/run_windows_android_test.sh --avd AndroidClawApi34 --test-class ai.androidclaw.app.MainActivitySmokeTest
ANDROIDCLAW_JAVA_HOME=/path/to/jdk17 ./scripts/run_windows_android_test.sh --avd AndroidClawApi34 --test-class ai.androidclaw.runtime.scheduler.TaskExecutionWorkerSmokeTest
```

If the wrapper becomes variant-aware later, document the `qa` equivalent too.

### 12.4 Exact-alarm regression matrix

Keep the current regression lane alive:

```bash
ANDROIDCLAW_JAVA_HOME=/path/to/jdk17 ./scripts/run_exact_alarm_regression.sh --api34-avd AndroidClawApi34 --api31-avd AndroidClawApi31
```

Re-run after:

- scheduler changes
- exact-alarm permission changes
- release-like build-type changes if they affect instrumentation

### 12.5 Managed-device matrix

Keep this as the preferred long-term repo-native lane:

```bash
./gradlew :app:pixel8Api36DebugAndroidTest -Pandroid.testoptions.manageddevices.emulator.gpu=swiftshader_indirect
```

But do **not** make this a hard PR gate until the runner/host path is actually stable.

### 12.6 Bundle validation matrix

After `bundleRelease` exists in the required path:

- generate AAB
- test with bundletool locally or via Play internal testing path if available

### 12.7 Manual QA matrix

Minimum manual QA before beta handoff:

- launch app fresh
- send one `FakeProvider` message
- create, switch, rename, and archive a session
- create `once`, `interval`, and `cron` tasks in GUI
- create at least one task through the agent/tool path
- use `Run now`
- inspect task run history
- import a local skill zip
- enable and disable a skill
- configure a skill secret/config field
- slash-invoke an eligible skill
- verify health/task diagnostics
- verify exact-alarm degrade messaging
- verify notification warning behavior when notifications are denied
- install and launch the `qa` artifact
- if bundle testing is available, install from the bundle-generated path too

### 12.8 What not to do

Do **not** claim success based only on:

- green unit tests without packaging
- green packaging without installability
- green installability without honest docs
- old RC evidence copied forward without rerunning the relevant lane

---

## 13. Human-blocker matrix

This phase is intentionally designed so that most work is repo-owned. Still, some blockers remain external.

### Blocker 1 — emulator/device availability

Needed for:

- smoke tests
- exact-alarm regression
- `qa` install validation

Mitigation:

- keep both managed-device and Windows-emulator paths
- do not tie repo progress to one host only

### Blocker 2 — Play Console access

Needed only if using:

- internal app sharing
- internal test tracks
- pre-launch report workflows

Mitigation:

- local `qa` install lane first
- App Bundle support should still exist even if Play upload is delayed

### Blocker 3 — real provider credentials

Needed only for:

- real-provider send validation

Mitigation:

- keep `FakeProvider` as the repo-required path

### Blocker 4 — Baseline Profile artifact resolution

Needed only for `ws7`.

Mitigation:

- keep it optional
- record honestly if still blocked

### Blocker 5 — production signing keys

Needed only for real production release.

Mitigation:

- use `qa` release-like build for local installability and shrink validation
- do not block beta on production signing

---

## 14. Risk register

### Risk 1 — tool execution context refactor causes broad regressions

Why it matters:

- tool context touches the core runtime loop

Mitigation:

- land `ws1` before new tools
- keep the context object small
- update tests first
- avoid broad redesign of tool results at the same time

### Risk 2 — task tool schema becomes too clever

Why it matters:

- LLM-facing tool schemas can become complex and fragile

Mitigation:

- keep the task tool set small
- use explicit fields
- no natural-language schedule parsing in the tool layer
- manual validation is acceptable if registry-level schema becomes awkward

### Risk 3 — `qa` build type explodes the variant matrix

Why it matters:

- extra variants can create Gradle/test complexity

Mitigation:

- add exactly one extra build type
- only wire variant-aware testing if needed
- keep docs clear on which variant is for what

### Risk 4 — R8 breaks runtime behavior

Why it matters:

- code shrinking can break reflective/discovery paths

Mitigation:

- stage on `qa` first
- add minimal keep rules only with evidence
- keep a rollback path for `release`

### Risk 5 — CI becomes slow or flaky

Why it matters:

- one of the repo’s strengths is a deterministic fast loop

Mitigation:

- keep PR gates focused on fast + packaging compile truth
- leave device jobs manual or scheduled until proven stable

### Risk 6 — skill config grows into a generic plugin platform

Why it matters:

- this phase is not the time to build a general-purpose secret/config engine

Mitigation:

- support only the current v5 subset
- do not implement shell/tool installer semantics
- keep fields simple

### Risk 7 — Baseline Profiles delay everything else

Why it matters:

- known external blocker may persist

Mitigation:

- keep them optional
- sequence them late
- do not let them block beta handoff

### Risk 8 — docs get ahead of reality again

Why it matters:

- this already happened for skill config and plan pointers

Mitigation:

- every workstream updates docs in the same PR/commit series
- treat stale docs as bugs

---

## 15. Progress seed

Use this section as a living checklist. Keep entries short.

- [x] single-module Kotlin-first Android host exists
- [x] Room persistence and schema export exist
- [x] scheduler core, worker execution, run history, and restore logic exist
- [x] bundled/local/workspace skill lifecycle exists
- [x] settings + provider configuration v1 exist
- [x] health/tasks/skills/settings/chat screens exist
- [x] repo-owned Windows-emulator scripts exist
- [x] exact-alarm regression tests and docs exist
- [x] README + architecture/testing/performance/release docs exist
- [x] `PLANv5.md` adopted as canonical
- [x] tool execution context added
- [x] task tools contract completed
- [x] installable `qa` build lane added
- [x] R8 enabled and measured
- [x] CI packaging parity expanded
- [x] skill config surface added
- [x] optional Baseline Profile support added or explicitly re-deferred
- [x] beta handoff packet produced

---

## 16. Discoveries seed

Add only facts that changed a real implementation choice.

- OpenAI’s agent-first guidance strongly supports keeping `AGENTS.md` short and putting detailed truth in checked-in docs/plans. [R1]
- OpenClaw’s current public runtime still centers on typed tools, `SKILL.md`, and built-in cron semantics; AndroidClaw should continue mirroring those semantics rather than desktop runtime internals. [R2][R3][R4][R5][R6]
- Android’s background-work guidance still makes WorkManager the default persistence primitive, with exact alarms as a narrow exception. [R8][R9][R10][R11]
- Android 13+ notification permission means “scheduled” and “user visibly notified” are not the same fact. [R12]
- Build-managed devices are still the official long-term path, but stable device automation should remain narrow. [R13][R14][R15]
- Baseline Profiles remain valuable for startup/runtime speed, but they should not become an external blocker for the rest of the phase. [R16]
- R8/resource shrinking is now the next most direct path to supporting AndroidClaw’s lightweight promise. [R17]
- Android vitals and wake-lock guidance make it important to keep scheduler work bounded and observable. [R18][R19][R20]
- The repository’s current task tool surface is inspection-heavy and creation-light; this is the clearest remaining runtime contract gap.
- The repository currently needs an installable release-like artifact before it can validate shrink/optimization on real devices honestly.
- The repository’s docs currently claim more about skill config semantics than the UI/runtime can actually satisfy.
- The active tester packet is now anchored on the installable `qa` APK plus the release AAB, with RC-era docs retained only as historical evidence.
- Tool audit logs need to preserve both the requested tool name and the canonical resolved name, because alias-based invocations are meaningful for diagnostics even when execution resolves to a single handler.
- `SchedulerCoordinator.scheduleTask()` reuses persisted `nextRunAt` when it is already present, so any task-tool schedule patch must recompute `nextRunAt` before rescheduling or the old schedule survives.
- AGP 8.13 exposes a shared `assembleAndroidTest` task for this app instead of a per-build-type `assembleQaAndroidTest`; the `qa` lane therefore reuses the shared androidTest APK while keeping the app APK variant-specific.
- Once `qa` is minified/resource-shrunk, the shared debug `androidTest` APK is no longer a truthful release-like proof lane; direct install-and-launch smoke is the stable way to validate shrunk `qa` packaging without destabilizing debug instrumentation or exact-alarm regression.
- The current shrinking pass reduced the installable APKs from roughly `10.2 MB` to roughly `2.1 MB` with only a narrow SnakeYAML `java.beans.*` `-dontwarn` adjustment.
- This workstation can intermittently deny local socket creation in the shell sandbox, which breaks Gradle's file-lock listener startup and Linux-side `adb`; when that happens, Windows-host or CI reruns are required for final build proof.
- The landed v5 skill config surface is intentionally narrow: config values are stored as per-skill strings keyed by `skillKey`, secrets are stored as `skillKey + envName`, and the current runtime uses them for eligibility/UI truth rather than generic tool/provider env injection.
- On this workstation, the first Robolectric + Room `SkillsViewModelTest` path can exceed short flow timeouts; stable coverage needs a hybrid wait strategy that advances the test dispatcher while allowing real background DB work to finish.
- Baseline Profiles remain explicitly deferred for the v5 beta path: the optimization is still desirable, but this repo phase is finishing on the documented fallback path instead of carrying an unresolved environment/module risk into the beta packet.

---

## 17. Decision log seed

- Decision: keep a single production `:app` module through this phase.  
  Rationale: build speed, runtime simplicity, agent legibility.

- Decision: prioritize tool execution context before adding more tools.  
  Rationale: context-free tools do not scale cleanly to task/session mutation.

- Decision: complete the task tool contract before adding new feature categories.  
  Rationale: it closes a product-core gap instead of expanding scope.

- Decision: introduce one installable release-like build type (`qa`) rather than overloading `release`.  
  Rationale: enables optimization/device testing without production signing.

- Decision: stage R8/resource shrinking on `qa` before `release`.  
  Rationale: reduces risk and keeps rollback simple.

- Decision: expand CI to cover packaging truth, but keep device jobs non-blocking until stable.  
  Rationale: avoids importing emulator flakiness into every PR.

- Decision: implement only the smallest useful skill config surface in v5.  
  Rationale: closes docs/runtime mismatch without building a generic plugin platform.

- Decision: keep v5 skill config fields as simple per-`skillKey` string/secret stores and use them for eligibility plus Skills UI, not generic runtime injection.  
  Rationale: it makes `docs/SKILLS_COMPAT.md` materially true now without inventing a broader plugin/config subsystem before beta.

- Decision: keep Baseline Profiles optional in this phase.  
  Rationale: performance benefit is real, but the repo should not stall on an external environment issue. [R16]

- Decision: close `ws7` on the explicit re-deferral path and keep Baseline Profiles out of the beta-critical path.  
  Rationale: the user-directed beta flow takes precedence over an optional optimization lane, and the repo already has truthful performance/limitations docs for that deferral.

- Decision: make `docs/BETA_HANDOFF.md` and `docs/qa/beta-validation.md` the active tester packet, while retaining `docs/qa/rc-validation.md` only as historical context.  
  Rationale: beta instructions, artifact truth, and validation evidence need one current entrypoint instead of being scattered across RC-era notes.

- Decision: continue deprioritizing heavy security work.  
  Rationale: lightweight installability, size, correctness, and testability are the phase priorities.

- Decision: keep tool event logging bounded to invocation metadata and terminal status, not raw arguments or payload dumps.  
  Rationale: health diagnostics need actionable auditability without leaking secrets or turning logs into unbounded transcripts.

- Decision: keep the task tool contract flat and typed with explicit schedule fields rather than nested natural-language or prose schedule payloads.  
  Rationale: it maps directly onto the current repository model, keeps provider tool schemas legible, and avoids inventing a second scheduler language.

- Decision: keep `qa` installable via debug signing and the base application id, without an `applicationIdSuffix` unless same-device coexistence becomes necessary later.  
  Rationale: it keeps the local release-like lane simple, installable, and close to production packaging without coupling it to production signing keys.

- Decision: keep the shared debug `androidTest` APK as the debug/exact-alarm instrumentation lane, and use direct launch smoke for minified `qa` instead of forcing a mismatched mixed-variant instrumentation path.
  Rationale: it preserves truthful release-like validation while keeping the existing debug-oriented instrumentation surface stable.

---

## 18. References

- **[R1]** OpenAI, *Harness engineering: leveraging Codex in an agent-first world*.  
  <https://openai.com/index/harness-engineering/>

- **[R2]** OpenClaw Docs, *Android App*.  
  <https://docs.openclaw.ai/platforms/android>

- **[R3]** OpenClaw Docs, *Skills*.  
  <https://docs.openclaw.ai/tools/skills>

- **[R4]** OpenClaw Docs, *Skills Config*.  
  <https://docs.openclaw.ai/tools/skills-config>

- **[R5]** OpenClaw Docs, *Tools*.  
  <https://docs.openclaw.ai/tools>

- **[R6]** OpenClaw Docs, *Cron Jobs*.  
  <https://docs.openclaw.ai/automation/cron-jobs>

- **[R7]** OpenClaw Docs, *Node Troubleshooting* and current Android/node foreground constraints.  
  <https://docs.openclaw.ai/nodes/troubleshooting>

- **[R8]** Android Developers, *Task scheduling / persistent background work*.  
  <https://developer.android.com/develop/background-work/background-tasks/persistent>

- **[R9]** Android Developers, *Define work requests*.  
  <https://developer.android.com/develop/background-work/background-tasks/persistent/getting-started/define-work>

- **[R10]** Android Developers, *Schedule exact alarms are denied by default*.  
  <https://developer.android.com/about/versions/14/changes/schedule-exact-alarms>

- **[R11]** Android Developers, *Schedule alarms*.  
  <https://developer.android.com/develop/background-work/services/alarms>

- **[R12]** Android Developers, *Notification runtime permission*.  
  <https://developer.android.com/develop/ui/views/notifications/notification-permission>

- **[R13]** Android Developers, *Scale your tests with build-managed devices*.  
  <https://developer.android.com/studio/test/managed-devices>

- **[R14]** Android Developers, *Testing strategies*.  
  <https://developer.android.com/training/testing/fundamentals/strategies>

- **[R15]** Android Developers, *Big test stability*.  
  <https://developer.android.com/training/testing/instrumented-tests/stability>

- **[R16]** Android Developers, *Baseline Profiles overview*, *Create Baseline Profiles*, and *Use a baseline profile in Compose*.  
  <https://developer.android.com/topic/performance/baselineprofiles/overview>  
  <https://developer.android.com/topic/performance/baselineprofiles/create-baselineprofile>  
  <https://developer.android.com/develop/ui/compose/performance/baseline-profiles>

- **[R17]** Android Developers, *Enable app optimization with R8* and *Reduce your app size*.  
  <https://developer.android.com/topic/performance/app-optimization/enable-app-optimization>  
  <https://developer.android.com/topic/performance/reduce-apk-size>

- **[R18]** Android Developers, *App startup time* and *What great technical quality looks like*.  
  <https://developer.android.com/topic/performance/vitals/launch-time>  
  <https://developer.android.com/quality/technical>

- **[R19]** Android Developers, *Excessive wakeups*, *Identify and optimize wake lock use cases*, and *Deeper Performance Considerations*.  
  <https://developer.android.com/topic/performance/vitals/wakeup>  
  <https://developer.android.com/develop/background-work/background-tasks/awake/wakelock/identify-wls>  
  <https://android-developers.googleblog.com/2025/11/deeper-performance-considerations.html>

- **[R20]** Android Developers, *Support for long-running workers*, *Behavior changes: all apps (Android 16)*, and *App Standby Buckets*.  
  <https://developer.android.com/develop/background-work/background-tasks/persistent/how-to/long-running>  
  <https://developer.android.com/about/versions/16/behavior-changes-all>  
  <https://developer.android.com/topic/performance/appstandby>

- **[R21]** Android Developers, *Build and test your Android App Bundle*, *bundletool*, and *Upload your app to the Play Console*.  
  <https://developer.android.com/guide/app-bundle/test>  
  <https://developer.android.com/tools/bundletool>  
  <https://developer.android.com/studio/publish/upload-bundle>

- **[R22]** Meng et al., *An Empirical Study of Flaky Tests in Android Apps*.  
  <https://people.cs.vt.edu/nm8247/publications/empirical-study-flaky-pdf.pdf>

- **[R23]** Pontillo, Palomba, Ferrucci, *Test Code Flakiness in Mobile Apps: The Developer’s Perspective*.  
  <https://www.sciencedirect.com/science/article/pii/S0950584923002495>

- **[R24]** Fazzini et al., *Use of test doubles in Android testing*.  
  <https://dl.acm.org/doi/10.1145/3510003.3510175>

- **[R25]** Cruz et al., *Test automation in open-source Android apps*.  
  <https://dl.acm.org/doi/10.1145/3324884.3416623>
