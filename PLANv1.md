# Build AndroidClaw v0 as a lightweight Android-native assistant host

This ExecPlan is a living document. The sections `Progress`, `Surprises & Discoveries`, `Decision Log`, and `Outcomes & Retrospective` must be kept up to date as work proceeds.

This repository is expected to start from an empty or near-empty Android project. The reader should assume no prior repository memory beyond this file and `AGENTS.md`.

## Purpose / Big Picture

AndroidClaw should let a user install a single APK, configure one model provider, open a main chat, create extra sessions, enable or import skills, schedule assistant tasks, and inspect permissions and health from a simple GUI. The app should feel like a phone-local host for the core OpenClaw ideas: session-aware, tool-aware, skill-aware, and automation-aware.

The compatibility target is not “run NanoClaw unchanged on Android.” The compatibility target is that the user can do the same classes of work that matter most on a phone: talk to a main session, keep isolated session history, load runtime skills, create recurring jobs, and use typed device tools through a lightweight interface.

The first release prioritizes lightness and usability over security hardening. A clean architecture is still required so future hardening can be added without rewriting the app.

## Progress

- [x] (2026-03-08 00:00Z) Initial ExecPlan drafted with architecture, scope, milestones, and validation rules.
- [x] (2026-03-08 10:20Z) Repository bootstrapping started from docs-only state: initialized git, added root Gradle/CI files, created a single-module Compose app shell, wired a manual app container, added a deterministic fake-provider chat path, built the first scheduler pure functions, and implemented bundled `SKILL.md` parsing plus JVM tests.
- [x] (2026-03-08 10:40Z) Bootstrap milestone for the first app shell is complete: `:app:assembleDebug`, `:app:testDebugUnitTest`, and `:app:lintDebug` all pass locally against a manually installed JDK 17 plus Android SDK 36 environment. `:app:connectedDebugAndroidTest` was not run because no device was attached through local `adb` or LDPlayer during this session.
- [x] (2026-03-08 10:40Z) Started the runtime milestone with a manual `AppContainer`, deterministic `FakeProvider`, typed tool registry, and a demo chat flow that can execute a slash-dispatched tool skill without network access.
- [x] (2026-03-08 10:40Z) Started the scheduler milestone with `once` / `interval` / `cron` models, a small cron parser plus next-run calculator, capabilities reporting, and a placeholder worker path that leaves durable task execution for the Room-backed milestone.
- [x] (2026-03-08 10:40Z) Started the skill milestone with bundled asset loading, `SKILL.md` frontmatter parsing, eligibility checks for required tools, slash-skill lookup, and three bundled demo skills.
- [x] Bootstrap the Android repository with a lean Gradle setup, Compose UI shell, and fast local validation commands.
- [ ] Implement the durable data model for sessions, messages, tasks, skills, settings, and logs.
- [ ] Implement the agent runtime with provider abstraction, deterministic fake provider, and message persistence.
- [ ] Implement the scheduler with once, interval, and cron support plus run-now execution.
- [ ] Implement the skill compatibility layer with `SKILL.md` parsing, bundled skills, local imports, and slash-command dispatch.
- [ ] Implement the baseline tool bus and core built-in tools.
- [ ] Implement the user-facing GUI for chat, tasks, skills, settings, permissions, and health.
- [ ] Add performance work, baseline profiles, and release-quality packaging checks.
- [ ] Add smoke tests and end-to-end validation that prove the app works without external chat context.

## Surprises & Discoveries

- Observation: NanoClaw’s small-core ideas are useful, but its runtime shape is still a desktop/server shape: a single Node.js process with SQLite, a polling loop, per-group queues, and containerized agent execution. Porting that runtime directly would work against the single-APK and lightweight Android goals.
  Evidence: The current upstream architecture is described as channels to SQLite to polling loop to containerized agent execution, and the listed requirements still include Node.js 20 and Docker or Apple Container.

- Observation: OpenClaw runtime skills are the correct compatibility model for AndroidClaw. The key artifact is a skill directory with `SKILL.md`, YAML frontmatter, instructions, and optional command dispatch metadata.
  Evidence: The current skills docs define a skill as a directory containing `SKILL.md`, optional local overrides, and frontmatter fields such as `user-invocable`, `disable-model-invocation`, and `command-tool`.

- Observation: The official OpenClaw Android app is a companion node, not a local host. AndroidClaw is therefore a deliberate new host implementation.
  Evidence: Current platform docs explicitly say Android does not host the Gateway.

- Observation: Android scheduling semantics are stricter than desktop daemon semantics. Periodic background work is not exact, exact alarms are special-use, and some interactive device capabilities are foreground-only.
  Evidence: Android documentation states that periodic WorkManager intervals are inexact and have a 15-minute minimum, exact alarms are reserved for precise user-facing events, and camera or screen-related actions are foreground constrained.

- Observation: Baseline Profiles matter early for this product because the target experience is “small and fast on ordinary phones,” not “eventually optimized later.”
  Evidence: Android performance guidance reports about a 30 percent first-launch code execution speed improvement from Baseline Profiles.

- Observation: This machine started without a usable Android build environment for a modern app. Only Java 8 was on `PATH`, there was no configured Android SDK, and `sdkmanager` stalled while fetching manifests even though the Google repository itself was reachable.
  Evidence: `java -version` reported `1.8.0_482`, `ANDROID_HOME` and `ANDROID_SDK_ROOT` were unset, and manual package downloads from `dl.google.com/android/repository/` succeeded while `sdkmanager` remained stuck on remote manifest fetches.

## Decision Log

- Decision: AndroidClaw will be a Kotlin-first Android-native implementation, not a Node.js or Docker port.
  Rationale: This is the only route that reliably preserves the single-APK, low-memory, low-complexity goals.
  Date/Author: 2026-03-08 / initial plan

- Decision: Compatibility is defined by four contracts: Session, Tool, Skill, and Automation.
  Rationale: These are the user-visible semantics that matter most. They allow compatibility without inheriting desktop runtime baggage.
  Date/Author: 2026-03-08 / initial plan

- Decision: v0 production code will live in one Android app module, with package-level layering instead of many Gradle modules.
  Rationale: Fewer modules mean lower build complexity, less agent confusion, and a simpler repository for the early phase.
  Date/Author: 2026-03-08 / initial plan

- Decision: Manual dependency wiring will be used instead of Hilt, Koin, or other DI frameworks.
  Rationale: Manual wiring is smaller, faster to inspect, and easier for future agents to reason about.
  Date/Author: 2026-03-08 / initial plan

- Decision: Data persistence will use Room for durable records and DataStore for lightweight settings.
  Rationale: Both are standard Android choices, stable, and light enough for this project.
  Date/Author: 2026-03-08 / initial plan

- Decision: The scheduler will use a hybrid model: WorkManager for durable background execution and AlarmManager only for explicitly precise, user-visible tasks.
  Rationale: This matches Android platform reality without pretending background work is a permanent daemon.
  Date/Author: 2026-03-08 / initial plan

- Decision: The first real provider implementation will use a lightweight HTTP client and a generic OpenAI-compatible style adapter, plus a deterministic FakeProvider for debug and tests.
  Rationale: The project needs one real network path and one fully local path. The local path is critical for agent-driven development and validation.
  Date/Author: 2026-03-08 / initial plan

- Decision: External messaging channels, browser automation, remote bridge mode, voice, camera flows, and screen flows are not part of v0.
  Rationale: They would expand scope and weight before the core contracts are proven.
  Date/Author: 2026-03-08 / initial plan

- Decision: Security hardening is intentionally deferred, but storage and runtime boundaries must still be clean.
  Rationale: The user explicitly wants lightness first. Clean seams now make hardening cheaper later.
  Date/Author: 2026-03-08 / initial plan

- Decision: Use AGP 8.13 with API 36 instead of AGP 9.x for the bootstrap.
  Rationale: AGP 8.13 officially supports API 36 and avoids taking on AGP 9-specific migration work while the repository is still being created from scratch.
  Date/Author: 2026-03-08 / implementation

- Decision: Treat scheduler work for this pass as a pure-function skeleton plus worker placeholder, not full durable automation execution.
  Rationale: The user asked to start M0/M1 implementation. The next unblocker is getting the app shell, skills parser, and schedule semantics under test before introducing Room-backed task state and real rescheduling.
  Date/Author: 2026-03-08 / implementation

## Outcomes & Retrospective

Not started yet. Add entries here as milestones complete. Each entry should compare what shipped against the original user-visible goal: install one APK, configure a provider, chat, create sessions, load skills, run tasks, and inspect health.

## Context and Orientation

AndroidClaw is a phone-local assistant app inspired by NanoClaw and OpenClaw.

In this plan, a **session** means a persistent conversation context with its own message history and lightweight summary. The special **main session** is the default self-chat and acts as the user’s control surface. A normal session is any user-created conversation.

A **tool** means a typed capability the agent can call through a stable interface. Tools are not shell commands. Each tool has a name, input shape, output shape, and capability metadata such as whether the tool requires the app to be in the foreground.

A **skill** means a directory containing `SKILL.md` plus optional supporting files. Skills can be bundled with the APK or imported by the user. A skill can add instructions to the model prompt and can optionally expose a slash command. AndroidClaw should be compatible with the useful OpenClaw-style runtime skill semantics, not the NanoClaw “modify the source tree with Claude Code” workflow.

An **automation** means a scheduled agent task. It can run once, on a repeating interval, or from a cron expression. It can run in **main-session** mode, where results land directly in the target session, or **isolated** mode, where the run has its own transient execution context and only the final result is posted back.

This repository should not be treated as a desktop port. It is a new Android host that borrows the right semantics from upstream projects and expresses them through Android-native storage, scheduling, permissions, and UI.

## Scope and Non-goals for v0

The goal of v0 is a real, testable Android host with the four compatibility contracts. It is not a full clone of everything upstream can do.

The following are explicit non-goals for v0:

- External messaging channels such as Telegram, WhatsApp, Discord, Slack, and Gmail
- Browser automation, embedded Chromium, or heavy WebView-centric runtime features
- Remote gateway pairing or remote bridge mode
- Arbitrary shell or host command execution
- Background camera, screen recording, continuous voice, or wake-word flows
- Cloud sync, accounts, or multi-device state sharing
- Full sandboxing, encryption, and enterprise-grade security hardening

If one of these seems necessary during implementation, record the need in `Decision Log`, keep the current milestone scoped, and defer the feature unless it is truly required for the four contracts.

## Compatibility contracts

### Session Contract

AndroidClaw must provide:

- one non-deletable main session
- creation, rename, archive, and selection of normal sessions
- persistent message history per session
- a place for a lightweight session summary so future compaction can be added
- message persistence across process death and relaunch

### Tool Contract

AndroidClaw must provide:

- a typed tool registry
- stable tool names
- tool availability metadata
- a clear way to mark tools as unsupported, foreground-required, or permission-blocked
- a deterministic path for tool execution during tests

### Skill Contract

AndroidClaw must provide:

- loading of bundled skills from APK assets
- import of local skills into app-private storage
- parsing of `SKILL.md` and frontmatter
- enable and disable controls
- slash-command dispatch for user-invocable skills
- skill precedence of workspace over local over bundled when the same skill id exists

### Automation Contract

AndroidClaw must provide:

- once, interval, and cron schedules
- durable task storage
- main-session and isolated execution modes
- a run-now action for manual validation
- last-run state, next-run state, and failure visibility in the GUI

## Target repository structure

After the bootstrap milestone, the repository should roughly look like this. Create these files if they do not exist yet.

- `AGENTS.md`
- `PLAN.md`
- `settings.gradle.kts`
- `build.gradle.kts`
- `gradle/libs.versions.toml`
- `app/build.gradle.kts`
- `app/src/main/AndroidManifest.xml`
- `app/src/main/java/ai/androidclaw/app/AndroidClawApplication.kt`
- `app/src/main/java/ai/androidclaw/app/MainActivity.kt`
- `app/src/main/java/ai/androidclaw/ui/`
- `app/src/main/java/ai/androidclaw/feature/chat/`
- `app/src/main/java/ai/androidclaw/feature/tasks/`
- `app/src/main/java/ai/androidclaw/feature/skills/`
- `app/src/main/java/ai/androidclaw/feature/settings/`
- `app/src/main/java/ai/androidclaw/feature/health/`
- `app/src/main/java/ai/androidclaw/data/db/`
- `app/src/main/java/ai/androidclaw/data/repo/`
- `app/src/main/java/ai/androidclaw/runtime/providers/`
- `app/src/main/java/ai/androidclaw/runtime/orchestrator/`
- `app/src/main/java/ai/androidclaw/runtime/sessions/`
- `app/src/main/java/ai/androidclaw/runtime/tools/`
- `app/src/main/java/ai/androidclaw/runtime/skills/`
- `app/src/main/java/ai/androidclaw/runtime/scheduler/`
- `app/src/main/assets/skills/`
- `app/src/test/`
- `app/src/androidTest/`

If a Baseline Profile generator is added later, a test-only `baselineprofile/` module is allowed.

Use `ai.androidclaw.app` as the temporary application id and Kotlin package root if no better package name exists yet. Keep the package definition easy to rename later.

## Core data model

Implement the first durable schema with Room. Keep it small and future-proof rather than over-normalized.

The minimum entities are:

- `SessionEntity`: id, title, kind, archived, createdAt, updatedAt, summary
- `MessageEntity`: id, sessionId, role, kind, text, payloadJson, createdAt, status
- `TaskEntity`: id, title, sessionId, executionMode, scheduleType, scheduleSpec, precise, enabled, nextRunAt, lastRunAt, lastStatus, retryCount, prompt, skillFilterJson
- `SkillEntity`: id, sourceType, baseDir, frontmatterJson, instructionsMd, enabled, lastLoadedAt, hash
- `SettingEntity` or DataStore-backed settings for provider config, selected session, and feature flags
- `EventLogEntity`: id, level, category, relatedId, message, payloadJson, createdAt

Keep DAOs simple. Repositories should expose flows that the UI can observe without pulling every table into memory.

## Runtime architecture

The runtime should stay explicit and legible.

Create an app container in `AndroidClawApplication` that wires together:

- Room database
- repositories
- provider registry
- tool registry
- skill manager
- scheduler coordinator
- agent runner
- settings store

The **agent runner** is the heart of the runtime. It should:

1. load the target session state
2. load enabled skills relevant to the request
3. assemble a system prompt and tool list
4. call the configured provider
5. execute tool calls when the provider requests them
6. persist user, assistant, and tool messages
7. stop when the provider returns a final assistant message or a loop limit is hit

Set a small maximum tool-call loop count such as six turns per request. Record loop exits and errors in the event log.

Do not hide orchestration in magic helpers. Keep the flow inspectable.

## Provider strategy

Create a `ModelProvider` interface in `runtime/providers/`.

The interface should support a request that includes:

- prior messages
- a system instruction block
- enabled skill instructions
- tool schemas
- optional metadata such as session id and whether the run is interactive or scheduled

Ship two provider implementations in v0:

1. `FakeProvider` for debug builds and tests. This provider must be deterministic and able to exercise ordinary reply flows plus simple tool-call flows without any network access.
2. `OpenAiCompatibleProvider` using OkHttp plus kotlinx serialization. Keep this adapter small and dependency-light. Avoid large generated SDKs.

The provider layer must be replaceable later so an Anthropic-compatible adapter can be added without touching UI or storage.

## Tool bus

Implement a typed tool registry. Each tool needs a stable name, a request parser, a result serializer, and availability metadata.

The v0 built-in tools should be:

- `sessions.list`
- `sessions.create`
- `sessions.rename`
- `sessions.archive`
- `tasks.list`
- `tasks.create`
- `tasks.update`
- `tasks.pause`
- `tasks.resume`
- `tasks.run`
- `skills.list`
- `skills.enable`
- `skills.disable`
- `skills.import`
- `device.info`
- `device.permissions`
- `health.status`
- `notifications.post`
- `files.list`
- `files.read`
- `files.write`
- `http.fetch`
- `contacts.search`
- `calendar.events`

Keep `files.*` constrained to app-private workspace roots. The project is intentionally not doing arbitrary filesystem access in v0.

Mark any capability that needs the app in the foreground as `foregroundRequired` so the runtime can refuse cleanly instead of pretending it can run.

## Skill compatibility layer

Implement the skill manager in `runtime/skills/`.

The loader should support three sources:

- bundled skills from `app/src/main/assets/skills/`
- local imported skills under app-private storage such as `files/skills/local/<skill-id>/`
- workspace skills under app-private storage such as `files/workspaces/<session-id>/skills/<skill-id>/`

Precedence must be:

1. workspace
2. local
3. bundled

A skill is a directory containing `SKILL.md`. Parse frontmatter and preserve unknown keys, but v0 only needs to actively support:

- `name`
- `description`
- `homepage`
- `user-invocable`
- `disable-model-invocation`
- `command-dispatch`
- `command-tool`
- `metadata`

Treat `metadata` as a future-safe JSON blob. v0 may use `metadata.android.permissions` and `metadata.android.foregroundRequired` if present.

Provide at least three bundled skills for testing and demos:

- a plain prompt skill that helps summarize a session
- a plain prompt skill that helps create reminders or tasks
- a tool-dispatch skill whose slash command calls `tasks.list`

Do not implement installer-style skills, package-manager hooks, or filesystem watchers in v0. Refresh the skill snapshot on app start, after import, and after enable or disable actions.

## Session and workspace model

Create one non-deletable main session at first launch.

Each session gets an app-private workspace root such as `files/workspaces/<session-id>/`.

For isolated automation runs, create a transient workspace root such as `files/workspaces/isolated/<task-id>/<run-id>/`. Isolation in v0 means separate runtime context and separate workspace root. It does not mean sandboxing or process isolation.

Persist all user-visible outputs back into the target session so results survive app restarts.

## Scheduler design

Implement the scheduler in `runtime/scheduler/`.

Use a durable database-backed scheduler model. The source of truth is always the task table, not the in-memory process.

Support three schedule types:

- once at a specific timestamp
- interval with a minimum background interval of 15 minutes
- cron using five fields plus macros such as `@hourly`, `@daily`, `@weekly`, and `@monthly`

For cron, prefer a small in-repo parser and next-run calculator with thorough tests. If that becomes unreliable, evaluate a small dependency as a documented spike before adopting it.

Use this execution strategy:

- Most work should be scheduled as unique one-time WorkManager jobs for the next due time.
- Recompute and enqueue the next run after every successful or failed execution.
- Use AlarmManager exact alarms only when the task is explicitly marked precise and the behavior is truly user-facing, such as a reminder notification.
- If exact alarms are unavailable, degrade gracefully and show that in the GUI.

Do not fake a daemon with background polling loops or abusive keep-alive behavior.

Implement two execution modes:

- `MAIN_SESSION`: the task prompt runs against the target session and posts results there directly.
- `ISOLATED_SESSION`: the task prompt runs against an isolated transient context, then posts the final result back to the target session and task log.

Every task must support a manual **Run now** action. This is mandatory for development, demoing, and repair.

## GUI design

Use Jetpack Compose and keep the UI flat, direct, and lightweight.

The minimum navigation surface is five tabs or top-level destinations:

- Chat
- Tasks
- Skills
- Settings
- Health

### Chat screen

The chat screen must provide:

- current session selector
- message list
- composer
- visible loading state
- slash-command entry path for user-invocable skills
- a simple action to create a new session

Do not hide session management behind multiple deep menus.

### Tasks screen

The tasks screen must provide:

- list of all tasks
- next run time
- last run status
- enable, pause, resume, edit, delete, and run-now actions
- task creation form with explicit controls for schedule type, cron expression or interval, precise toggle, target session, and execution mode

Do not rely on natural-language schedule parsing in v0. Use explicit form controls.

### Skills screen

The skills screen must provide:

- list of bundled and imported skills
- source type and enabled state
- visible missing-capability or permission warnings
- import action using document picker and zip extraction
- details view showing frontmatter and instruction text

### Settings screen

The settings screen must provide:

- provider type selector
- endpoint, model, and API key fields
- fake-provider toggle for debug builds
- notification permission status
- exact alarm permission status if relevant
- export logs or clear local data actions if cheap to add

### Health screen

The health screen must provide:

- provider configured or not
- selected session
- next scheduled task
- last scheduler wake and last automation result
- notification permission state
- battery optimization state if available
- app version and database size
- concise guidance for OEM battery restrictions on devices such as OPPO, vivo, and Xiaomi

## Performance plan

This project must behave like a lightweight phone app, not a mini desktop environment.

Implement these choices early:

- manual DI instead of large DI frameworks
- no persistent foreground service while idle
- no eager loading of entire histories or skill trees at startup
- lazy lists and paged message queries
- bounded event log table or periodic trimming
- bounded in-memory caches
- no embedded browser runtime in the base app
- no generated SDK bloat when raw HTTP is enough

After the core screens exist, add Baseline Profiles for these journeys:

- cold start to main chat
- open task list
- open skill list
- open settings

If a `baselineprofile/` test-only module is used, keep it narrow and only for performance work.

## Milestone 1 — Bootstrap a lean Android repository

Create a fresh Android app using current stable Android tooling. Use Compose Material 3, Kotlin, Gradle Kotlin DSL, and a single `:app` module for production code. Use `minSdk = 26`. Set `compileSdk` and `targetSdk` to the current stable SDK supported by the installed Android Gradle Plugin, and never choose a target below the current Play minimum. Add the smallest reasonable dependency set: Compose, lifecycle, Room, WorkManager, DataStore, OkHttp, kotlinx serialization, coroutines test, and Android test libraries.

Create `AndroidClawApplication`, `MainActivity`, theme, navigation shell, and placeholder top-level screens for Chat, Tasks, Skills, Settings, and Health. The app should launch and navigate without crashes before any runtime logic exists.

Add root validation commands and a simple CI workflow that runs build, JVM tests, and lint without requiring an emulator.

At the end of this milestone, a developer should be able to clone the repo, run the app, switch between placeholder screens, and run the fast validation suite successfully.

## Milestone 2 — Add durable storage and repositories

Implement Room entities, DAOs, and repositories for sessions, messages, tasks, skills, and event logs. Add first-launch initialization that creates the main session and initial app settings.

Create repository APIs that are friendly to both UI and runtime code. Keep write operations explicit and small. Add repository tests for creation, update, archive, task persistence, and skill persistence. Add one instrumentation test that proves Room migrations or schema creation works on-device.

At the end of this milestone, all user-facing state should persist across process death, even though the provider and scheduler are still placeholders.

## Milestone 3 — Implement the agent runner and provider abstraction

Create the `ModelProvider` interface, the deterministic `FakeProvider`, and the first real HTTP provider adapter. Implement the agent runner loop that persists user messages, loads skills, offers tools, handles provider tool calls, persists tool messages, and writes final assistant messages back to the session.

Connect the Chat screen to this runtime. The chat UI should support sending a message, showing pending state, streaming or incremental display if feasible, and rendering the final response. The debug build must work entirely with FakeProvider so the app remains testable without network credentials.

At the end of this milestone, a user should be able to open the app, send a message in the main session, and get a deterministic reply using FakeProvider or a real reply using the configured HTTP provider.

## Milestone 4 — Implement the scheduler and automation runs

Create the scheduler coordinator, task creation flow, cron and interval parsing, and the automation worker path. Implement `Run now` first, then persistent scheduled execution.

Ensure that every task stores last-run status, next-run timestamp, and a readable failure reason. Implement execution mode switching between main-session and isolated-session runs.

Do not optimize for perfect exactness. Optimize for durability, correctness, and honest visibility in the UI. If the platform delays a run, the app should still show what happened and compute the next due time correctly.

At the end of this milestone, a user should be able to create a task, run it immediately, see the result in the target session, then relaunch the app and still see the saved task state.

## Milestone 5 — Implement the skill compatibility layer

Create the skill parser, storage model, loader precedence, and GUI integration. Load bundled skills from assets and support user import from a zip file through the document picker.

Add slash-command support in the chat composer for user-invocable skills. For tool-dispatch skills, bypass the model and call the named tool directly. For ordinary instruction skills, include their instructions in the agent prompt when enabled.

Surface capability warnings clearly. If a skill references a missing tool or a permission-gated feature, show that state in the Skills screen and keep the skill disabled or degraded instead of failing silently.

At the end of this milestone, a user should be able to enable a bundled skill, import a local skill, use a slash command, and observe either direct tool behavior or modified assistant behavior.

## Milestone 6 — Implement the baseline tool bus

Build the typed tool registry and the v0 built-in tools listed earlier. Start with session and task tools because they are easiest to validate. Then add `device.info`, `device.permissions`, `health.status`, notifications, files, HTTP fetch, contacts search, and calendar events.

Keep each tool in a small, explicit class or file. Validate JSON parsing strictly at the boundary. Every tool should return structured success or structured failure, never vague strings that are hard to test.

Use the fake provider and unit tests to prove tool calling works. The fake provider should be able to request a tool call and consume the returned structured output.

At the end of this milestone, the runtime should be able to answer tool-using prompts, slash-dispatched tool skills, and task-management prompts without special-case branching in the chat UI.

## Milestone 7 — Replace placeholders with the real admin GUI

Turn each placeholder screen into a working management surface.

The Chat screen becomes the primary interaction surface. The Tasks screen becomes a full task list and editor. The Skills screen becomes the install and capability view. The Settings screen becomes the provider configuration and maintenance view. The Health screen becomes the system status dashboard.

Add clear empty states, loading states, and failure states. The UI should look simple and deliberate, not dashboard-heavy or visually noisy.

At the end of this milestone, a human tester should be able to manage the whole v0 app from the GUI without adb, without editing files, and without hidden developer menus.

## Milestone 8 — Performance, baseline profiles, and release checks

Add Baseline Profile support and generate profiles for startup and the most common navigation flows. Trim any heavyweight dependencies that crept in. Review startup work and move non-essential initialization off the critical path.

Add one release-oriented pass over package size, log trimming, lazy loading, and background behavior. Confirm that the app does not start an idle foreground service and that it can recover cleanly from process death.

At the end of this milestone, the release build should feel like a normal lightweight Android app rather than an experimental desktop runtime in a phone wrapper.

## Plan of Work

The work sequence matters. Build vertical slices that stay runnable.

Start by making the app boot and navigate. Then make state durable. Then make the chat runtime real with a fake provider path. Then make automation real with a run-now path. Then layer in skills and tools. Only after the runtime is coherent should the full admin GUI and performance work land.

Prefer additive changes. Keep the app working after each milestone. If a milestone needs a risky experiment, keep the experiment narrow, record the result in `Surprises & Discoveries`, and either promote or discard it quickly.

## Concrete Steps

Use the repository root as the working directory unless otherwise noted.

Bootstrap and fast checks:

    ./gradlew :app:assembleDebug
    ./gradlew :app:testDebugUnitTest
    ./gradlew :app:lintDebug

When an emulator or device is available, run the instrumentation smoke tests:

    ./gradlew :app:connectedDebugAndroidTest

When the baseline profile module exists and startup-critical code changed, generate profiles:

    ./gradlew :app:generateBaselineProfile

Install and run a debug build manually when needed:

    adb install -r app/build/outputs/apk/debug/app-debug.apk
    adb shell am start -n ai.androidclaw.app/.MainActivity

If the package path differs after bootstrap, update this section and keep it correct.

## Validation and Acceptance

Validation is behavioral, not just compilation.

The minimum acceptance flow for v0 is:

1. Install the APK on an emulator or device.
2. Launch the app and confirm the main session exists automatically.
3. Open Settings and configure either FakeProvider or a real HTTP provider.
4. Open Chat, send a message, and receive a response.
5. Create a second session and switch between sessions without losing history.
6. Open Skills, enable a bundled skill, then use that skill through chat or slash command.
7. Open Tasks, create a scheduled task, confirm the next run is computed, and use Run now.
8. Confirm the task result is written into the correct session and appears in task history.
9. Force-stop or kill the app, relaunch it, and verify sessions, tasks, and skills persist.
10. Open Health and confirm that permission and scheduler status are readable to a human tester.

The required automated checks before calling a milestone done are:

- `./gradlew :app:assembleDebug`
- `./gradlew :app:testDebugUnitTest`
- `./gradlew :app:lintDebug`
- `./gradlew :app:connectedDebugAndroidTest` when the environment provides a device or emulator

The release-quality check for the final milestone also includes Baseline Profile generation when that module exists.

## Idempotence and Recovery

All plan steps should be safe to repeat.

- Re-running Gradle validation commands should be harmless.
- Re-opening the app after a crash or force-stop should restore durable state from Room and DataStore.
- Importing a skill with an existing id should replace the older same-source copy only after the new copy is fully extracted and parsed.
- Scheduler resync should be possible at app launch by recomputing due tasks from the database instead of trusting in-memory state.
- If a provider call fails halfway, persist a readable failure event and keep the session consistent by recording either an error event or an assistant-visible failure message, not a silently dropped interaction.

If a migration or schema change becomes necessary later, add migration tests before landing it.

## Artifacts and Notes

The repository should eventually contain a few small artifacts that make the system more legible to future agents and humans:

- bundled sample skills under `app/src/main/assets/skills/`
- a debug-only FakeProvider path that keeps end-to-end testing offline
- a tiny set of instrumentation smoke tests proving first-launch, chat, task creation, and skill activation
- optional later child plans under `docs/exec-plans/active/` if work branches into separate subsystems

Keep these artifacts small, checked in, and easy for future Codex runs to discover.
