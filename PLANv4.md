# AndroidClaw Execution Plan v4

> Status: canonical execution plan as of 2026-03-12.
>
> `AGENTS.md` now points here. `PLANv1.md`, `PLANv2.md`, and `PLANv3.md` are retained as historical context only.

---

## 1. Why this plan exists

AndroidClaw is no longer a greenfield prototype. The attached repository already contains a serious amount of real implementation: a single-module Android app, a persistent database, a provider layer, a tool registry, a skill loader/importer, a durable scheduler, a Windows-host emulator harness, and a focused instrumented test suite.

At this point, the highest-value work is **not** inventing new product categories. The highest-value work is turning the current repository into a **provable, repeatable, lightweight v0 release candidate**.

That means this plan is intentionally biased toward:

1. finishing proof and validation,
2. removing brittle environment assumptions,
3. closing exact-alarm and scheduler evidence,
4. hardening persistence and upgrade behavior,
5. locking in startup/size/performance,
6. shipping a clean release artifact with honest limits.

This plan follows the same agent-first repository principles described by OpenAI’s harness engineering write-up: keep `AGENTS.md` short, keep the repository docs as the system of record, and make execution plans explicit, versioned, and continuously updated. [R1]

---

## 2. Read order

Before making any non-trivial change, read in this order:

1. `AGENTS.md`
2. `docs/ARCHITECTURE.md`
3. `docs/SCHEDULER.md`
4. `docs/SKILLS_COMPAT.md`
5. `PLANv4.md`

If a task contradicts the plan, update the plan first, then code.

If a workstream becomes too large for one file, create a child plan under `docs/exec-plans/active/<slug>.md` and link it here. Keep this document as the top-level map and sequencing source. This matches the repository-local knowledge approach recommended for agent-driven engineering. [R1]

---

## 3. Executive summary

### 3.1 Product position

AndroidClaw remains a **lightweight Android-native local assistant host** inspired by NanoClaw and OpenClaw.

It is **not** a desktop runtime port.

Its v0 contract is still:

- **Session contract**: main session, normal sessions, durable history, lightweight summaries
- **Tool contract**: typed tools with structured availability and structured failures
- **Skill contract**: OpenClaw-style runtime skills with `SKILL.md`, precedence, enable/disable, and slash invocation
- **Automation contract**: `once`, `interval`, and `cron`, with `MAIN_SESSION` and `ISOLATED_SESSION`

This remains the correct product direction because:

- OpenClaw’s current Android app is a **companion node**, not a local gateway host. AndroidClaw is therefore a new Android-native host implementation, not an upstream port. [R2]
- OpenClaw’s current skill model is runtime `SKILL.md` directories with precedence and config semantics; this is the compatibility target that makes sense on mobile. [R3][R4]
- OpenClaw’s modern capability model is typed tools, not shell-first behavior. [R5]
- OpenClaw cron is persisted, can wake the agent, and can deliver output back to chat; NanoClaw also treats scheduled tasks as a core concept. [R6][R7]

### 3.2 What is already true in this repo

The current repository already includes, at minimum:

- a single `:app` module Android app,
- manual dependency wiring,
- Compose navigation and feature screens,
- Room persistence,
- session/message/task/skill/event repositories,
- a `FakeProvider` and one real OpenAI-compatible provider,
- a typed `ToolRegistry`,
- bundled/local/workspace skill loading and precedence handling,
- a WorkManager-first scheduler,
- exact-alarm routing and diagnostics,
- startup maintenance and rescheduling,
- JVM tests for most runtime/data/viewmodel surfaces,
- instrumented tests for smoke, migration, scheduler execution, and exact alarms,
- repo-owned Windows-host emulator scripts,
- deprecated LDPlayer entrypoints reduced to shims.

### 3.3 What is still missing or still not fully proven

The major remaining gaps are now narrower:

1. **Baseline Profile tooling gap**
   - Baseline Profiles still do not exist in-repo.
   - This is currently blocked by WSL Gradle failing to resolve uncached AndroidX benchmark/profile artifacts from Google Maven, even though normal app dependencies resolve.
   - By explicit user direction on 2026-03-12, this work is deferred for now and is not a blocker for the current RC verification pass.

2. **Performance/release evidence gap**
   - startup eagerness has been reduced, but that evidence still needs to be finalized in the plan/docs
   - release shrinking is still off and its keep-disabled-for-now decision must remain explicit
   - the current unshrunk release APK size is now recorded, but there is still no post-shrinking comparison because shrinking remains deferred

3. **RC validation gap**
   - automated RC evidence is now recorded
   - the remaining RC items are manual/external tester-hand-off steps such as real-provider QA with an API key and human walkthrough checks

4. **Tooling honesty gap**
   - `lintDebug` is clean for production sources, but AGP 8.13 + Kotlin FIR is crashing while analyzing `debugUnitTest` and `debugAndroidTest` sources on this workstation
   - the repo needs the narrowest stable workaround so the fast validation loop stays green without pretending test-source lint coverage is trustworthy

### 3.4 The single most important principle for the next phase

**Do not reopen architecture.**

The app already has the right architectural direction for v0. The remaining work is about validation, removal of brittle assumptions, diagnostics honesty, performance proof, and release discipline.

---

## 4. Current repository snapshot (audited from the attached zip)

This section is deliberately concrete so the next agent can orient quickly.

### 4.1 Build and platform baseline

Current `app/build.gradle.kts` shows:

- `compileSdk = 36`
- `targetSdk = 36`
- `minSdk = 26`
- Java/Kotlin toolchain = 17
- a Gradle Managed Device named `pixel8Api36`
- Compose, Room, WorkManager, DataStore, OkHttp, kotlinx serialization, and SnakeYAML
- release minification currently **disabled**

### 4.2 Current top-level repository shape

The repo currently contains:

- `AGENTS.md`
- `PLANv1.md`
- `PLANv2.md`
- `PLANv3.md`
- `docs/ARCHITECTURE.md`
- `docs/SCHEDULER.md`
- `docs/SKILLS_COMPAT.md`
- `scripts/` with Windows emulator setup/test harnesses
- `.github/workflows/android.yml`

### 4.3 Current app/runtime surface

The codebase already includes these important runtime areas:

- `app/AppContainer.kt`
- `runtime/orchestrator/*`
- `runtime/providers/*`
- `runtime/tools/*`
- `runtime/skills/*`
- `runtime/scheduler/*`
- `feature/chat/*`
- `feature/tasks/*`
- `feature/skills/*`
- `feature/settings/*`
- `feature/health/*`
- `data/db/*`
- `data/repository/*`

### 4.4 Current test surface

The repo already includes:

- broad JVM coverage for repositories, viewmodels, provider logic, scheduler logic, skill parsing, tool registry, and startup maintenance,
- instrumentation tests for:
  - `MainActivitySmokeTest`
  - `AndroidClawDatabaseMigrationTest`
  - `TaskExecutionWorkerSmokeTest`
  - `ExactAlarmRegressionTest`

### 4.5 Current validation status from the latest handoff

Validated green on the repo side:

- `:app:testDebugUnitTest`
- `:app:assembleDebug`
- `:app:assembleDebugAndroidTest`
- `:app:lintDebug`
- `:app:assembleRelease`
- `ANDROIDCLAW_JAVA_HOME=/tmp/androidclaw-jdk17-extract/jdk-17.0.18+8 ./scripts/run_windows_android_test.sh --avd AndroidClawApi34 --test-class ai.androidclaw.app.MainActivitySmokeTest --no-window` passed
- `ANDROIDCLAW_JAVA_HOME=/tmp/androidclaw-jdk17-extract/jdk-17.0.18+8 ./scripts/run_windows_android_test.sh --avd AndroidClawApi34 --test-class ai.androidclaw.runtime.scheduler.TaskExecutionWorkerSmokeTest --no-window` passed
- `ANDROIDCLAW_JAVA_HOME=/tmp/androidclaw-jdk17-extract/jdk-17.0.18+8 ./scripts/run_exact_alarm_regression.sh --api34-avd AndroidClawApi34 --api31-avd AndroidClawApi31 --no-window` passed
- `ANDROIDCLAW_JAVA_HOME=/tmp/androidclaw-jdk17-extract/jdk-17.0.18+8 ./scripts/run_windows_android_test.sh --avd Medium_Phone_API_36.1 --test-class ai.androidclaw.data.db.AndroidClawDatabaseMigrationTest --no-window` passed
- `ANDROIDCLAW_JAVA_HOME=/tmp/androidclaw-jdk17-extract/jdk-17.0.18+8 ./scripts/run_windows_android_test.sh --avd Medium_Phone_API_36.1 --test-class ai.androidclaw.app.StartupMaintenanceIntegrationTest --no-window` passed
- persistence evidence is recorded in `docs/qa/persistence-validation.md`
- scheduler/device evidence is recorded in `docs/qa/windows-emulator-validation.md` and `docs/qa/exact-alarm-regression.md`
- release artifact evidence is recorded in `docs/qa/release-build-validation.md`

### 4.6 Current blocker state from the latest handoff

Windows AVD validation is now unblocked and proven:

- Android Studio is installed on Windows,
- Windows SDK tools under `%LOCALAPPDATA%\Android\Sdk` are present,
- `HypervisorPlatform` / WHPX is enabled,
- the required AVDs (`AndroidClawApi34`, `AndroidClawApi31`) now exist,
- API 34 smoke tests passed through the repo-owned WSL -> PowerShell -> Windows emulator path,
- the API 34 / API 31 exact-alarm regression sweep passed and is recorded in `docs/qa/exact-alarm-regression.md`.

### 4.7 Repo issue discovered during plan audit

The shell wrappers no longer hardcode a user-specific Linux JDK fallback path. The harness now resolves Java in this order:

- `scripts/run_windows_android_test.sh`
- `scripts/run_exact_alarm_regression.sh`

1. `ANDROIDCLAW_JAVA_HOME`
2. `JAVA_HOME`
3. `java` on `PATH` if it is Java 17+

The remaining operator-facing issue is now narrower: WSL still defaults to Java 8 on this workstation, so any Windows-AVD run must export a Java 17+ runtime explicitly until the host default changes.

---

## 5. Product goals for the rest of v0

The remaining v0 work should be judged against these outcomes:

1. **One installable APK** with no extra runtime host requirement.  
2. **Low memory and fast startup** on ordinary Android phones.  
3. **Truthful scheduler behavior**: clear exact-vs-approximate behavior, visible degradation, and no silent false promises.  
4. **No data wipes on upgrade**.  
5. **Enough test evidence** that humans can trust the app.  
6. **No new architectural burden** that makes agent-driven iteration harder.  

### 5.1 What “done” looks like for v0

For v0 release candidate, AndroidClaw must be able to demonstrate:

- main session chat with `FakeProvider`
- main session chat with the current OpenAI-compatible provider path
- session creation / switching / rename / archive
- task creation for `once`, `interval`, and `cron`
- `MAIN_SESSION` and `ISOLATED_SESSION` task execution
- `Run now`
- durable `TaskRun` history
- exact-alarm degrade path on Android 14+
- health/task diagnostics that explain scheduling limitations
- bundled/local/workspace skill handling
- skill import / enable / disable / precedence explanation
- migration-safe database upgrades
- baseline profile coverage for core journeys
- a buildable release artifact

---

## 6. Non-goals until after v0 RC

These items are explicitly out of scope unless this plan is amended:

- Node.js / Docker / Chromium embedding in the base app
- external chat-channel integrations (Telegram, Gmail, Discord, Slack, WhatsApp, etc.)
- remote bridge mode / desktop gateway pairing
- shell execution or package-manager-driven skills
- browser automation
- voice wake word / continuous audio
- screen recording workflows
- cloud sync / account system
- multi-provider orchestration
- streaming or multimodal provider work
- heavy security hardening programs
- multi-module architecture refactors

### 6.1 Security posture for this phase

Security still matters, but it is not the primary optimization target for the next phase.

For v0, the rule is:

- keep the existing minimal safe defaults,
- do not expand high-risk capabilities,
- do not spend the next phase on sandboxing or enterprise-grade hardening,
- do not regress obviously safe existing behavior.

Practical meaning:

- keep app-private storage,
- keep the existing keystore-backed secret seam,
- keep SAF-based imports,
- avoid new dangerous capabilities,
- do not open a dedicated security-hardening track before RC.

---

## 7. External facts that shape this plan

These are the external truths that justify the sequencing and constraints below.

1. OpenAI’s harness engineering guidance strongly favors a short `AGENTS.md`, deeper repository-local docs, and explicit execution plans as the system of record for agent-first repos. [R1]
2. OpenClaw currently treats Android as a **node**, not a gateway host. [R2]
3. OpenClaw skills are runtime directories centered on `SKILL.md` with YAML frontmatter and instructions, with bundled/local/workspace loading and precedence semantics. [R3][R4]
4. OpenClaw prefers first-class typed tools over shell-first patterns. [R5]
5. OpenClaw cron persists jobs and can deliver output back to chat; that is the right behavior model for AndroidClaw’s scheduler UX. [R6]
6. NanoClaw’s philosophy emphasizes a small understandable core, but still assumes desktop/server realities like container isolation; AndroidClaw must preserve the small-core philosophy without copying the desktop runtime assumptions. [R7]
7. Android recommends WorkManager for durable background work that must persist across app restarts and device reboots; unique work and backoff are first-class concepts. [R8][R9]
8. On Android 14+, `SCHEDULE_EXACT_ALARM` is denied by default for most newly installed apps targeting Android 13+. Exact alarms are meant for user-intentioned, time-sensitive behavior, not general recurring background automation. [R10][R11]
9. On Android 13+, `POST_NOTIFICATIONS` is a runtime permission. If notifications are denied, exact scheduling may still technically occur while the user-visible reminder value is degraded. [R12]
10. Foreground services are more constrained on recent Android versions; Android 14 requires correct service types, and while-in-use permission restrictions matter for camera/location/microphone-related work. [R13]
11. App Standby Buckets and Android 16 job/runtime quota changes affect WorkManager and related job behavior. Diagnostics and bounded work are therefore required, not optional. [R14]
12. Build-managed devices are the official Gradle-owned path for consistent instrumented tests; they improve reliability and can be grouped or sharded. [R15]
13. Baseline Profiles are an official path to materially better launch/runtime behavior, and their generation path works well with rooted physical devices, emulators, or Gradle Managed Devices using `aosp`. [R16]
14. Room migrations should be explicit and tested with exported schemas and migration helpers. [R17]
15. SAF remains the correct Android-native import model for user-selected skill archives/files. [R18]
16. Android’s app-optimization guidance recommends shrinking/optimizing release builds with R8 and measuring APK composition with APK Analyzer. [R19]
17. Android provides dedicated guidance for testing workers and debugging WorkManager behavior. [R20]
18. Research on Android/mobile flakiness shows mobile tests are especially sensitive to UI, dependency, environment, and program logic issues; therefore, the device suite should be narrow, high-value, and deterministic rather than broad and brittle. [R21][R22]
19. Research on Android background activity and battery impact reinforces the decision to avoid polling loops, permanent foreground services, and “daemon mindset” designs. [R23]

---

## 8. Architecture invariants for the rest of the project

These are effectively frozen for v0.

### 8.1 Module and dependency policy

- Keep production code in **one `:app` module**.
- A test-only `:baselineprofile` module is allowed later.
- Do not introduce Hilt/Koin or other DI frameworks.
- Keep manual wiring in `AppContainer` or obvious successors.
- Do not add large SDKs when platform APIs + OkHttp are enough.

### 8.2 Runtime policy

- WorkManager-first scheduler
- exact alarms only as a narrow enhancement path
- no always-on foreground service while idle
- no polling loop
- no desktop-style daemon assumptions
- bounded task execution
- truthful degradation when platform requirements are not met

### 8.3 Data policy

- Room is the durable structured store
- DataStore for settings
- app-private files for imported/local/workspace skill content
- SAF for user-selected imports
- exported schemas must stay in version control

### 8.4 UX policy

The UI does not need to be fancy, but it must be **honest**.

That means:

- show when exact alarms are unavailable,
- show when notifications are disabled,
- show when a skill is ineligible and why,
- show when a tool is unavailable and why,
- show when a task failed or was degraded,
- never silently no-op.

### 8.5 Performance policy

- no startup network dependency
- no eager global parsing/loading without reason
- prefer `WhileSubscribed` or lazy loading over always-hot flows when practical
- keep caches bounded
- keep logs bounded
- avoid heavy animations and visual effects
- size and startup regressions are treated as real bugs

---

## 9. Delivery strategy for the next phase

The next phase should run on **three tracks in parallel**, so the Windows manual blocker does not freeze the project.

### Track A — Host and validation closure
Finish the Windows host path, run the device tests, and close scheduler evidence.

### Track B — Repo-only hardening
Remove brittle script assumptions, improve diagnostics, tighten migrations, and prep performance work.

### Track C — Release evidence and docs
Add the missing operator docs, QA docs, performance docs, and release checklist.

This division lets Codex keep making progress even when a human must complete a Windows GUI/admin step.

---

## 10. Ordered workstreams

---

## ws0-plan-adoption — make PLANv4 canonical

### Goal

Adopt this file as the active source of truth and prevent plan drift.

### Why first

Agent-driven work gets sloppy quickly when there are multiple competing “current” plans. [R1]

### Ordered steps

1. Update `AGENTS.md` to point to `PLANv4.md`.
2. Mark `PLANv3.md` as historical once this file is adopted.
3. Keep `PLANv1.md` and `PLANv2.md` as archive/history only.
4. If helpful, create:
   - `docs/exec-plans/active/`
   - `docs/exec-plans/completed/`
   But do not block delivery on that reorganization.
5. Seed the progress/discoveries/decision log sections in this file after adoption.

### Acceptance criteria

- `AGENTS.md` points to `PLANv4.md`
- no document still claims `PLANv3.md` is canonical
- next agent can determine the current plan in one read

### Validation

- grep for `PLANv3.md` references that should now point to `PLANv4.md`
- fast suite not required unless code or scripts changed

---

## ws1-validation-harness-normalization — remove brittle host assumptions

### Goal

Make the repo-owned validation harness portable, explicit, and legible.

### Why now

The current Windows AVD route is the right strategy for this workstation, but the shell wrappers still contain a user-specific JDK path. That is a stability bug in the harness.

### Ordered steps

#### Step 1 — remove user-specific Java fallback

Replace the hardcoded `/home/lanla/...` fallback in:

- `scripts/run_windows_android_test.sh`
- `scripts/run_exact_alarm_regression.sh`

with a generic resolution order such as:

1. `ANDROIDCLAW_JAVA_HOME` (if set)
2. existing `JAVA_HOME` (if valid)
3. `java` on `PATH` if version >= 17
4. otherwise fail with a clear, actionable message

Do **not** guess random local paths.

#### Step 2 — add or improve preflight reporting

Either add a dedicated `scripts/check_host_prereqs.sh` / `.ps1`, or extend the existing wrappers so they can print a deterministic preflight summary covering:

- Linux/WSL `java -version`
- whether Java is >= 17
- whether `wslpath` exists
- whether `powershell.exe` is callable
- Windows SDK root path
- presence of `emulator.exe`
- presence of `adb.exe`
- HypervisorPlatform / WHPX state
- available AVD names

The script must fail **early** and **explicitly**.

#### Step 3 — document the two official device lanes

Document both supported device paths:

1. **Gradle Managed Device lane** for acceleration-capable hosts:
   - `./gradlew :app:pixel8Api36DebugAndroidTest`
2. **Windows AVD lane** for this workstation:
   - `./scripts/run_windows_android_test.sh ...`
   - `./scripts/run_exact_alarm_regression.sh ...`

The documentation must say clearly:

- Managed Devices remain the long-term repo-native path. [R15]
- Windows AVD is the current practical workstation fallback.
- LDPlayer is deprecated.

#### Step 4 — keep CI fast

Do **not** immediately move full instrumented testing into every PR job.

Current GitHub Actions already runs assemble + unit + lint. Keep that fast loop.

After the harness is stable, consider one of these, in order:

1. manual `workflow_dispatch` device smoke,
2. nightly device smoke,
3. PR-gated smoke only if runtime and flakiness are acceptable.

This is consistent with both Android testing guidance and the reality that mobile/device suites are more fragile and expensive than local deterministic tests. [R15][R21][R22]

### Files likely touched

- `scripts/run_windows_android_test.sh`
- `scripts/run_exact_alarm_regression.sh`
- possibly a new `scripts/check_host_prereqs.sh`
- possibly `scripts/setup_windows_android_emulator.sh`
- possibly `docs/TESTING.md`
- possibly `.github/workflows/*`

### Acceptance criteria

- no user-specific JDK path remains in the canonical scripts
- preflight failures are explicit and actionable
- a new operator can tell which validation lane to use
- CI remains fast for ordinary iteration

### Validation

- `bash -n` on touched shell scripts
- run the preflight script on the current machine
- ensure fast suite still passes if code changes were made

---

## ws2-windows-host-completion — finish the human-required emulator setup

### Goal

Finish the Windows-side setup required for the current workstation to run official AVD-backed instrumentation.

### Important note

This workstream contains **human-only** steps. Codex should not pretend it can complete them unattended from WSL if Windows elevation or Android Studio GUI steps are required.

### Human-owned steps

#### Step 1 — enable HypervisorPlatform / WHPX

If the Windows preflight still reports `WHPX_INSTALL_STATE != 1`, complete this in an elevated PowerShell session.

The repo already includes:

```powershell
scripts/setup_windows_android_emulator.ps1 -EnableHypervisorPlatform
```

Reboot if Windows requires it.

#### Step 2 — complete Android Studio first-run SDK install

Launch Android Studio and finish the first-run flow so that `%LOCALAPPDATA%\Android\Sdk` contains, at minimum:

- platform-tools
- emulator
- cmdline-tools
- Android SDK Platform 34
- Android SDK Platform 31
- suitable x86_64 emulator system images for API 34 and API 31

#### Step 3 — create the required AVDs

Create AVDs with these exact names:

- `AndroidClawApi34`
- `AndroidClawApi31`

These names are intentionally hardcoded in the repo workflows and docs.

#### Step 4 — verify tools and AVDs

Use the repo setup helper or the Windows SDK tools directly to verify:

- `emulator.exe -list-avds` contains both AVD names
- `adb.exe devices` sees the emulator after boot

### Codex-owned follow-up after the human step

Once the host is actually ready, Codex should immediately run:

```bash
./scripts/run_windows_android_test.sh --avd AndroidClawApi34 --test-class ai.androidclaw.app.MainActivitySmokeTest
./scripts/run_windows_android_test.sh --avd AndroidClawApi34 --test-class ai.androidclaw.runtime.scheduler.TaskExecutionWorkerSmokeTest
./scripts/run_exact_alarm_regression.sh --api34-avd AndroidClawApi34 --api31-avd AndroidClawApi31
```

### Acceptance criteria

- the workstation can boot named official Android AVDs through the repo scripts
- the app APK and androidTest APK install cleanly
- instrumented tests execute from WSL through the Windows SDK path

### Validation evidence to save

Create a small repo-local evidence note such as:

- `docs/qa/windows-emulator-validation.md`

Record:

- date
- Windows SDK root
- AVD names
- commands run
- pass/fail results
- any host quirks discovered

Do not commit emulator binaries or screenshots to git unless there is a clear reason.

---

## ws3-m5-closure — finish scheduler precision, degradation, and diagnostics proof

### Goal

Close the remaining `m5` work by proving the real scheduler behavior on devices and tightening any missing diagnostics.

### Why this matters

Scheduler trust is core to AndroidClaw. If tasks feel unreliable, the product loses most of its value.

Android’s guidance is also clear that exact alarms are a narrow mechanism and that general background work should use WorkManager. AndroidClaw therefore has to be extremely honest about the difference between “precise request” and “actual device-visible outcome.” [R8][R10][R11]

### Ordered steps

#### Step 1 — add notification-visibility diagnostics

Current scheduler diagnostics track:

- exact-alarm support
- exact-alarm grant state
- standby bucket

They do **not** clearly model whether notifications can actually be shown.

For time-sensitive reminders on Android 13+, this is a meaningful missing signal because `POST_NOTIFICATIONS` is a runtime permission. [R12]

Add a small diagnostics extension such as:

- notifications permission granted (API 33+)
- app notifications enabled
- user-visible warning if a precise reminder may execute but not visibly notify

This should surface in:

- `HealthViewModel` / `HealthScreen`
- `TasksViewModel` / `TasksScreen`

Keep it lightweight; do not build a giant onboarding flow.

#### Step 2 — ensure task creation UX explains degradation clearly

When a user marks a task as precise:

- if exact alarms are unsupported, say so,
- if exact alarms are denied, say so,
- if notifications are denied or disabled, say so,
- if the task would still degrade to approximate WorkManager execution, say so before or immediately after save.

This should be plain language, not internal jargon.

#### Step 3 — run the device-backed smoke suite

Once the Windows host is ready, run at minimum:

- `MainActivitySmokeTest`
- `TaskExecutionWorkerSmokeTest`

on API 34 AVD.

#### Step 4 — run the exact-alarm regression sweep

Run:

```bash
./scripts/run_exact_alarm_regression.sh --api34-avd AndroidClawApi34 --api31-avd AndroidClawApi31
```

This currently covers the most important cases:

- API 34 fresh-install deny/degrade path
- API 31 deny path
- API 31 allow path
- API 31 deny-after-allow path

This is a good minimal release matrix because:

- API 31 is where exact alarm special access semantics start to matter,
- API 34 is where deny-by-default becomes central for fresh installs, [R10]
- API 36 is already represented in the Gradle Managed Device smoke lane for current target-SDK behavior.

#### Step 5 — record the real outcomes

Create a repo-local evidence note such as:

- `docs/qa/exact-alarm-regression.md`

Record:

- device/API used
- whether the allow path required manual action or appops
- whether `dumpsys alarm` showed the scheduled exact alarm in the allow case
- event-log messages observed
- task/health UI screenshots or textual observations
- any emulator oddities

#### Step 6 — close the milestone only after evidence exists

Do not mark `m5` complete based only on unit tests or repo-side code inspection.

The milestone is complete only once:

- device-backed smoke is green,
- exact regression sweep is run,
- evidence is recorded,
- any repo fixes discovered by the run are implemented and revalidated.

### Files likely touched

- `runtime/scheduler/SchedulerDiagnostics.kt`
- `feature/health/*`
- `feature/tasks/*`
- possibly tests for health/tasks diagnostics
- `docs/SCHEDULER.md`
- `docs/TESTING.md` or `docs/qa/*`

### Acceptance criteria

- no silent “precise” promise remains
- exact alarms degrade honestly when unavailable
- notification visibility state is visible to the user
- device-backed evidence exists for the regression matrix

### Validation

- JVM tests for new diagnostics logic
- `:app:testDebugUnitTest`
- Windows AVD smoke + exact regression
- update docs after the run

---

## ws4-m9-completion — persistence hardening and upgrade-proofing

### Goal

Finish the remaining persistence hardening so that user data survives upgrades and routine maintenance.

### Why now

The repo has already crossed the threshold where destructive migration is unacceptable. Room’s official guidance is clear: explicit migrations and migration testing are the correct path. [R17]

### Current known state

Already true:

- DB version = 2
- explicit `1 -> 2` migration exists
- destructive fallback has been removed
- exported schemas are present
- migration instrumentation exists
- startup maintenance trims old task runs and event logs
- startup reschedules work after boot/package/time changes

### Ordered steps

#### Step 1 — freeze schema unless truly necessary

Do not casually add new columns/tables during this phase.

If a schema change becomes necessary, batch it deliberately into one migration rather than many small churny migrations.

#### Step 2 — expand migration fixtures

Strengthen migration testing with realistic fixture data covering:

- main session + extra sessions
- chat messages
- tasks of all schedule kinds
- task runs in multiple states
- bundled/local/workspace skills
- event logs

The goal is to prove **meaningful upgrades**, not just empty database upgrades.

#### Step 3 — test upgrade paths across all extant versions

At minimum, support and test:

- `1 -> 2`
- `1 -> latest`
- `2 -> latest` (if a new version appears)

If the schema remains at version 2 through v0 RC, then the focus is strengthening `1 -> 2` coverage and preserving discipline for the next version.

#### Step 4 — verify startup maintenance correctness

Explicitly validate that startup maintenance:

- creates/keeps the main session,
- trims only old task runs,
- trims only old event logs,
- never removes live tasks or live sessions,
- reschedules pending tasks after restart/upgrade.

#### Step 5 — validate receiver-driven reschedule scenarios

Use device-backed tests or focused manual verification for:

- reboot / `BOOT_COMPLETED`
- `MY_PACKAGE_REPLACED`
- time change
- timezone change

Not every scenario needs a giant UI test, but the behavior must be validated somewhere and documented.

#### Step 6 — document retention policy

Add or extend docs so the retention policy is explicit and stable:

- task-run retention window
- event-log retention window
- what is pruned and what is never pruned automatically

### Files likely touched

- Room migration tests
- `StartupMaintenance.kt`
- relevant DAO/repository tests
- possibly `docs/ARCHITECTURE.md`
- possibly new `docs/TESTING.md`

### Acceptance criteria

- migration coverage is meaningful, not empty
- no destructive fallback exists
- restart/upgrade/reinstall semantics are documented
- pruning is bounded and predictable

### Validation

- `:app:testDebugUnitTest`
- `:app:assembleDebugAndroidTest`
- migration instrumentation on real emulator/device

---

## ws5-m10-performance-and-size — prove the lightweight promise

### Goal

Turn “lightweight and fluid” from aspiration into measured repo evidence.

### Why now

The app is already architecturally small. The next step is to preserve that advantage and make it durable through the release process.

Android’s own guidance highlights Baseline Profiles as one of the clearest wins for startup and first-use responsiveness, and official app-optimization guidance recommends release shrinking/optimization with R8. [R16][R19]

### Ordered steps

#### Step 1 — run a startup and eagerness audit

Review startup and top-level screens for unnecessary eager work.

Concrete checks:

- no network call on startup
- no unconditional full skill reparse on every launch
- no unconditional full task-run history load on first render
- no screen-local flows kept hot when the screen is not visible unless clearly justified
- audit `SharingStarted.Eagerly` in feature viewmodels and convert to `WhileSubscribed` where safe

This is especially relevant because the current repo still has eager `stateIn(...)` usage in multiple feature viewmodels.

Current status:

- `ChatViewModel`, `TasksViewModel`, and `HealthViewModel` now use `SharingStarted.WhileSubscribed(5_000)` instead of eager collection
- startup remains bounded to local maintenance and scheduler restore work

#### Step 2 — create performance docs

Add:

- `docs/PERFORMANCE.md`

It should record:

- performance goals
- what is measured
- where the Baseline Profile lives
- how size is checked
- current known trade-offs

Current status:

- `docs/PERFORMANCE.md` exists and records the current startup posture, the Baseline Profile blocker, and the release-size posture

#### Step 3 — add Baseline Profile generation

Introduce a test-only `:baselineprofile` module (or equivalent official plugin setup) once the device lane is ready.

Cover at least these journeys:

- app launch
- open chat
- open tasks
- open skills
- open settings
- open health
- send one message with `FakeProvider`

If using a Gradle Managed Device for generation, keep `aosp` system image requirements in mind as documented by Android. [R16]

Current blocker:

- this workstation's WSL Gradle runtime currently fails TLS handshakes when resolving uncached AndroidX benchmark/profile artifacts from Google Maven
- do not check in partial `:baselineprofile` wiring that leaves the repo red; wait until dependency resolution is stable on the active host or a mirrored/preseeded artifact path exists
- this step is explicitly deferred for the current RC pass; record the absence of Baseline Profiles honestly in release evidence instead of blocking RC verification on it

#### Step 4 — generate and check in the profile

Once generation works:

- check in the generated Baseline Profile artifacts,
- document the exact generation command in `docs/PERFORMANCE.md`,
- use the generated task names rather than guessing.

#### Step 5 — evaluate release shrinking

Current release builds have `isMinifyEnabled = false`.

Because lightweight size matters, evaluate a conservative release-only optimization pass:

- enable R8/code shrinking,
- enable resource shrinking if safe,
- keep rules minimal and justified,
- validate release behavior before keeping the change.

Do this incrementally, not in one giant risky commit. Android’s optimization guidance explicitly supports staged adoption. [R19]

Current decision:

- keep shrinking disabled until there is a dedicated release-validation pass with install/launch evidence
- record size facts on the current unshrunk release artifact first rather than enabling R8 speculatively

#### Step 6 — record APK size and composition

After release build optimization is evaluated:

- measure release APK size,
- note the before/after delta,
- inspect composition with APK Analyzer or equivalent tooling,
- document any unexpectedly large dependency/resource contributors. [R19]

#### Step 7 — avoid fake precision budgets

Do **not** invent arbitrary performance budgets before measuring.

Instead:

1. measure the baseline,
2. record it,
3. set a small regression guard based on the observed baseline,
4. enforce that in future work.

### Files likely touched

- `app/build.gradle.kts`
- new `baselineprofile/` module or equivalent
- `docs/PERFORMANCE.md`
- possible viewmodel/startup code
- possible release ProGuard/R8 rules

### Acceptance criteria

- startup and first-use paths are profiled and documented
- a baseline profile exists in the repo
- release shrinking decision is explicit and validated
- size measurement exists in the repo docs

### Validation

- `:app:assembleRelease`
- fast suite
- baseline profile generation / verification tasks once configured
- smoke on at least one device/emulator after release changes

---

## ws6-release-surface-hardening — make the app self-explanatory to testers

### Goal

Ensure a tester can install and meaningfully use AndroidClaw without reading source code.

### Why this matters

The app’s main product promise is low-friction local use. Any remaining ambiguity in permission state, exact-alarm behavior, provider setup, or skill eligibility directly weakens that promise.

### Ordered steps

#### Step 1 — eliminate the last ambiguous UX states

Audit every top-level screen for:

- placeholder copy
- unexplained error text
- empty states with no next action
- missing permission guidance
- missing degradation guidance

#### Step 2 — add lightweight operator docs

Add at least:

- `docs/TESTING.md`
- `docs/PERFORMANCE.md`
- `docs/RELEASE_CHECKLIST.md`

Optional but useful:

- `docs/KNOWN_LIMITATIONS.md`

#### Step 3 — capture honest limitations

Make the following limitations explicit in docs and, where appropriate, in-app copy:

- exact alarms may be denied by default on Android 14+ [R10]
- notifications can be denied on Android 13+ [R12]
- standby bucket and quota behavior affect background work [R14]
- no browser automation
- no external channels
- no remote bridge in v0

#### Step 4 — preserve lightweightness in product design

Do not add a large onboarding wizard unless absolutely necessary.

Prefer:

- small banners,
- short explanatory cards,
- direct settings/task health cues,
- targeted permission prompts,
- good empty states.

### Acceptance criteria

- a new tester can configure FakeProvider or the OpenAI-compatible provider,
- a new tester can create and run tasks,
- a new tester can import and toggle skills,
- a new tester can understand exact-alarm degradation and notification limitations,
- repo docs cover testing and release steps.

### Validation

- manual walkthrough on all five top-level screens
- release checklist reviewed against the app
- screenshot or textual QA notes stored in repo docs

---

## ws7-v0-release-candidate — produce and prove the release candidate

### Goal

Freeze scope and produce a release candidate with repeatable evidence.

### Scope rule

Once this workstream starts, **do not add new feature categories**.

Only bug fixes, validation fixes, performance fixes, and documentation fixes are allowed.

### Ordered steps

#### Step 1 — run the fast local matrix

Required:

```bash
./gradlew :app:assembleDebug
./gradlew :app:testDebugUnitTest
./gradlew :app:lintDebug
./gradlew :app:assembleRelease
```

#### Step 2 — run the device matrix

Required minimum:

- API 34 AVD smoke
- API 31/API 34 exact-alarm regression sweep

Preferred additional coverage:

- `:app:pixel8Api36DebugAndroidTest` on a host where managed devices can boot successfully

#### Step 3 — run focused manual QA

Minimum manual QA checklist:

- launch app fresh
- FakeProvider message send
- OpenAI-compatible provider message send
- create session / switch session / rename / archive
- create `once` task
- create `interval` task
- create `cron` task
- `Run now`
- observe task run history
- import local skill zip
- enable/disable skill
- slash-invoke eligible skill
- verify health screen diagnostics
- verify exact-alarm degradation banner/state
- verify notification-permission warning if notifications are denied

#### Step 4 — verify degraded cases intentionally

Required degraded scenarios:

- provider auth failure
- provider timeout or connectivity failure
- exact alarm denied
- notifications denied/disabled
- ineligible skill
- unavailable tool

#### Step 5 — build the release artifact

Produce at least:

- release APK for sideload testing

If a Play path is later needed, an AAB can be secondary, but the product requirement for v0 remains “single installable app that works locally.”

#### Step 6 — record release evidence

Create or update a release evidence doc such as:

- `docs/RELEASE_CHECKLIST.md`
- `docs/qa/rc-validation.md`

Record:

- git commit / tag
- validation commands
- device matrix results
- known limitations
- APK size
- whether R8/resource shrinking is enabled
- whether Baseline Profiles are present

### Acceptance criteria

- scope is frozen
- validation matrix is green or blocked only by explicitly documented external facts
- release APK exists
- known limitations are honest and repo-local
- the app can be handed to a tester without hidden setup knowledge

---

## 11. Detailed sequencing recommendation

This is the recommended actual order of execution.

### Packet A — plan and harness hygiene

1. adopt `PLANv4.md`
2. remove hardcoded Linux JDK path from shell wrappers
3. add/improve host preflight output
4. add `docs/TESTING.md` skeleton

### Packet B — scheduler diagnostics honesty

1. surface notification permission / notification-enabled state in diagnostics
2. add health/tasks UI warnings
3. add focused JVM tests
4. update docs

### Packet C — Windows host completion and first device proof

1. human completes WHPX + SDK + AVD setup
2. run API 34 smoke tests
3. fix any discovered breakage
4. record evidence

### Packet D — exact-alarm closure

1. run full exact regression sweep
2. fix any issues exposed
3. update docs/qa evidence
4. mark `m5` complete only after rerun is green

### Packet E — persistence hardening completion

1. strengthen migration fixtures/tests
2. validate startup maintenance/reschedule semantics
3. document retention/upgrade behavior

### Packet F — performance and size

1. startup/eagerness audit
2. add `docs/PERFORMANCE.md`
3. add Baseline Profile support when the Google Maven dependency-resolution blocker is cleared
4. evaluate R8/resource shrinking
5. record APK size

### Packet G — RC proof

1. freeze scope
2. run full matrix with Baseline Profiles explicitly deferred and called out in release evidence
3. build release APK
4. record release evidence
5. cut RC

This packetization is intentionally small and agent-friendly, consistent with the “small legible steps + validation loops” approach described in the harness engineering article. [R1]

---

## 12. Validation matrix

### 12.1 Always-run fast matrix

Use this after most code changes:

```bash
./gradlew :app:assembleDebug :app:testDebugUnitTest :app:lintDebug
```

Notes:

- production lint must remain green
- if the AGP/Kotlin test-source lint crash persists, prefer a narrow test-source lint workaround plus explicit compile/test coverage over leaving the whole fast loop red

### 12.2 Device smoke matrix

Preferred managed-device path on acceleration-capable hosts:

```bash
./gradlew :app:pixel8Api36DebugAndroidTest -Pandroid.testoptions.manageddevices.emulator.gpu=swiftshader_indirect
```

Current workstation fallback:

```bash
./scripts/run_windows_android_test.sh --avd AndroidClawApi34 --test-class ai.androidclaw.app.MainActivitySmokeTest
./scripts/run_windows_android_test.sh --avd AndroidClawApi34 --test-class ai.androidclaw.runtime.scheduler.TaskExecutionWorkerSmokeTest
```

### 12.3 Exact-alarm regression matrix

```bash
./scripts/run_exact_alarm_regression.sh --api34-avd AndroidClawApi34 --api31-avd AndroidClawApi31
```

### 12.4 Release matrix

```bash
./gradlew :app:assembleRelease
```

Add Baseline Profile tasks after the profile module/setup exists.

### 12.5 What not to do

- do not rely on “wait a few minutes and see” manual scheduler tests
- do not rely on the old split-adb LDPlayer flow
- do not broaden instrumentation coverage just to inflate test counts
- do not gate every PR on expensive/flaky device suites too early

---

## 13. Human-blocker matrix

Codex should stop and record a blocker immediately if the next required step is any of the following:

1. Windows elevation / UAC approval
2. Android Studio first-run GUI setup
3. manual AVD creation in Device Manager
4. API-key entry for real-provider manual QA
5. release signing with a human-held key

For each blocker, record:

- exact missing prerequisite
- exact command attempted
- exact observed output
- exact human action needed next

Do not pad the blocker with vague language.

---

## 14. Risk register

### Risk 1 — exact alarms work technically but reminders are not user-visible

Cause:

- notifications denied or disabled on Android 13+

Mitigation:

- surface notification diagnostics and warnings
- include this in manual QA

### Risk 2 — device suite remains blocked by host setup

Cause:

- Windows SDK/AVD not completed

Mitigation:

- separate host-manual steps from repo work
- keep repo-only hardening moving in parallel

### Risk 3 — validation harness becomes workstation-specific again

Cause:

- local path assumptions creeping into scripts/docs

Mitigation:

- remove current hardcoded JDK path
- require explicit preflight

### Risk 4 — performance regresses while feature work looks “done”

Cause:

- eager loading, always-hot flows, unbounded data surfaces, release shrinking never enabled

Mitigation:

- startup audit
- baseline profiles
- release-size measurement
- explicit performance doc

### Risk 5 — migration bugs are discovered too late

Cause:

- schema tests too shallow

Mitigation:

- richer migration fixtures
- device-backed migration tests before RC

### Risk 6 — scope creeps after the app is already functionally sufficient

Cause:

- adding streaming, multimodal, new tools, or external channels before RC

Mitigation:

- explicit feature freeze before ws7
- post-v0 backlog section below

---

## 15. Post-v0 backlog (not for this phase)

These are reasonable future directions, but they are not part of the current execution path:

- streaming provider output
- multimodal provider inputs
- richer typed tools
- external channels
- remote bridge mode
- ClawHub/registry synchronization
- more advanced skill compatibility coverage
- richer session compaction/summarization
- stricter security hardening
- broader CI device coverage
- OEM-specific battery-restriction guidance

Keep them visible, but do not let them interfere with RC delivery.

---

## 16. Progress seed

Use this section as a living checklist. Keep entries short.

- [x] bootstrap shell, single-module app, manual DI, fake provider
- [x] Room persistence and schema export
- [x] scheduler core, worker execution, run history, startup reschedule
- [x] bundled/local/workspace skills lifecycle
- [x] settings + OpenAI-compatible provider v1
- [x] health/tasks/skills/settings/chat functional top-level screens
- [x] repo-owned Windows emulator scripts, no `cmd.exe` path
- [x] exact-alarm instrumentation added for API 34 and API 31
- [x] migration floor and startup maintenance shipped
- [x] PLANv4 adoption
- [x] harness normalization (remove user-specific JDK path, add preflight)
- [x] Windows host completion
- [x] exact-alarm regression evidence recorded
- [x] notification visibility diagnostics added
- [x] `m5` formally closed
- [x] `m9` formally closed
- [ ] Baseline Profile support added
- [x] lint fast-loop workaround for test-source FIR crash recorded
- [x] release shrinking decision recorded
- [x] RC validation evidence recorded

---

## 17. Discoveries seed

Add only facts that changed a real implementation choice.

- OpenClaw Android is still a companion node, so AndroidClaw must remain an Android-native host implementation rather than a port. [R2]
- WorkManager remains the correct durability primitive; exact alarms must stay narrow and user-visible. [R8][R10][R11]
- Android 13+ notification permission changes mean scheduler truthfulness also depends on visible-notification state, not only exact-alarm state. [R12]
- Android 16 quota behavior makes bounded background work and visible diagnostics more important, especially with `targetSdk = 36`. [R14]
- Managed Devices remain the official Gradle-owned test path, but this workstation currently still needs the Windows AVD fallback. [R15]
- Mobile/device tests are more failure-prone than local deterministic tests; narrow high-value device coverage is preferable to a broad flaky suite. [R21][R22]
- Background wakeups materially affect battery life, reinforcing the no-daemon/no-polling design. [R23]
- This workstation’s default WSL `java` is still Java 8, so the Windows AVD wrappers now need an explicit Java 17 resolution order (`ANDROIDCLAW_JAVA_HOME` -> `JAVA_HOME` -> `PATH`) instead of guessing private paths.
- The official Windows emulator lane is now proven on this workstation with `AndroidClawApi34` and `AndroidClawApi31`; `m5` no longer has a host-side blocker.
- The existing `Medium_Phone_API_36.1` AVD is already sufficient for migration and startup-maintenance device proof, so `m9` no longer depends on the exact-alarm API 31/API 34 AVD creation step.
- On this WSL-mounted workspace, the Windows-AVD wrappers need the same stable Gradle flags as the fast suite (`--no-daemon`, in-process Kotlin compiler, no configuration/build cache) to avoid hanging in the artifact-build phase before PowerShell handoff.
- PowerShell `-File` argument binding does not safely preserve repeated array-style AVD parameters from the WSL wrapper, so the preflight harness now normalizes comma-separated required-AVD input before checking availability.
- On this workstation, AGP 8.13/Kotlin FIR can crash during `lintAnalyzeDebugUnitTest` and `lintAnalyzeDebugAndroidTest` even when production lint results are clean, so test-source lint cannot be treated as a trustworthy gate until the toolchain bug is worked around or upgraded.
- Lint's network-backed version detectors are also a poor fit for the repo fast loop on this workstation; they can stall validation in outbound HTTP metadata fetches without improving product correctness.
- RC verification can proceed honestly without Baseline Profiles as long as their absence is recorded explicitly in release evidence and known limitations.

---

## 18. Decision log seed

- Decision: keep a single `:app` module through v0.  
  Rationale: smallest build/runtime surface, easiest agent legibility.

- Decision: keep both Managed Devices and Windows AVD wrappers.  
  Rationale: Managed Devices are the official long-term path, but this workstation currently needs a host-owned fallback. [R15]

- Decision: do not add new feature categories before RC.  
  Rationale: current repo already covers the v0 product thesis; proof matters more than expansion.

- Decision: extend scheduler diagnostics to include notification visibility state.  
  Rationale: a “precise” reminder is not meaningfully user-visible if notifications are denied. [R12]

- Decision: make the WSL Windows-AVD harness fail early on Java and host-preflight gaps.  
  Rationale: predictable diagnostics are more valuable than silently guessing workstation-local paths.

- Decision: use any available official Windows AVD for migration and startup-maintenance proof, and reserve the named `AndroidClawApi34` / `AndroidClawApi31` requirement for the exact-alarm matrix only.  
  Rationale: `m9` depends on real device-backed upgrade/startup evidence, but it does not require the API-specific exact-alarm semantics that still block `m5`.

- Decision: the WSL Windows-AVD wrappers build APK artifacts with the same stable Gradle flags used by the repo’s fast validation lane.  
  Rationale: this mounted-workspace environment can stall during the wrapper’s pre-PowerShell Gradle step unless it uses the known-good single-use daemon / no-cache settings.

- Decision: evaluate R8/resource shrinking before RC.  
  Rationale: lightweight size matters more than obfuscation for this project phase. [R19]

- Decision: if the AGP/Kotlin test-source lint crash persists, keep production lint enabled and disable only test-source lint analysis.  
  Rationale: this preserves real source lint coverage and honest fast-loop green status without relying on a known toolchain crash.

- Decision: disable lint's network-backed version-check detectors in the repo fast loop.  
  Rationale: they add outbound HTTP stalls and workstation variance without improving product-correctness coverage.

- Decision: keep release shrinking disabled for now and record that choice in repo docs.  
  Rationale: an explicit keep-disabled decision is better than speculative R8 enablement without release validation evidence.

- Decision: defer Baseline Profile support for the current RC pass and proceed with RC verification without it.  
  Rationale: the current blocker is external dependency resolution in this WSL environment, and the repo should record that limitation honestly rather than stall the RC proof workstream.

- Decision: do not invest in heavy security work before RC.  
  Rationale: the next phase is limited and should prioritize validation, reliability, startup, and size.

---

## 19. References

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

- **[R7]** NanoClaw GitHub README.  
  <https://github.com/qwibitai/nanoclaw/blob/main/README.md>

- **[R8]** Android Developers, *Task scheduling / persistent background work*.  
  <https://developer.android.com/develop/background-work/background-tasks/persistent>

- **[R9]** Android Developers, *Define work requests* and *Managing work*.  
  <https://developer.android.com/develop/background-work/background-tasks/persistent/getting-started/define-work>  
  <https://developer.android.com/develop/background-work/background-tasks/persistent/how-to/manage-work>

- **[R10]** Android Developers, *Schedule exact alarms are denied by default*.  
  <https://developer.android.com/about/versions/14/changes/schedule-exact-alarms>

- **[R11]** Android Developers, *Schedule alarms*.  
  <https://developer.android.com/develop/background-work/services/alarms>

- **[R12]** Android Developers, *Notification runtime permission* / Android 13 notification permission docs.  
  <https://developer.android.com/develop/ui/views/notifications/notification-permission>  
  <https://developer.android.com/about/versions/13/behavior-changes-all#notification-permission>

- **[R13]** Android Developers, *Foreground service types are required* and *Restrictions on starting a foreground service from the background*.  
  <https://developer.android.com/about/versions/14/changes/fgs-types-required>  
  <https://developer.android.com/develop/background-work/services/fgs/restrictions-bg-start>

- **[R14]** Android Developers, *App Standby Buckets*, Android 16 behavior changes, and long-running worker guidance.  
  <https://developer.android.com/topic/performance/appstandby>  
  <https://developer.android.com/about/versions/16/behavior-changes-all>  
  <https://developer.android.com/develop/background-work/background-tasks/persistent/how-to/long-running>

- **[R15]** Android Developers, *Scale your tests with build-managed devices*.  
  <https://developer.android.com/studio/test/managed-devices>

- **[R16]** Android Developers, *Baseline Profiles overview* and *Create Baseline Profiles*.  
  <https://developer.android.com/topic/performance/baselineprofiles/overview>  
  <https://developer.android.com/topic/performance/baselineprofiles/create-baselineprofile>

- **[R17]** Android Developers, *Migrate your Room database* and *Test and debug your database*.  
  <https://developer.android.com/training/data-storage/room/migrating-db-versions>  
  <https://developer.android.com/training/data-storage/room/testing-db>

- **[R18]** Android Developers, *Open files using the Storage Access Framework*.  
  <https://developer.android.com/guide/topics/providers/document-provider>

- **[R19]** Android Developers, *Enable app optimization with R8*, *Reduce app size*, and *APK Analyzer*.  
  <https://developer.android.com/topic/performance/app-optimization/enable-app-optimization>  
  <https://developer.android.com/topic/performance/reduce-apk-size>  
  <https://developer.android.com/studio/debug/apk-analyzer>

- **[R20]** Android Developers, *Testing Worker implementation*, *Integration tests with WorkManager*, and *Debug WorkManager*.  
  <https://developer.android.com/develop/background-work/background-tasks/testing/persistent/worker-impl>  
  <https://developer.android.com/develop/background-work/background-tasks/testing/persistent/integration-testing>  
  <https://developer.android.com/develop/background-work/background-tasks/testing/persistent/debug>

- **[R21]** Thorve et al., *An Empirical Study of Flaky Tests in Android Apps* (Virginia Tech / empirical study PDF).  
  <https://people.cs.vt.edu/nm8247/publications/empirical-study-flaky-pdf.pdf>

- **[R22]** Pontillo, Palomba, Ferrucci, *Test Code Flakiness in Mobile Apps: The Developer’s Perspective*.  
  <https://doi.org/10.1016/j.infsof.2023.107394>

- **[R23]** Martins, Cappos, Fonseca, *Selectively Taming Background Android Apps to Improve Battery Lifetime* (USENIX ATC 2015).  
  <https://cs.brown.edu/~rfonseca/pubs/martins15tamer.pdf>
