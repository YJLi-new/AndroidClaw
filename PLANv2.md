# AndroidClaw v0 Execution Plan — Phase 2

> Archived on 2026-03-09. `PLANv4.md` is the canonical execution plan. Keep this file for historical reference only.

This plan picks up from the bootstrap skeleton completed on 2026-03-08 and drives the project to a working v0 that satisfies the acceptance flow defined in the original plan (install → chat → persist → schedule → skill → survive kill).

This is a Codex-executable plan. Every milestone has ordered concrete steps, validation commands, and acceptance criteria. Do not skip ahead. Do not move to M(n+1) until M(n) passes all its validation.

Before starting any milestone, read `AGENTS.md`, `docs/ARCHITECTURE.md`, `docs/SCHEDULER.md`, and `docs/SKILLS_COMPAT.md`. These documents define the hard constraints and architectural boundaries that all code must respect. If a milestone requires changing an architectural constraint, update the relevant doc first, then code.

---

## Guiding Principles for This Phase

1. **Persistence first.** The single biggest gap in the bootstrap skeleton is that nothing is durable. Every milestone in this plan either adds persistence or depends on persistence being present.
2. **One vertical slice at a time.** Each milestone should leave the app in a runnable state. No half-wired layers.
3. **Test alongside code.** Every new public class or module must ship with at least one JVM unit test covering its happy path and its most important failure path. Do not defer tests to a later milestone.
4. **Respect the layer rules.** UI never touches DAO directly. ViewModel never leaks Room entities to Composables. Runtime never imports Compose. All boundary crossings use mapped domain models.
5. **Keep it small.** Avoid introducing new dependencies unless absolutely necessary. The current dependency set (Room, WorkManager, OkHttp, SnakeYAML, kotlinx-serialization, Compose, Navigation, DataStore) should be sufficient for all of v0.

---

## Progress

Update this section as each milestone completes. Use the format:
- [x] (date) Milestone slug — one-line summary of what shipped.
- [ ] Milestone slug — not started or in progress.

Current state:
- [x] (2026-03-08) M1 — Room schema, DAOs, exported schema JSON, and JVM DAO tests shipped and validated.
- [x] (2026-03-08) M2 — Domain models, repository layer, schedule serialization, and repository JVM tests shipped and validated.
- [x] (2026-03-08) M3 — Chat persistence, main-session bootstrap, multi-session switching, and ChatViewModel tests shipped and validated.
- [x] (2026-03-08) M4 — Feature ViewModels, dependency bundles, and feature/runtime layer-rule cleanup shipped and validated.
- [x] (2026-03-08) Post-M4 audit remediation — chat and tool failures now degrade gracefully, bundled skills are cached, the settings path no longer advertises a plain-text API key slot, and missing feature/runtime JVM tests shipped alongside the fixes.
- [ ] M5 — ModelRequest enrichment and provider contract hardening
- [ ] M6 — Tool contract hardening and real built-in tools
- [ ] M7 — Scheduler durable execution with WorkManager
- [ ] M8 — Skill persistence, import, enable/disable lifecycle
- [ ] M9 — Full GUI wiring — all screens functional
- [ ] M10 — Test hardening, lint cleanup, and release prep

---

## Surprises & Discoveries

Carry forward all entries from the bootstrap plan. Add new entries here as they emerge during execution.
- Room's `room.generateKotlin` compiler argument is KSP-only. With the current Gradle setup using KAPT, enabling it breaks the build immediately.
- Robolectric attempting to boot Android SDK 36 under Java 17 fails before tests run. Pinning JVM tests to SDK 35 avoids forcing a Java 21 toolchain change during v0.
- The runtime already had the right scheduler and skill enums for part of M2. Reusing `TaskSchedule`, `TaskExecutionMode`, `SkillSourceType`, and `SkillEligibilityStatus` kept the repository layer aligned with existing runtime semantics and avoided duplicate type families.
- A small dependency bundle per feature is a workable bridge between today's manual `AppContainer` wiring and the stricter M4 rule that feature code must not depend on the full container. `ChatDependencies` was enough to remove `AppContainer` from the chat ViewModel path without forcing a broader refactor yet.
- The same bundle pattern scales cleanly across the rest of the app. `DependencyBundles.kt` was sufficient to make `AppContainer` the composition root only, while keeping ViewModel factories explicit and lightweight.
- The Linux-side Gradle build and the Windows-hosted LDPlayer emulator do not automatically share an ADB view. In this session, Windows-side `adb` could see `emulator-5554`, but the local SDK `adb` still reported no devices, so `connectedDebugAndroidTest` remained blocked even after the emulator booted.

---

## Decision Log

Carry forward all entries from the bootstrap plan. Add new entries here using the same format:
- Decision: ...
  Rationale: ...
  Date/Author: ...
- Decision: Keep Room annotation processing on KAPT for Milestone 1 instead of introducing KSP mid-stream.
  Rationale: The project already builds cleanly with KAPT, and adding KSP would expand build-system scope without improving the Room schema or DAO acceptance criteria for this milestone.
  Date/Author: 2026-03-08 / Codex
- Decision: Pin Robolectric JVM tests to SDK 35 via `app/src/test/resources/robolectric.properties`.
  Rationale: The app still compiles and targets SDK 36, but Robolectric's SDK 36 sandbox requires Java 21. SDK 35 keeps local JVM tests deterministic on the existing Java 17 toolchain.
  Date/Author: 2026-03-08 / Codex
- Decision: Reuse runtime scheduler and skill types in `data.model` wherever stable equivalents already existed.
  Rationale: `TaskSchedule`, `TaskExecutionMode`, `SkillSourceType`, and `SkillEligibilityStatus` were already the compatibility surface the runtime uses. Reusing them prevents mapper drift and keeps future runtime wiring simpler.
  Date/Author: 2026-03-08 / Codex
- Decision: Persist task schedules with an explicit JSON payload managed by `ScheduleSerializer`, using a canonical cron expression string for cron schedules.
  Rationale: The Room schema stores a string blob, but the runtime needs typed schedules. A small explicit serializer keeps storage inspectable, deterministic in tests, and independent of reflection-heavy polymorphic serialization.
  Date/Author: 2026-03-08 / Codex
- Decision: Bootstrap the main session from `AndroidClawApplication` using an application-scoped `CoroutineScope` instead of `GlobalScope`.
  Rationale: Startup still needs one-shot background initialization, but an app-scoped supervisor job keeps ownership explicit and avoids introducing a process-lifetime global coroutine with no parent.
  Date/Author: 2026-03-08 / Codex
- Decision: Introduce `ChatDependencies` as the narrow factory input for `ChatViewModel`.
  Rationale: M3 needed chat to stop depending on `AppContainer` before the broader M4 dependency-bundle cleanup. A small bundle keeps the change local and matches the direction already laid out for later milestones.
  Date/Author: 2026-03-08 / Codex
- Decision: Standardize feature ViewModel factory inputs in `ai.androidclaw.app.DependencyBundles.kt`.
  Rationale: This keeps `AppContainer` at the composition root only, avoids passing the full container into feature code, and gives M4 a single explicit dependency boundary for all screen-level factories.
  Date/Author: 2026-03-08 / Codex
- Decision: Add a minimal `SettingsDataStore` now, even though settings UI remains shallow until later milestones.
  Rationale: M4 needed a real non-`AppContainer` dependency for `SettingsViewModel`. A tiny Preferences DataStore wrapper satisfies the layer rule now and gives later provider settings work a stable persistence hook.
  Date/Author: 2026-03-08 / Codex
- Decision: Remove the placeholder plain-text API key field from `SettingsDataStore` before M5 introduces real provider configuration.
  Rationale: Carrying a fake secret slot in plain preferences would create the wrong seam just before provider work expands. It is safer to keep provider selection in DataStore and defer credentials to a later Keystore-backed path.
  Date/Author: 2026-03-08 / Codex

---

## Outcomes & Retrospective

Add one entry per completed milestone. Each entry must answer: does the app now satisfy one more step of the v0 acceptance flow?
- M1 (2026-03-08): The app now has a durable Room schema for sessions, messages, tasks, task runs, skills, and event logs, with DAO behavior validated by JVM tests. This does not complete the user-visible "persist" acceptance step yet, but it establishes the persistence substrate required for M2 and M3 to finish that flow.
- M2 (2026-03-08): The app now has a repository and domain-model layer between Room and the runtime, with typed schedule and skill mappings validated by JVM tests. This still does not complete the user-visible persistence flow, but it removes the main architectural blocker for wiring persistent chat in M3 without leaking Room entities into runtime or UI code.
- M3 (2026-03-08): Chat now persists user and assistant messages to Room, the main session is auto-created and seeded with a system readiness message, and the UI can create and switch sessions while observing repository-backed history. This completes the core "chat → persist → survive relaunch" part of the acceptance flow, pending manual device verification on an emulator or phone.
- M4 (2026-03-08): Every current feature screen now has a dedicated ViewModel path, `AppContainer` is confined to the composition root, and both feature and runtime code are free of direct Room imports. This does not add major new end-user capabilities by itself, but it enforces the layering needed for M5-M9 to land without backsliding into container- or DAO-coupled feature code.
- Post-M4 audit remediation (2026-03-08): The shipped M1-M4 slice now handles provider and tool failures without wedging chat, avoids reparsing bundled skills on every refresh path, turns the task `runNow` placeholder into an explicit unsupported state, and has JVM coverage for the previously untested feature ViewModels plus the new failure paths. This does not complete M5, but it closes the most obvious reliability and test gaps before provider and tool expansion continues.

---

## Milestone 1 — Room data model and migration infrastructure

### Goal
Create all Room entities, DAOs, the database class, and the migration test infrastructure. After this milestone the database can be created, written to, and read from in JVM tests. No UI wiring yet.

### Why first
Everything downstream (persistent chat, durable tasks, skill records, event log) depends on the schema existing.

### Concrete Steps

1. Create package `ai.androidclaw.data.db.entity` with the following Room entities. Use `@Entity` annotations with explicit `tableName`. All primary keys are `String` (UUID-based, generated at creation time). All timestamp fields are `Long` (epoch millis) for Room compatibility.

   **SessionEntity**
   - `id: String` (PK)
   - `title: String`
   - `isMain: Boolean` (exactly one row has `true`; enforce in repository, not in schema)
   - `createdAt: Long`
   - `updatedAt: Long`
   - `archivedAt: Long?`
   - `summaryText: String?`

   **MessageEntity**
   - `id: String` (PK)
   - `sessionId: String` (indexed, FK to SessionEntity)
   - `role: String` (one of: `user`, `assistant`, `tool_call`, `tool_result`, `system`)
   - `content: String`
   - `createdAt: Long`
   - `providerMeta: String?` (JSON blob, nullable; stores provider-specific metadata)
   - `toolCallId: String?` (links tool_result back to its tool_call)
   - `taskRunId: String?` (nullable; set when message was produced by a scheduled run)

   **TaskEntity**
   - `id: String` (PK)
   - `name: String`
   - `prompt: String`
   - `scheduleKind: String` (one of: `once`, `interval`, `cron`)
   - `scheduleSpec: String` (JSON blob encoding the schedule parameters: instant for once, anchorAt+intervalMs for interval, cronExpr+zoneId for cron)
   - `executionMode: String` (one of: `MAIN_SESSION`, `ISOLATED_SESSION`)
   - `targetSessionId: String?` (nullable; if `MAIN_SESSION`, references the main session)
   - `enabled: Boolean`
   - `precise: Boolean` (if true, use AlarmManager exact path)
   - `nextRunAt: Long?`
   - `lastRunAt: Long?`
   - `failureCount: Int`
   - `maxRetries: Int`
   - `createdAt: Long`
   - `updatedAt: Long`

   **TaskRunEntity**
   - `id: String` (PK)
   - `taskId: String` (indexed, FK to TaskEntity)
   - `status: String` (one of: `PENDING`, `RUNNING`, `SUCCESS`, `FAILURE`, `SKIPPED`)
   - `scheduledAt: Long`
   - `startedAt: Long?`
   - `finishedAt: Long?`
   - `errorCode: String?`
   - `errorMessage: String?`
   - `resultSummary: String?`
   - `outputMessageId: String?` (FK to MessageEntity)

   **SkillRecordEntity**
   - `id: String` (PK, matches the skill directory name or a generated import id)
   - `sourceType: String` (one of: `bundled`, `local`, `workspace`)
   - `enabled: Boolean`
   - `displayName: String`
   - `description: String`
   - `frontmatterJson: String?` (serialized SkillFrontmatter as JSON)
   - `eligibilityStatus: String`
   - `eligibilityReasons: String` (JSON array of strings)
   - `importedAt: Long?`
   - `updatedAt: Long`

   **EventLogEntity**
   - `id: String` (PK)
   - `timestamp: Long` (indexed)
   - `category: String` (one of: `provider`, `tool`, `scheduler`, `skill`, `system`, `debug`)
   - `level: String` (one of: `info`, `warn`, `error`)
   - `message: String`
   - `detailsJson: String?`

2. Create package `ai.androidclaw.data.db.dao` with DAO interfaces:

   **SessionDao**
   - `insert(session: SessionEntity)`
   - `update(session: SessionEntity)`
   - `getById(id: String): SessionEntity?`
   - `getMainSession(): SessionEntity?`
   - `getAllSessions(): Flow<List<SessionEntity>>` (ordered by updatedAt desc, exclude archived)
   - `getArchivedSessions(): Flow<List<SessionEntity>>`

   **MessageDao**
   - `insert(message: MessageEntity)`
   - `insertAll(messages: List<MessageEntity>)`
   - `getBySessionId(sessionId: String): Flow<List<MessageEntity>>` (ordered by createdAt asc)
   - `getRecentBySessionId(sessionId: String, limit: Int): List<MessageEntity>` (suspend, ordered by createdAt desc, used for building model context)
   - `countBySessionId(sessionId: String): Int`
   - `deleteBySessionId(sessionId: String)`

   **TaskDao**
   - `insert(task: TaskEntity)`
   - `update(task: TaskEntity)`
   - `getById(id: String): TaskEntity?`
   - `getAllTasks(): Flow<List<TaskEntity>>` (ordered by nextRunAt asc nulls last)
   - `getEnabledTasksDueBefore(instant: Long): List<TaskEntity>` (suspend)
   - `delete(id: String)`

   **TaskRunDao**
   - `insert(run: TaskRunEntity)`
   - `update(run: TaskRunEntity)`
   - `getByTaskId(taskId: String): Flow<List<TaskRunEntity>>` (ordered by scheduledAt desc)
   - `getLatestByTaskId(taskId: String): TaskRunEntity?`
   - `deleteOlderThan(timestamp: Long)` (for log trimming)

   **SkillRecordDao**
   - `upsert(record: SkillRecordEntity)` (insert or replace)
   - `upsertAll(records: List<SkillRecordEntity>)`
   - `getAll(): Flow<List<SkillRecordEntity>>`
   - `getEnabled(): List<SkillRecordEntity>` (suspend)
   - `getById(id: String): SkillRecordEntity?`
   - `delete(id: String)`

   **EventLogDao**
   - `insert(event: EventLogEntity)`
   - `getRecent(limit: Int): Flow<List<EventLogEntity>>` (ordered by timestamp desc)
   - `deleteOlderThan(timestamp: Long)`
   - `count(): Int`

3. Create `ai.androidclaw.data.db.AndroidClawDatabase`:
   - `@Database` annotation listing all six entities.
   - Version = 1. No migrations yet; use `fallbackToDestructiveMigration()` in the builder for v0 only. Add a TODO comment that production releases must use proper migrations.
   - Declare abstract DAO accessors for all six DAOs.
   - Provide a `companion object` with a `build(context: Context): AndroidClawDatabase` factory that uses `Room.databaseBuilder` with database name `"androidclaw.db"`.

4. Create package `ai.androidclaw.data.db.migration` with an empty `Migrations.kt` file containing a comment: `// Add migration objects here when schema changes occur after v0.`

5. Write JVM unit tests (under `src/test/`) for every entity's construction and every DAO query using Robolectric or a plain Room in-memory test database.

   Specifically, create these test files:

   **SessionDaoTest** — insert a session, query by id, query main session, verify ordering.
   **MessageDaoTest** — insert messages for a session, query by session id, verify ordering and limit.
   **TaskDaoTest** — insert tasks, query due tasks before a timestamp, verify enabled filter.
   **TaskRunDaoTest** — insert runs, query by task id, verify latest-run query.
   **SkillRecordDaoTest** — upsert records, verify getEnabled filter.
   **EventLogDaoTest** — insert events, query recent, verify trimming.

   Each test file must use `@RunWith(AndroidJUnit4::class)` and `Room.inMemoryDatabaseBuilder` for the database instance. Add `testImplementation(libs.androidx.test.core)` and `testImplementation(libs.robolectric)` to `build.gradle.kts` dependencies if not already present. Add the Robolectric version to `libs.versions.toml`.

6. Verify:
   - `./gradlew :app:assembleDebug` passes.
   - `./gradlew :app:testDebugUnitTest` passes with all new DAO tests green.
   - `./gradlew :app:lintDebug` passes with no new errors.

### Acceptance Criteria
- All six entity classes exist and compile.
- All six DAO interfaces exist with the specified query methods.
- `AndroidClawDatabase` is buildable and all DAOs are accessible.
- All DAO unit tests pass.
- No existing tests break.

---

## Milestone 2 — Repository layer and domain model mapping

### Goal
Create repository classes that wrap DAOs and expose domain models (not Room entities) to the runtime layer. After this milestone, the runtime layer has a clean API for all CRUD operations without ever importing Room annotations.

### Why second
The repository layer is the bridge between persistence and runtime. Without it, the runtime would depend on Room entities directly, violating the layer rules.

### Concrete Steps

1. Create package `ai.androidclaw.data.model` with domain model data classes. These are the "public API" of the data layer. They must not have any Room annotations.

   **Session** — mirrors SessionEntity but uses `Instant` instead of `Long` for timestamps, and `Boolean` for archived instead of nullable `archivedAt`.
   **ChatMessage** — mirrors MessageEntity but uses an enum `MessageRole` instead of raw String, and `Instant` for timestamp.
   **Task** — mirrors TaskEntity but uses `TaskSchedule` (from the existing scheduler models) instead of raw JSON, and `TaskExecutionMode` enum instead of raw String.
   **TaskRun** — mirrors TaskRunEntity but with `Instant` timestamps and a `TaskRunStatus` enum.
   **SkillRecord** — mirrors SkillRecordEntity but with `SkillSourceType` and `SkillEligibilityStatus` enums.
   **EventLogEntry** — mirrors EventLogEntity but with `Instant` timestamp and `EventCategory` / `EventLevel` enums.

2. Create package `ai.androidclaw.data.repository` with repository classes:

   **SessionRepository**
   - `createSession(title: String, isMain: Boolean = false): Session`
   - `getOrCreateMainSession(): Session` — returns the existing main session or creates one with title "Main session" and `isMain = true`.
   - `getSession(id: String): Session?`
   - `observeSessions(): Flow<List<Session>>`
   - `updateTitle(id: String, title: String)`
   - `archiveSession(id: String)`

   **MessageRepository**
   - `addMessage(sessionId: String, role: MessageRole, content: String, providerMeta: String? = null, toolCallId: String? = null, taskRunId: String? = null): ChatMessage`
   - `observeMessages(sessionId: String): Flow<List<ChatMessage>>`
   - `getRecentMessages(sessionId: String, limit: Int): List<ChatMessage>`
   - `deleteSessionMessages(sessionId: String)`

   **TaskRepository**
   - `createTask(name: String, prompt: String, schedule: TaskSchedule, executionMode: TaskExecutionMode, targetSessionId: String?, precise: Boolean = false, maxRetries: Int = 3): Task`
   - `updateTask(task: Task)`
   - `getTask(id: String): Task?`
   - `observeTasks(): Flow<List<Task>>`
   - `getEnabledTasksDueBefore(instant: Instant): List<Task>`
   - `deleteTask(id: String)`
   - `recordRun(taskId: String): TaskRun` — creates a new `PENDING` run.
   - `updateRun(run: TaskRun)`
   - `observeRuns(taskId: String): Flow<List<TaskRun>>`
   - `getLatestRun(taskId: String): TaskRun?`

   **SkillRepository**
   - `upsertSkill(record: SkillRecord)`
   - `upsertAll(records: List<SkillRecord>)`
   - `observeSkills(): Flow<List<SkillRecord>>`
   - `getEnabledSkills(): List<SkillRecord>`
   - `getSkill(id: String): SkillRecord?`
   - `setEnabled(id: String, enabled: Boolean)`
   - `deleteSkill(id: String)`

   **EventLogRepository**
   - `log(category: EventCategory, level: EventLevel, message: String, details: String? = null)`
   - `observeRecent(limit: Int = 100): Flow<List<EventLogEntry>>`
   - `trimOlderThan(instant: Instant)`
   - `count(): Int`

   Each repository class takes only its corresponding DAO as a constructor parameter. Each repository method handles the mapping between domain model and Room entity internally. Use private extension functions `toEntity()` and `toDomain()` in each repository file.

3. Create `ai.androidclaw.data.model.Mappers.kt` only if shared mapping logic is needed; prefer keeping mappers private in each repository file.

4. For `TaskRepository`, the `scheduleSpec` JSON serialization and deserialization of `TaskSchedule` must be handled explicitly. Create a small utility `ScheduleSerializer` in `ai.androidclaw.data.model` that converts `TaskSchedule` ↔ JSON string using `kotlinx.serialization`. Test this serializer separately.

5. Write JVM unit tests for every repository class. These tests should use an in-memory Room database (same pattern as M1 DAO tests) and verify:
   - Round-trip: create → read back → values match.
   - Flow emission: insert triggers a new emission.
   - Mapping correctness: domain model fields have correct types and values.
   - `ScheduleSerializer` round-trips for `Once`, `Interval`, and `Cron` schedules.

6. Verify:
   - `./gradlew :app:assembleDebug` passes.
   - `./gradlew :app:testDebugUnitTest` passes.
   - `./gradlew :app:lintDebug` passes.

### Acceptance Criteria
- Six domain model classes exist in `data.model`, free of Room annotations.
- Five repository classes exist, each wrapping a DAO.
- `ScheduleSerializer` round-trips all three schedule types.
- All repository unit tests pass.
- Runtime layer code (not yet modified) still compiles.

---

## Milestone 3 — Persistent chat with multi-session support

### Goal
Wire `ChatViewModel` to `SessionRepository` and `MessageRepository` so that messages are written to Room and survive process death. Support creating new sessions and switching between them. After this milestone, the user can chat, kill the app, relaunch, and see their history.

### Concrete Steps

1. Update `AppContainer` to construct and hold `AndroidClawDatabase`, `SessionRepository`, and `MessageRepository`. The database instance must be built once and shared.

2. Add a method `AppContainer.ensureMainSession()` that calls `sessionRepository.getOrCreateMainSession()`. Call this from `AndroidClawApplication.onCreate()` inside a coroutine (use `GlobalScope` or an application-scoped `CoroutineScope` — document the choice).

3. Rewrite `ChatViewModel`:
   - Constructor takes `SessionRepository`, `MessageRepository`, `AgentRunner`, and `SkillManager` — not the full `AppContainer`.
   - Maintain a `currentSessionId: StateFlow<String>` that defaults to the main session's id.
   - `messages` is derived from `messageRepository.observeMessages(currentSessionId)` mapped to `ChatMessageUi`.
   - `sendCurrentDraft()` writes the user message to the DB first, then calls `agentRunner.runInteractiveTurn(...)`, then writes the assistant reply to the DB. The UI updates reactively from the Flow, not from manual `mutableState.update`.
   - Remove the hardcoded welcome message from `ChatUiState` default. Instead, on first launch of the main session, `ensureMainSession()` should insert a system message "AndroidClaw is ready." into the message table.
   - Add `switchSession(sessionId: String)` and `createNewSession(title: String)` methods.

4. Update `ChatScreen` to:
   - Display a session selector (a dropdown or horizontal chip row showing all sessions from `sessionRepository.observeSessions()`).
   - Show a "New session" action.
   - Pass only the needed dependencies to the ViewModel factory, not the full container.

5. Update `ChatViewModel.factory()` to accept only the needed dependencies. If this is cumbersome, create a `ChatDependencies` data class in the `feature.chat` package that holds the four dependencies. `AppContainer` exposes a `chatDependencies` property that constructs this.

6. Write tests:
   - **ChatViewModelTest** — uses an in-memory Room database and `FakeProvider`. Verifies:
     - Sending a message persists both user and assistant messages.
     - Switching sessions changes the observed message list.
     - Creating a new session adds it to the session list.
   - Use `kotlinx-coroutines-test` with `runTest` and `Turbine` (add `app.cash.turbine:turbine` as a test dependency) for Flow testing.

7. Verify:
   - `./gradlew :app:assembleDebug` passes.
   - `./gradlew :app:testDebugUnitTest` passes.
   - `./gradlew :app:lintDebug` passes.
   - Manual verification (when device available): launch app → send message → force stop → relaunch → message is still visible.

### Acceptance Criteria
- Chat messages survive process death.
- Multiple sessions can be created and switched between.
- Main session is auto-created on first launch.
- `ChatViewModel` no longer holds a reference to `AppContainer`.
- At least 3 ViewModel unit tests pass.

---

## Milestone 4 — ViewModel unification and layer rule enforcement

### Goal
Ensure every feature screen has a proper ViewModel. No Composable directly accesses `AppContainer` or any runtime service. After this milestone, the layer rule "UI depends only on ViewModel-exposed state" is enforced everywhere.

### Concrete Steps

1. Create `TasksViewModel`:
   - Takes `TaskRepository` and `SchedulerCoordinator` as dependencies.
   - Exposes `TasksUiState` containing: list of tasks (from `taskRepository.observeTasks()`), scheduler capabilities, and any computed preview data (next-run previews).
   - Move all `java.time.Instant.now()` calls and `NextRunCalculator` calls out of the Composable and into the ViewModel.
   - Add `createTask(...)`, `toggleEnabled(taskId)`, `runNow(taskId)`, `deleteTask(taskId)` actions.

2. Create `SettingsViewModel`:
   - Takes `ProviderRegistry` and a future `SettingsRepository` (backed by DataStore) as dependencies.
   - Exposes `SettingsUiState` containing: current provider id, provider configuration status, build posture info.
   - For now, the DataStore-backed settings can be a simple wrapper with `providerType` and `apiKey` fields. Create `ai.androidclaw.data.SettingsDataStore` as a thin wrapper around Preferences DataStore.

3. Create `HealthViewModel`:
   - Takes `SchedulerCoordinator`, `ToolRegistry`, `ProviderRegistry`, `EventLogRepository` as dependencies.
   - Exposes `HealthUiState` containing: provider status, scheduler capabilities, tool list, recent event log entries.

4. Update `SkillsViewModel`:
   - Replace `AppContainer` dependency with `SkillManager` and `SkillRepository`.
   - Skills state should come from `skillRepository.observeSkills()` once M8 lands; for now, keep using `skillManager.refreshBundledSkills()` but route through the ViewModel, not through the container.

5. Update all Screen composables to take their ViewModel (obtained via `viewModel(factory = ...)`) and not `AppContainer`.

6. Update `AndroidClawApp` (the NavHost composable):
   - It still receives `AppContainer` at the top level (this is acceptable as the composition root).
   - Each `composable { ... }` block creates the ViewModel via its factory, passing only the needed dependencies extracted from `AppContainer`.

7. Create a `DependencyBundles.kt` in `ai.androidclaw.app` package that defines small data classes grouping dependencies for each ViewModel factory. `AppContainer` exposes properties that construct these bundles. This avoids passing `AppContainer` into ViewModel factories.

8. Code review checklist (verify all of these before marking done):
   - `grep -rn "AppContainer" app/src/main/java/ai/androidclaw/feature/` returns zero results (no feature code imports AppContainer).
   - `grep -rn "import ai.androidclaw.data.db" app/src/main/java/ai/androidclaw/feature/` returns zero results (no feature code imports Room entities or DAOs).
   - `grep -rn "import ai.androidclaw.data.db" app/src/main/java/ai/androidclaw/runtime/` returns zero results (runtime does not import Room entities or DAOs; it uses repositories).

9. Verify:
   - `./gradlew :app:assembleDebug` passes.
   - `./gradlew :app:testDebugUnitTest` passes.
   - `./gradlew :app:lintDebug` passes.

### Acceptance Criteria
- Every feature screen has a dedicated ViewModel.
- No Composable imports `AppContainer`, any DAO, or any Room entity.
- No runtime class imports any Room entity or DAO directly (only repositories).
- grep verification commands in step 8 all return zero matches.

---

## Milestone 5 — ModelRequest enrichment and provider contract hardening

### Goal
Make `ModelRequest` capable of carrying conversation history, tool schemas in a provider-compatible format, and skill instructions. Harden `ModelProvider` and `ModelResponse` to support tool-call loops. After this milestone, the provider interface is ready for a real LLM API, even though FakeProvider remains the default.

### Concrete Steps

1. Update `ModelRequest`:
   - Add `priorMessages: List<ProviderMessage>` — a list of message objects that the provider uses as conversation history.
   - Add `systemInstruction: String?` — the system prompt, assembled by AgentRunner from enabled skill instructions.
   - Add `maxToolRounds: Int = 6` — the upper bound on tool-call loops.
   - Keep `enabledSkillNames` for logging, but the actual skill instructions should be baked into `systemInstruction`.

   Define `ProviderMessage` as:
   ```
   data class ProviderMessage(
       val role: String,       // "user", "assistant", "tool_result"
       val content: String,
       val toolCallId: String? = null,
       val toolCalls: List<ProviderToolCall>? = null,
   )
   ```

   Define `ProviderToolCall` as:
   ```
   data class ProviderToolCall(
       val id: String,
       val name: String,
       val argumentsJson: String,
   )
   ```

2. Update `ModelResponse`:
   - Add `toolCalls: List<ProviderToolCall> = emptyList()`.
   - Add `finishReason: String = "stop"` (values: `stop`, `tool_use`, `error`).
   - The `text` field may be empty when `finishReason == "tool_use"`.

3. Update `ToolDescriptor`:
   - Add `inputSchema: kotlinx.serialization.json.JsonObject` — a JSON Schema object describing the tool's input parameters.
   - Add `outputSchema: kotlinx.serialization.json.JsonObject?` — optional output schema.
   - Add `requiredPermissions: List<String> = emptyList()`.
   - Add `errorCodes: List<String> = emptyList()`.

4. Update `ToolRegistry.execute`:
   - Do not throw on unknown tool name. Instead, return `ToolExecutionResult(summary = "Unknown tool: $name", payload = ..., success = false)`.
   - Add `success: Boolean` field to `ToolExecutionResult`.

5. Update `AgentRunner.runInteractiveTurn`:
   - Build `priorMessages` from `messageRepository.getRecentMessages(sessionId, limit = 20)`, mapped to `ProviderMessage`.
   - Build `systemInstruction` from enabled skill instructions (concatenated markdown).
   - Implement a tool-call loop: if `ModelResponse.finishReason == "tool_use"`, execute each tool call, append tool results to the message history, and call the provider again. Repeat up to `maxToolRounds` times.
   - After the loop completes, persist all intermediate messages (tool calls and tool results) to the message table, not just the final assistant reply.
   - `AgentRunner` now takes `MessageRepository` as an additional dependency.

6. Update `FakeProvider`:
   - When the user message contains the substring `[tool:TOOLNAME]`, the FakeProvider should return a `ModelResponse` with `finishReason = "tool_use"` and a single `ProviderToolCall` targeting `TOOLNAME` with empty arguments. This allows testing the tool-call loop end-to-end without a real LLM.
   - On the second call (when prior messages contain a tool result), FakeProvider should return a normal `stop` response summarizing the tool result.

7. Write tests:
   - **AgentRunnerTest** — using FakeProvider and an in-memory Room DB:
     - Normal message round-trip persists user + assistant messages.
     - `[tool:health.status]` triggers tool-call loop, persists tool_call and tool_result messages, and returns a final assistant message.
     - Unknown tool in loop returns graceful error, does not crash.
     - Slash command dispatch still works as before.
   - **SlashCommandTest** — extract `SlashCommand` from AgentRunner to its own file `ai.androidclaw.runtime.orchestrator.SlashCommand.kt` and test:
     - `/name args` parses correctly.
     - `/name` with no args works.
     - Non-slash input returns null.
     - Input with leading/trailing whitespace is handled.

8. Verify:
   - `./gradlew :app:assembleDebug` passes.
   - `./gradlew :app:testDebugUnitTest` passes.
   - `./gradlew :app:lintDebug` passes.

### Acceptance Criteria
- `ModelRequest` carries prior messages and system instruction.
- `ModelResponse` can express tool-call requests.
- AgentRunner implements a bounded tool-call loop.
- FakeProvider can simulate a tool-call round-trip.
- All tool-call loop paths are tested.
- `SlashCommand` is independently testable and tested.

---

## Milestone 6 — Tool contract hardening and real built-in tools

### Goal
Flesh out the tool bus with proper schemas and implement the v0 built-in tools that the architecture document specifies as highest priority. After this milestone, tools are not stubs — they read and write real data.

### Concrete Steps

1. Implement the following built-in tools as separate classes in `ai.androidclaw.runtime.tools.builtins`:

   **SessionsTool** (`sessions.list`, `sessions.create`, `sessions.switch`)
   - `sessions.list`: returns all non-archived sessions from SessionRepository.
   - `sessions.create`: takes `title: String`, creates a new session, returns its id.
   - `sessions.switch`: takes `sessionId: String`, validates it exists, returns success.

   **TasksTool** (`tasks.list`, `tasks.create`, `tasks.get`)
   - `tasks.list`: returns all tasks from TaskRepository.
   - `tasks.create`: takes `name`, `prompt`, `scheduleKind`, `scheduleSpec`, `executionMode`; creates a task; returns its id.
   - `tasks.get`: takes `taskId`, returns task details or structured error.

   **SkillsTool** (`skills.list`)
   - Returns all skill records from SkillRepository.

   **DeviceInfoTool** (`device.info`)
   - Returns: Android version, SDK level, device model, available memory, battery level.
   - Uses `android.os.Build`, `ActivityManager`, and `BatteryManager`.

   **HealthStatusTool** (`health.status`)
   - Returns: provider configured (yes/no + id), scheduler capabilities, tool count, skill count, recent error count from EventLogRepository.

2. Each tool class implements a common `BuiltinTool` interface:
   ```
   interface BuiltinTool {
       val descriptors: List<ToolDescriptor>
       suspend fun execute(name: String, arguments: JsonObject): ToolExecutionResult
   }
   ```

3. Update `AppContainer` to construct all built-in tools and register them in `ToolRegistry`. Remove the two hardcoded stub tools (`tasks.list` and `health.status` lambdas) from `AppContainer`.

4. Each `ToolDescriptor` now carries a real `inputSchema` (as a `JsonObject` conforming to JSON Schema draft). Write the schemas inline as `buildJsonObject { ... }` in each tool class.

5. Write tests:
   - One test per tool class, verifying happy path and at least one error case (e.g., `sessions.switch` with a nonexistent session id).

6. Verify:
   - `./gradlew :app:assembleDebug` passes.
   - `./gradlew :app:testDebugUnitTest` passes.
   - `./gradlew :app:lintDebug` passes.

### Acceptance Criteria
- At least 5 real built-in tools are registered.
- No stub lambdas remain in `AppContainer` for tools.
- Every tool has a JSON Schema `inputSchema`.
- Every tool returns structured success or structured failure, never throws.
- All tool tests pass.

---

## Milestone 7 — Scheduler durable execution with WorkManager

### Goal
Make the scheduler actually run tasks. `TaskExecutionWorker` reads a task from Room, executes its prompt through AgentRunner, writes the result to TaskRun, computes the next run, and re-enqueues. After this milestone, `once` and `interval` tasks work end-to-end; `cron` works with approximate timing.

### Concrete Steps

1. Update `SchedulerCoordinator`:
   - Add `enqueueTask(task: Task)` — creates a OneTimeWorkRequest (for `once`) or PeriodicWorkRequest (for `interval`, minimum 15 minutes) tagged with the task id.
   - Add `cancelTask(taskId: String)` — cancels work by unique name.
   - Add `enqueueRunNow(taskId: String)` — creates a OneTimeWorkRequest with no delay for immediate execution.
   - Add `syncAllTasks()` — called at app start; reads all enabled tasks from `TaskRepository`, ensures each has a corresponding enqueued WorkManager work. Cancel orphaned work.
   - For `cron` tasks, convert the next-run time to a delay and use a OneTimeWorkRequest. After execution, compute the next cron occurrence and enqueue a new OneTimeWorkRequest. This simulates cron on top of WorkManager.
   - For `precise` tasks, use AlarmManager exact alarm to trigger at the precise time, which then enqueues a OneTimeWorkRequest for immediate execution. Register a BroadcastReceiver for the alarm. Check `canScheduleExactAlarms()` and fall back to WorkManager if not granted.

2. Update `TaskExecutionWorker.doWork()`:
   - Extract `taskId` from `inputData`.
   - Load the `Task` from `TaskRepository`.
   - Create a `TaskRun` with status `RUNNING`.
   - Determine the target session: if `MAIN_SESSION`, use the main session id; if `ISOLATED_SESSION`, create a new temporary session.
   - Call `agentRunner.runInteractiveTurn(request)` with the task's prompt.
   - On success: update `TaskRun` to `SUCCESS`, write the result summary, update the task's `lastRunAt` and compute `nextRunAt`, reset failure count.
   - On failure: update `TaskRun` to `FAILURE` with error details, increment failure count, apply backoff if under maxRetries.
   - Log the execution to `EventLogRepository`.
   - For recurring tasks (`interval`, `cron`): ensure the next execution is enqueued.
   - Return `Result.success()` on completion (even if the task itself failed — the failure is recorded in TaskRun, not in WorkManager retry semantics).

3. `TaskExecutionWorker` needs access to repositories and runtime services. Since we use manual DI (no Hilt), create a custom `WorkerFactory` in `ai.androidclaw.app` that provides the worker with its dependencies from `AppContainer`. Register this factory in `AndroidClawApplication.onCreate()` using `Configuration.Builder().setWorkerFactory(...)`. This requires implementing `androidx.work.Configuration.Provider` on the Application class and adding `android:name="androidx.startup"` removal in the manifest to disable default WorkManager initialization.

4. Update `AndroidManifest.xml`:
   - Add `<provider>` entry to disable automatic WorkManager initialization (since we use custom initialization).
   - Add `SCHEDULE_EXACT_ALARM` permission declaration (with appropriate comments about Android 14+ behavior).
   - Register the alarm BroadcastReceiver if implementing the precise alarm path.

5. Call `schedulerCoordinator.syncAllTasks()` from `AndroidClawApplication.onCreate()` after the database is initialized, inside a coroutine scope.

6. Write tests:
   - **SchedulerCoordinatorTest** — verify `enqueueTask` creates the correct work request type for each schedule kind. Use `WorkManagerTestInitHelper` for testing.
   - **TaskExecutionWorkerTest** — verify the worker loads a task, calls AgentRunner, writes the run result. Use `TestWorkerBuilder` from `androidx.work:work-testing`.

7. Verify:
   - `./gradlew :app:assembleDebug` passes.
   - `./gradlew :app:testDebugUnitTest` passes.
   - `./gradlew :app:lintDebug` passes.

### Acceptance Criteria
- Creating a `once` task and pressing "Run now" executes the prompt and writes a TaskRun.
- Creating an `interval` task enqueues periodic work.
- Creating a `cron` task computes the next run and enqueues a delayed OneTimeWorkRequest.
- WorkManager is initialized with a custom factory, not default initialization.
- Task execution failures are recorded as structured TaskRun entries, not silent drops.
- All scheduler tests pass.

---

## Milestone 8 — Skill persistence, import, enable/disable lifecycle

### Goal
Skills are no longer ephemeral in-memory snapshots. Bundled skills are synced to Room on first launch and on refresh. Users can enable/disable skills from the GUI. The foundation for local skill import (from file) is laid. After this milestone, skill state survives process death.

### Concrete Steps

1. Update `SkillManager`:
   - On `refreshBundledSkills()`, after loading from assets, upsert all results into `SkillRepository`. Return the persisted list.
   - Add `refreshLocalSkills()` — scans `files/skills/local/` directory for skill directories, parses each, upserts into SkillRepository with `sourceType = Local`.
   - Add `setSkillEnabled(skillId: String, enabled: Boolean)` — updates the skill record in the repository.
   - Add `importSkillFromZip(uri: Uri)` — placeholder that extracts a zip to `files/skills/local/<skill-id>/`, parses, and upserts. For v0, the actual extraction can be minimal (just support a directory containing `SKILL.md`). Full zip handling is a v0+ enhancement.
   - `SkillManager` now takes `SkillRepository` as an additional constructor dependency.

2. Update `SkillManager` caching:
   - After the first successful `refreshBundledSkills()`, cache the result in memory. Subsequent calls within the same process lifetime return the cache unless `forceRefresh = true` is passed.
   - This eliminates the current problem of re-parsing assets on every `AgentRunner` turn.

3. Update `SkillsViewModel`:
   - Observe `skillRepository.observeSkills()` as the primary data source.
   - Add `toggleSkill(skillId: String)` action.
   - Add `refresh()` that calls `skillManager.refreshBundledSkills(forceRefresh = true)`.
   - Show enable/disable toggle on each skill card.
   - Show skill source type badge (bundled / local / workspace).

4. Update `SkillsScreen`:
   - Add a Switch or Checkbox on each skill card for enable/disable.
   - Add an "Import skill" button (for v0, it can show a toast "Import not yet implemented" or open a basic file picker).

5. Write tests:
   - **SkillManagerTest** — verify bundled skills are upserted to repository, verify caching behavior, verify `setSkillEnabled` updates the record.

6. Verify:
   - `./gradlew :app:assembleDebug` passes.
   - `./gradlew :app:testDebugUnitTest` passes.
   - `./gradlew :app:lintDebug` passes.

### Acceptance Criteria
- Bundled skills are persisted in Room after first refresh.
- Disabling a skill persists the disabled state across process restarts.
- Skill list in the GUI reflects the persisted state.
- Skills are cached in memory after first load; subsequent calls do not re-parse assets.

---

## Milestone 9 — Full GUI wiring — all screens functional

### Goal
Turn every placeholder screen into a working management surface. After this milestone, a human tester can manage the whole v0 app from the GUI.

### Concrete Steps

1. **Chat screen enhancements**:
   - Session selector at the top (horizontal scrollable chip row or dropdown).
   - "New session" action (FAB or menu item).
   - Long-press on a session chip to archive.
   - Slash command chips populated from enabled, user-invocable skills.
   - Loading indicator during AgentRunner execution.
   - Error state display when a turn fails.
   - Empty state when a session has no messages.

2. **Tasks screen full implementation**:
   - Task list showing: name, schedule description, next run time, last run status, enabled toggle.
   - "Create task" FAB opening a creation dialog/sheet with fields: name, prompt, schedule kind (selector), schedule parameters (time picker for once, interval input for interval, cron expression text field for cron), execution mode selector.
   - "Run now" button on each task card.
   - Swipe-to-delete or long-press delete.
   - Task detail screen showing run history (list of TaskRuns with status, timing, error details).

3. **Skills screen enhancements** (mostly done in M8):
   - Ensure the enable/disable toggle works.
   - Show eligibility warnings clearly (missing tools, bridge-only).
   - Show skill details on tap (expand card or navigate to detail screen showing full description, frontmatter fields, required tools).

4. **Settings screen full implementation**:
   - Provider selector: FakeProvider vs OpenAI-compatible.
   - When OpenAI-compatible is selected, show fields for: API base URL, API key (stored in Keystore, shown masked), model name.
   - "Test connection" button that sends a simple request and shows success/failure.
   - "Clear all data" button with confirmation dialog.
   - "Export sessions" placeholder (show coming-soon message).
   - Permission status summary (exact alarm permission, notification permission).
   - App version display.

5. **Health screen full implementation**:
   - Provider configured: yes/no with provider id.
   - Scheduler status: next scheduled task name and time, last wake time.
   - Tool registry: count and list of registered tools.
   - Skill summary: count of enabled/total skills.
   - Recent event log: scrollable list of last 50 events with category/level/message.
   - Battery optimization status: detect if the app is in restricted battery bucket and show guidance.

6. **Navigation polish**:
   - Replace text glyph icons (`"C"`, `"T"`, `"S"`, `"G"`, `"H"`) with Material icons from `androidx.compose.material.icons`. Add the icons dependency if not present.
   - Ensure back navigation works correctly from all screens.
   - Ensure deep-link capability for future use (add placeholder deep link patterns in the manifest).

7. **Empty states, loading states, error states**:
   - Every list screen must handle: empty (with a message like "No tasks yet"), loading (with a spinner or shimmer), and error (with a retry button).
   - These must be consistent in style across all screens.

8. Verify:
   - `./gradlew :app:assembleDebug` passes.
   - `./gradlew :app:testDebugUnitTest` passes.
   - `./gradlew :app:lintDebug` passes.
   - Manual walkthrough of all 5 screens confirms they are functional and not placeholders.

### Acceptance Criteria
- A human tester can: send messages, create sessions, switch sessions, create tasks, run tasks now, enable/disable skills, configure a provider, and view health — all from the GUI.
- All screens have empty/loading/error states.
- Navigation bar uses proper icons.
- No screen directly accesses `AppContainer`.

---

## Milestone 10 — Test hardening, lint cleanup, and release prep

### Goal
Bring test coverage to a baseline level, fix all lint warnings, add proguard rules for release builds, and ensure the app is ready for a debug APK handoff. This is the final quality gate before declaring v0 done.

### Concrete Steps

1. **Test coverage targets** (not percentage-based; specific files):
   - `AgentRunner`: at least 5 tests covering normal turn, slash command, tool-call loop, unknown tool, max-rounds exceeded.
   - `SkillManager`: at least 4 tests covering bundled refresh, eligibility (eligible, bridgeOnly, missingTool), cache behavior.
   - `SkillParser`: at least 5 tests covering valid parse, missing name, missing description, no frontmatter, malformed YAML.
   - `CronExpression`: at least 6 tests covering daily, weekly, monthly, hourly macros, range with step, comma-separated values, day-of-week edge cases (Sunday = 0 or 7).
   - `NextRunCalculator`: at least 4 tests covering once (future and past), interval from anchor, cron next occurrence.
   - `SlashCommand`: at least 4 tests.
   - All repositories: at least 2 tests each (round-trip and Flow emission).
   - All built-in tools: at least 1 test each.

2. **Instrumentation smoke tests** (under `src/androidTest/`):
   - Create `AppLaunchTest` that verifies the app launches and the main session is created.
   - Create `NavigationTest` that verifies all 5 top-level destinations are reachable.
   - Create `ChatPersistenceTest` that sends a message via UI, then recreates the activity, and verifies the message is still visible.
   - These use `ComposeTestRule` and `createAndroidComposeRule<MainActivity>()`.

3. **Lint cleanup**:
   - Run `./gradlew :app:lintDebug` and fix all errors and warnings.
   - Suppress only warnings that are false positives, with a comment explaining why.
   - Ensure `lint-baseline.xml` is not used as a way to hide real issues.

4. **Proguard / R8**:
   - Enable `isMinifyEnabled = true` for the release build type.
   - Add proguard rules for: Room entities, kotlinx-serialization, SnakeYAML, OkHttp.
   - Verify `./gradlew :app:assembleRelease` succeeds.
   - Verify the release APK is functional (if a device is available).

5. **APK size check**:
   - After `assembleRelease`, record the APK size in `PLAN.md` under Outcomes.
   - Target: under 10 MB for the base APK. If over, investigate the largest contributors with `apkanalyzer`.

6. **Documentation update**:
   - Update `PLAN.md` Progress section to reflect all completed milestones.
   - Update `ARCHITECTURE.md` if any architectural decisions changed during implementation.
   - Update `AGENTS.md` if new validation commands or repository conventions emerged.
   - Verify all three docs are consistent with each other and with the actual code.

7. Final verification:
   - `./gradlew :app:assembleDebug` passes.
   - `./gradlew :app:testDebugUnitTest` passes (all JVM tests green).
   - `./gradlew :app:lintDebug` passes (zero errors).
   - `./gradlew :app:assembleRelease` passes.
   - `./gradlew :app:connectedDebugAndroidTest` passes (when device/emulator available).

### Acceptance Criteria
- All specified test files exist and pass.
- Lint reports zero errors.
- Release build succeeds with minification enabled.
- APK size is recorded.
- All documentation is up to date.
- The v0 acceptance flow (from the original plan, steps 1-10) can be completed on a device.

---

## v0 Acceptance Flow (copied from original plan, for reference)

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

Every milestone in this plan is designed to unlock one or more steps of this flow. By M10, all ten steps should pass.

---

## Dependency Changes Expected

The following new dependencies may be needed during this phase. Prefer the latest stable versions at the time of implementation. Add them to `libs.versions.toml` with explicit version pinning.

- `androidx.test:core` — for Robolectric-based Room tests (testImplementation)
- `org.robolectric:robolectric` — for JVM-based Android tests (testImplementation)
- `app.cash.turbine:turbine` — for Flow testing (testImplementation)
- `androidx.work:work-testing` — for WorkManager tests (testImplementation)
- `androidx.room:room-compiler` — KSP annotation processor (ksp, if not already configured)
- `androidx.compose.material:material-icons-extended` — for navigation icons (implementation)

If KSP is not yet configured in the project for Room annotation processing, add the KSP Gradle plugin and configure it before M1 begins.

---

## Idempotence and Recovery

All plan steps should be safe to repeat.

- Re-running Gradle validation commands should be harmless.
- Re-opening the app after a crash or force-stop should restore durable state from Room and DataStore.
- Scheduler sync at app launch recomputes due tasks from the database.
- If a provider call fails halfway, persist a readable failure event.
- If a migration or schema change becomes necessary, add migration tests before landing it.
- Each milestone can be re-executed from scratch if the previous milestone's artifacts are intact.

---

## Concrete Validation Commands

From the repository root:

```bash
# Fast local checks (run after every change)
./gradlew :app:assembleDebug
./gradlew :app:testDebugUnitTest
./gradlew :app:lintDebug

# Release build (run at M10)
./gradlew :app:assembleRelease

# Instrumentation tests (run when device/emulator available)
./gradlew :app:connectedDebugAndroidTest

# APK size check (run at M10)
ls -lh app/build/outputs/apk/release/app-release.apk
```

Never declare a milestone done with red tests, red lint, or a broken build.
