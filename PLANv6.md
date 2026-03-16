# AndroidClaw Execution Plan v6

> Status: canonical execution plan as of 2026-03-16.
>
> This file supersedes `PLANv5.md` as the active execution plan. `PLANv1.md` through `PLANv5.md` remain historical records.

---

## 1. Why this plan exists

AndroidClaw is past the “can this architecture work at all?” stage.

The latest repository snapshot already proves that the core product concept is real:

- a Kotlin-first Android app can host the runtime locally
- a single-APK baseline is viable
- the app already ships durable chat sessions, typed tools, `SKILL.md`-based skills, and scheduled automations
- Room persistence, migration tests, scheduler restore, exact-alarm degradation, and installable optimized builds are already in place
- the repository already has agent-first documentation, CI, and device-validation lanes

That means `PLANv6.md` should not behave like an early greenfield plan. It should behave like a **post-foundation execution plan** that is strict about sequencing and strict about leverage.

The right next goal is not “add many more features.” The right next goal is:

> make AndroidClaw feel good with real providers, keep it lightweight, and close the highest-value product gaps without destabilizing the repo.

The supplied audit report is useful and directionally correct, but it is also **partly stale relative to the latest zip**. Some items that were previously open are already closed in the current repository. This plan therefore does two things at once:

1. it reconciles the audit against the actual repo snapshot
2. it turns the reconciled truth into an ordered execution plan that Codex can follow with low ambiguity

This file is intentionally written in an agent-first style. OpenAI’s harness-engineering guidance argues for a short `AGENTS.md`, docs as system of record, and checked-in execution plans with progress and decision logs. That model fits AndroidClaw well and should remain the operating model for the repo. [R1]

---

## 2. Read order and operating rules

Before making any non-trivial code change, read in this order:

1. `AGENTS.md`
2. `README.md`
3. `PLANv6.md`
4. `docs/ARCHITECTURE.md`
5. `docs/SCHEDULER.md`
6. `docs/SKILLS_COMPAT.md`
7. `docs/TESTING.md`
8. `docs/PERFORMANCE.md`
9. `docs/RELEASE_CHECKLIST.md`
10. `docs/KNOWN_LIMITATIONS.md`
11. `docs/BETA_HANDOFF.md` if the current task touches tester-facing distribution
12. `docs/qa/*` if the current task changes validation truth

Operating rules:

- Treat `PLANv6.md` as the canonical execution plan.
- Keep `AGENTS.md` short. Do not move architecture or sequencing truth into `AGENTS.md`.
- Prefer small diffs and narrow, reviewable commits.
- Add tests in the same workstream that changes behavior.
- Update docs when implementation changes user-visible behavior, validation truth, or product boundaries.
- Do not silently expand scope from “make the baseline better” to “become a desktop gateway clone”.
- Do not add heavy frameworks when a small Kotlin-first solution is enough.
- When a workstream becomes too large, split it into a child plan under `docs/exec-plans/active/` and link it back here.
- Keep the fast loop green at all times:
  - `./gradlew :app:assembleDebug`
  - `./gradlew :app:testDebugUnitTest`
  - `./gradlew :app:lintDebug`
- Keep shipping validation truthful:
  - `./gradlew :app:assembleQa`
  - `./gradlew :app:assembleRelease`
  - `./gradlew :app:bundleRelease`
- If a change affects device behavior, add or update evidence under `docs/qa/`.

---

## 3. Executive summary

### 3.1 Product position for this phase

AndroidClaw remains:

- an **Android-native local AI assistant host**
- a **single-APK-first product**
- a **Kotlin-first implementation**
- a **lightweight reinterpretation** of the OpenClaw / NanoClaw ideas that matter most on phone:
  - sessions
  - tools
  - skills
  - scheduled automations

AndroidClaw still does **not** become:

- a Node.js port
- a Docker host
- a browser-automation shell
- a desktop runtime transplant
- a cloud-sync product
- a remote-first companion app
- a shell-execution app

That remains correct given the public ecosystem facts:

- OpenClaw’s docs still frame Android as a companion node rather than the main Gateway host. [R2]
- OpenClaw’s modern runtime centers typed tools, `SKILL.md`, and Gateway-managed cron semantics. [R3][R4][R5]
- NanoClaw remains a Node 20+/container-oriented desktop/server runtime. [R6]

### 3.2 What the latest repo already proves

The current repo already proves:

- the single-module approach is viable
- manual DI is still manageable at current scale
- the scheduler architecture is viable
- the skills loader/import flow is viable
- the exact-alarm degradation model is viable
- the Windows AVD lane is viable
- the exact-alarm regression lane is now device-backed and green
- the app can already produce a tiny optimized artifact
- the repo is already agent-drivable

### 3.3 What `PLANv6` changes versus `PLANv5`

`PLANv5` correctly focused on repo truth, task tools, optimized builds, R8, CI parity, and beta handoff. Most of that work now exists in the repo.

`PLANv6` moves the center of gravity to the next real bottleneck:

1. **human-perceived responsiveness**
   - streaming output
   - better visible turn state
   - cancel/retry
2. **context stability**
   - replace the hardcoded recent-message cap with budgeted context assembly
   - introduce session-summary foundations
3. **provider breadth where it matters**
   - keep OpenAI-compatible path
   - add a native Anthropic path next
4. **lightweight maintainability**
   - migrate Room from `kapt` to `ksp`
   - keep build speed and code legibility improving
5. **release hygiene without turning security into the whole project**
   - low-cost network/backup/distribution fixes
   - no heavyweight enterprise-security program in this phase

### 3.4 The single most important principle for v6

**Optimize for “pleasant real use” before breadth.**

The best next work is the work that makes a human feel:

- the app responds immediately
- the app does not lose context too quickly
- the app gives a clear recovery path when a provider fails
- the app remains small, fast, and installable

Do **not** spend this phase adding broad feature categories if the main chat experience still feels inert or brittle.

---

## 4. Current repository snapshot (audited from the attached zip)

### 4.1 Build and platform baseline

Audited from the latest attached repository snapshot:

- module count: one production module (`:app`)
- language/runtime: Kotlin + Java 17 toolchain
- UI: Jetpack Compose (Material 3)
- persistence: Room with exported schemas
- background work: WorkManager + AlarmManager exact path
- networking: OkHttp
- serialization: kotlinx.serialization + SnakeYAML
- `compileSdk = 36`
- `targetSdk = 36`
- `minSdk = 26`

This is still aligned with the Android-native, low-dependency, small-APK goal.

### 4.2 Current top-level repository shape

The repo now contains the expected agent-first surface:

- `AGENTS.md`
- `README.md`
- `PLANv1.md` through `PLANv5.md`
- `docs/ARCHITECTURE.md`
- `docs/SCHEDULER.md`
- `docs/SKILLS_COMPAT.md`
- `docs/TESTING.md`
- `docs/PERFORMANCE.md`
- `docs/BETA_HANDOFF.md`
- `docs/RELEASE_CHECKLIST.md`
- `docs/KNOWN_LIMITATIONS.md`
- `docs/qa/*`
- `.github/workflows/android.yml`
- validation scripts for Windows AVD and exact-alarm regression

This is already a strong repo shape. The main truth-drift issue is simply that the top-level pointers still target `PLANv5.md`.

### 4.3 Runtime surface already present

The current runtime already includes:

- local chat with durable session/message history
- deterministic `FakeProvider`
- one real-provider path: `OpenAiCompatibleProvider`
- tool-call runtime loop in `AgentRunner`
- built-in tools for tasks, health, sessions, skills, and notifications
- slash invocation for user-invocable skills
- bundled / local / workspace skill loading
- task scheduling with `once`, `interval`, and `cron`
- exact-alarm degradation and scheduler diagnostics
- task-run history
- startup + broadcast restore for scheduler state

### 4.4 Builds and distribution surface already present

The current repo already ships:

- `debug` build for the fast loop
- `qa` build as an installable, release-like lane with R8 and resource shrinking enabled
- `release` build with shrinking enabled
- `bundleRelease` output for Play-style distribution prep
- GitHub Actions jobs for fast validation and packaging validation

Measured optimized artifact sizes from current docs are already around the intended lightweight target.

### 4.5 Test surface already present

The repo already has:

- broad JVM test coverage for data, repositories, runtime, scheduler, skills, and view models
- instrumentation coverage for startup, migration, exact alarms, and task worker smoke
- Windows AVD wrappers from WSL
- exact-alarm regression evidence recorded under `docs/qa/`

This is more than enough foundation for the next phase. The problem is no longer “lack of tests”; it is “the next tests should target the right missing behavior.”

---

## 5. Audit reconciliation: what is stale, what is still true

The supplied review is helpful, but the latest repo snapshot changes the status of several items.

### 5.1 Items from the review that are already closed in the current repo

These should **not** be treated as major open gaps anymore:

1. **Task tools are now more complete than an early minimal surface.**
   The current runtime already includes `tasks.create`, `tasks.update`, `tasks.delete`, `tasks.enable`, `tasks.disable`, and `tasks.run_now`, in addition to listing/getting tasks.

2. **Tool execution context already exists.**
   `ToolExecutionContext` is now part of the runtime contract and carries session/task/mode/origin metadata.

3. **The repo side of the Windows emulator migration is already done.**
   The repo no longer depends on LDPlayer as an official lane.

4. **Exact-alarm regression evidence is already device-backed and green.**
   `docs/qa/exact-alarm-regression.md` in the current snapshot records a passing matrix.

5. **Optimized QA / release packaging already exists.**
   `qa` and `release` builds are already shrunk; the earlier size-reduction phase is not hypothetical anymore.

6. **Secret storage is stronger than the review assumed.**
   The current `EncryptedStringStore` uses Android Keystore-backed AES/GCM rather than a plain-text placeholder path.

### 5.2 Items from the review that remain valid and important

These still matter and should drive the next phase:

1. **No streaming provider UX yet.**
2. **Context window management is still crude.**
   `AgentRunner` still pulls a fixed recent-message slice rather than using a budgeted selector.
3. **Retry/cancel/recovery UX is still thin.**
4. **Only one real provider family is supported.**
5. **No chat export/share yet.**
6. **No search yet for sessions/messages/tasks.**
7. **Session summaries are not actually in use yet.**
8. **`kapt` is still in use for Room.**
9. **Baseline Profiles are still deferred.**
10. **There is still no explicit network security config.**
11. **Secret preference backup/restore hygiene is not yet correct.**
12. **There are still no meaningful Compose UI tests for the live chat UX.**
13. **No end-to-end scripted tool-loop test focuses on the real user path.**

### 5.3 Items from the review that are directionally right but should be re-prioritized

These are worth doing eventually, but should not lead the plan:

- Hilt/Koin or other DI framework adoption
- full detekt policy rollout
- crash-reporting SDK integration
- image/file multimodal support
- local-model runtime embedding on device
- browser automation
- cloud sync
- PDF export in the near term

The reason is simple: each of those can wait longer than streaming, context management, and provider-failure recovery.

---

## 6. External facts that should shape the plan

The plan should respect the following ecosystem facts.

### 6.1 Agent-first repo operations

OpenAI’s harness-engineering guidance recommends short agent instructions, checked-in execution plans, and repository docs as the real system of record. AndroidClaw’s repo already follows this direction and should continue doing so. [R1]

### 6.2 OpenClaw semantics worth preserving

OpenClaw’s public docs make three things clear:

- Android is currently framed as a **companion node** rather than the main host. [R2]
- Skills are runtime-loaded `SKILL.md` folders with precedence and config semantics. [R3]
- Tools and cron are first-class runtime contracts. [R4][R5]

AndroidClaw should therefore continue to preserve **behavioral contracts**, not source-code or host-environment identity.

### 6.3 NanoClaw is not the right runtime transplant target

NanoClaw remains server/desktop oriented and container-oriented. That is useful as inspiration but not as the literal deployment model for Android. [R6]

### 6.4 Android background work is capability-constrained

Persistent background work belongs on WorkManager; exact alarms are restricted and, on recent Android versions, can be denied by default depending on category and install state. Background timing is also affected by standby buckets and quota behavior. [R7][R8][R9]

That means AndroidClaw should keep its current hybrid scheduler model and should not pretend desktop-style daemon guarantees exist on phone.

### 6.5 Baseline Profiles help startup but are not free

Android’s official guidance says Baseline Profiles can materially improve first-launch and interaction performance, often around the 30% range in representative cases. [R10]

They are valuable, but they are not worth blocking the core UX plan on an environment that still cannot support the collection lane cleanly.

### 6.6 `kapt` is no longer the preferred long-term path

Kotlin’s own guidance places `kapt` in maintenance mode and recommends KSP where supported. Room supports KSP and modern Room docs increasingly assume that route. [R11][R12]

### 6.7 Streaming is the right next provider capability

Both OpenAI and Anthropic document streaming via server-sent events. OpenAI still supports streaming on Chat Completions with `stream: true`, and Anthropic supports Messages streaming with `stream: true` and documents distinct event types. [R13][R14]

Since AndroidClaw already uses Chat Completions for compatibility, the right next step is **not** a wholesale provider API migration. The right next step is to add streaming on the current compatibility path first.

### 6.8 OkHttp already has an appropriate streaming primitive

OkHttp provides an SSE/EventSource path that is a natural fit for AndroidClaw’s existing networking stack. [R15]

### 6.9 Startup and release quality should be measured, not guessed

Android vitals and app-startup guidance emphasize measuring startup quality and release regressions rather than assuming they are acceptable. [R16]

### 6.10 Compose tests should stay purposeful and small

Official Compose testing guidance is strong enough to justify a small set of UI tests, but it does not justify flooding the repo with fragile screenshot or animation-heavy tests. [R17]

### 6.11 Auto Backup and encrypted preferences have real restore caveats

Android’s backup docs explicitly say that shared preferences are backed up by default unless excluded, and Android’s encrypted-preference/file docs warn that encrypted data should not be backed up if the restoring device may not have the encryption key. [R18][R19][R20]

AndroidClaw’s current backup rules include all shared preferences, which is therefore a hygiene bug for the provider/skill secret stores.

### 6.12 Distribution should eventually use the normal Android channel

Google’s guidance continues to favor App Bundles, Play App Signing, and staged/internal testing channels for broader distribution. [R21][R22][R23]

### 6.13 Long-running chat quality benefits from summary-based memory

Recent long-context and long-dialogue work continues to support the idea that recursive or hierarchical summarization is a practical way to preserve useful context without replaying unbounded full history. [R24][R25]

### 6.14 Human response-time expectations matter

Classic HCI work on response time still supports the common-sense conclusion that users perceive long, silent waits as worse than incremental feedback. Streaming is therefore not cosmetic; it is a first-order UX improvement. [R26]

---

## 7. Product goals and release horizon

This plan assumes three practical horizons.

### 7.1 Horizon A: v0.2.0 — “Human-usable real-provider beta”

This is the highest-priority horizon.

Definition:

- OpenAI-compatible provider can stream visibly
- chat UI shows live progress instead of only waiting in silence
- users can cancel a running turn
- users can retry a failed provider turn without awkward manual re-entry
- context management is better than a fixed last-32-message slice
- the repo stays lightweight and green

This horizon is the real target of `PLANv6`.

### 7.2 Horizon B: v0.3.0 — “Context-stable and provider-broader beta”

Definition:

- session summaries exist in usable form
- native Anthropic provider exists
- basic export/share exists
- basic search exists
- Room has moved to KSP
- release hygiene is improved

### 7.3 Horizon C: v0.4.0 — “Distribution-ready RC lane”

Definition:

- signing path is formalized
- internal/beta distribution path is formalized
- performance evidence is updated
- optional Baseline Profile lane is either working or explicitly deferred again with evidence

---

## 8. Non-goals and explicit deferrals

These are **not** the core of v6 and should not silently become the plan:

- browser automation
- remote bridge mode as a required baseline
- external chat-channel integrations
- shell execution
- cloud sync
- on-device local model runtime embedding
- full multimodal chat input
- PDF export as a near-term requirement
- Hilt/Koin migration
- enterprise security hardening
- full crash telemetry integration
- full-text search / FTS as a first implementation
- large-scale refactoring into multiple production modules

Notes:

- Search is in scope, but **basic search first**, not FTS-first.
- security hygiene is in scope, but **low-cost hygiene only** for this phase.
- release/distribution is in scope, but **without turning Play/ops work into the whole plan**.

---

## 9. Architecture invariants for v6

The following architecture rules should remain true unless a later plan explicitly changes them.

### 9.1 Keep the production app single-module for now

Do not split the production app into many modules during v6.

Reason:

- the current codebase size does not justify modularization overhead
- agent velocity is higher in the current structure
- the real current problems are UX and runtime behavior, not module boundaries

Exception:

- a dedicated Baseline Profile / benchmark module may be introduced later if and only if the environment supports it cleanly

### 9.2 Keep manual DI, but make it less painful

Do not adopt Hilt/Koin during v6.

Instead:

- keep `AppContainer`
- split it into small factory helpers or dependency bundles only when readability clearly improves
- avoid framework churn during streaming/context work

### 9.3 Add provider capabilities without breaking the current provider SPI

`ModelProvider` should evolve in a backward-compatible direction:

- current `generate()` path remains valid
- a streaming path is added rather than replacing the current call entirely
- providers should declare capabilities explicitly

### 9.4 Preserve the scheduler contract as-is

Do not re-architect the scheduler during v6.

The scheduler already has the right shape for Android:

- WorkManager for durable background work
- exact-alarm path when available
- clear degradation when exact delivery is not available

### 9.5 Keep partial streaming text ephemeral until completion

For the first streaming implementation:

- do **not** persist every token/chunk to Room
- keep partial assistant text in memory/UI state
- persist the final assistant message only when the turn reaches a terminal success state

Reason:

- fewer DB writes
- simpler cancellation semantics
- easier to reason about transcript truth
- lower risk of half-persisted garbage states

### 9.6 Improve context by budgeting first, token perfection later

The first context improvement should use a conservative budgeted selector, not a heavy tokenizer dependency.

Reason:

- lightweight goal matters
- the current hardcoded last-32 slice is much worse than a conservative char-/unit-budget selector
- a rough budgeted selector is a high-value intermediate step

### 9.7 Search should start as indexed SQL + `LIKE`, not FTS

Start simple:

- indexed title queries where possible
- bounded message-content search with `LIKE`
- only introduce FTS if real usage or scale justifies it

### 9.8 Security fixes must stay proportional

During v6, prioritize low-cost, high-signal hygiene only:

- explicit backup exclusions for secret stores
- explicit network security posture
- signing/distribution readiness

Do **not** let security work consume the bandwidth needed for streaming, context, and provider UX.

---

## 10. Ordered workstreams

The workstreams below are ordered. A later workstream may begin early only if it does not distract from a blocking earlier one.

---

### WS0 — Adopt `PLANv6` and restore documentation truth

#### Why this workstream exists

The repo should have one clear active plan. Right now several top-level pointers still reference `PLANv5.md`.

#### Goals

- make `PLANv6.md` the canonical active plan
- update repo pointers so agents stop reading the wrong plan first
- avoid plan/document drift before more code lands

#### Implementation steps

1. Add `PLANv6.md` to repo root.
2. Update `AGENTS.md` so every reference to the active plan points to `PLANv6.md`.
3. Update `README.md` so the “Execution plan” link points to `PLANv6.md`.
4. Update any docs that explicitly say `PLANv5.md` is canonical.
5. Add one short note to `PLANv5.md` stating it is superseded.

#### Acceptance criteria

- no top-level documentation points to `PLANv5.md` as the active plan
- `AGENTS.md` read order points to `PLANv6.md`
- `README.md` points to `PLANv6.md`

#### Validation

- `rg -n "PLANv5.md|PLANv6.md" AGENTS.md README.md docs PLANv*.md`
- manual inspection of top-level links

---

### WS1 — Introduce a streaming-capable provider/runtime contract

#### Why this workstream exists

The app can already complete turns, but it cannot yet **show useful progress** during long real-provider calls. The current provider contract is effectively whole-response only.

#### Goals

- add streaming capability without breaking the current provider abstraction
- keep batch generation available for scheduler/background flows
- create an agent-level event model suitable for chat UI consumption

#### Concrete design

Add a new streaming-capable provider SPI along these lines:

```kotlin
sealed interface ModelStreamEvent {
    data class TextDelta(val text: String) : ModelStreamEvent
    data class ToolCallDelta(
        val index: Int,
        val idPart: String? = null,
        val namePart: String? = null,
        val argumentsPart: String? = null,
    ) : ModelStreamEvent
    data class Completed(val response: ModelResponse) : ModelStreamEvent
}

data class ProviderCapabilities(
    val supportsStreamingText: Boolean = false,
    val supportsStreamingToolCalls: Boolean = false,
    val supportsImages: Boolean = false,
    val supportsFiles: Boolean = false,
)

interface ModelProvider {
    val id: String
    val capabilities: ProviderCapabilities
    suspend fun generate(request: ModelRequest): ModelResponse
    fun streamGenerate(request: ModelRequest): Flow<ModelStreamEvent> = flow {
        emit(ModelStreamEvent.Completed(generate(request)))
    }
}
```

Then add an agent-level event model such as:

```kotlin
sealed interface AgentTurnEvent {
    data class AssistantTextDelta(val text: String) : AgentTurnEvent
    data class ToolStarted(val name: String) : AgentTurnEvent
    data class ToolFinished(val name: String, val success: Boolean, val summary: String) : AgentTurnEvent
    data class TurnCompleted(val assistantText: String) : AgentTurnEvent
    data class TurnFailed(val message: String, val retryable: Boolean) : AgentTurnEvent
    data object Cancelled : AgentTurnEvent
}
```

The exact names may differ, but the contract should preserve these properties:

- interactive turns can emit deltas
- scheduled/background turns can still use the batch path
- terminal success still yields a normal persisted assistant message
- terminal failure/cancel does not leave the DB in a half-finished state

#### Implementation steps

1. Extend `ModelProvider` with capabilities and a default streaming path.
2. Add the agent-level event model in the orchestrator package.
3. Keep `AgentRunner.runInteractiveTurn()` intact for compatibility.
4. Add a new streaming-oriented interactive entry point, for example:
   - `fun runInteractiveTurnStream(request: AgentTurnRequest): Flow<AgentTurnEvent>`
5. Ensure the streaming entry point still reuses the same session-lane coordination.
6. Ensure cancellation of the collecting coroutine cancels the underlying network call.
7. Keep scheduled flows on the non-streaming path for now.

#### Acceptance criteria

- existing providers still compile with minimal change
- interactive chat can collect deltas from the new path
- existing non-streaming tests remain valid
- cancellation propagates cleanly without wedging the lane

#### Validation

- all current unit tests remain green
- new unit tests for:
  - default `streamGenerate()` fallback
  - cancellation propagation
  - lane cleanup after a cancelled stream

---

### WS2 — Add OpenAI-compatible SSE streaming on the existing provider path

#### Why this workstream exists

AndroidClaw already has an OpenAI-compatible provider path. The fastest, most compatible improvement is to make that path stream rather than waiting for the whole response.

#### Key decision

Stay on the **Chat Completions-compatible** path for now rather than migrating the whole app to a different provider API.

Reason:

- many OpenAI-compatible vendors implement Chat Completions semantics first
- the current provider is already built around it
- OpenAI still documents streaming with `stream: true`
- a migration to a different API surface would multiply risk without fixing the immediate UX gap [R13]

#### Goals

- support `stream: true` on the OpenAI-compatible provider
- surface assistant text incrementally
- correctly accumulate streamed tool-call fragments when present
- fall back safely when an endpoint does not support streaming cleanly

#### Implementation steps

1. Add the `okhttp-sse` dependency aligned to the existing OkHttp version.
2. Extend the request payload model to support `stream = true` when using the streaming path.
3. Implement SSE parsing with OkHttp `EventSource`.
4. Ignore keepalive / non-data noise safely.
5. Handle the normal `[DONE]` sentinel.
6. Build a small chunk aggregator that can reconstruct:
   - assistant text
   - tool call IDs
   - tool names
   - tool argument JSON fragments
7. Only emit `Completed(ModelResponse)` when the full assistant response/tool-call set is coherent.
8. If streaming fails due to:
   - HTTP 400/404/501 on `stream: true`
   - malformed SSE
   - obviously unsupported endpoint behavior
   then either:
   - surface a retryable provider error, or
   - explicitly fall back to `generate()` if the failure is clearly a “streaming unsupported” class
9. Keep timeouts and failure mapping consistent with the current provider behavior.

#### Edge cases to handle explicitly

- text-only deltas
- mixed text + tool-call deltas
- tool-call arguments split across many fragments
- multiple tool calls in one response
- empty initial content blocks
- unknown fields in chunks
- malformed chunk JSON
- cancellation while streaming

#### Acceptance criteria

- text appears incrementally in the chat UI via the new agent streaming path
- tool-use responses still produce the same final `ModelResponse` semantics as the batch path
- endpoints that do not support streaming do not wedge the UI or orphan the lane

#### Validation

Add JVM tests using `MockWebServer` for:

- plain text SSE stream
- streamed tool-call arguments across multiple chunks
- unknown/ignored event payloads
- malformed JSON chunk -> structured provider failure
- unsupported-stream path -> safe fallback or explicit failure
- cancellation during stream

Fast loop:

- `./gradlew :app:testDebugUnitTest`
- `./gradlew :app:assembleDebug`
- `./gradlew :app:lintDebug`

---

### WS3 — Upgrade the chat turn UX: streaming view, cancel, retry, and clearer errors

#### Why this workstream exists

Streaming without UI support still feels incomplete. The current chat screen mostly shows a spinner and a static error card.

#### Goals

- render partial assistant text while a turn is running
- allow the user to cancel a turn cleanly
- give the user a one-tap retry path after a provider failure
- expose clearer turn states without cluttering the UI

#### UX rules

1. **While a turn is running:**
   - show partial assistant text inline or as a transient draft bubble
   - keep send disabled
   - show a cancel action

2. **On success:**
   - replace the transient draft bubble with the persisted assistant message
   - clear transient error state

3. **On failure:**
   - keep the user’s last message visible
   - do not persist a half-finished assistant message
   - show a retry action
   - preserve the human-readable error summary

4. **On cancel:**
   - stop network/tool progression
   - clear transient streaming state
   - do not persist a partial assistant message
   - show a lightweight cancellation notice, not a scary error

#### Recommended implementation shape

Extend `ChatUiState` with fields like:

- `streamingAssistantText: String = ""`
- `lastFailedUserMessageId: String? = null`
- `canRetryLastFailedTurn: Boolean = false`
- `isCancelling: Boolean = false`
- `activeTurnStage: String? = null`

Add `ChatViewModel` methods:

- `sendCurrentDraft()` -> now collects agent events
- `cancelActiveTurn()`
- `retryLastFailedTurn()`

#### Retry design decision

Keep retry lightweight.

Recommended first implementation:

- retry reuses the latest failed user message already stored in the session
- do **not** duplicate that user message in the transcript on retry
- record the retry attempt in event logs, not as an extra chat message

Reason:

- avoids schema churn
- avoids transcript duplication
- preserves clear session history

If this proves confusing later, a future plan can switch to explicit “retried turn” transcript semantics.

#### Error model improvements

Map provider failures into a few user-facing classes:

- configuration
- authentication
- network
- timeout
- malformed provider response
- unknown/runtime

Use that classification to decide whether `retryable = true`.

#### Acceptance criteria

- users see partial text during a real-provider stream
- users can cancel a run without leaving the UI stuck in `isRunning = true`
- users can retry the last failed provider turn without retyping
- the failure card is more actionable than a raw static message

#### Validation

JVM tests:

- `ChatViewModel` streaming success
- `ChatViewModel` failure -> retry availability
- `ChatViewModel` cancel path
- no stale streaming text after success/failure/cancel

Compose/instrumentation tests:

- send message -> transient streaming state visible
- cancel button visible only during active turn
- retry button visible after failure

---

### WS4 — Replace the hardcoded recent-message cap with a budgeted context selector

#### Why this workstream exists

`AgentRunner` currently uses a fixed recent-message count. That is better than unbounded replay, but it is too crude for real usage.

#### Goals

- stop using a fixed message-count slice as the only context strategy
- preserve the most important context under a bounded budget
- avoid adding a heavy tokenizer dependency in the first pass

#### Design principles

1. Use a **budgeted selector**, not a blind message-count selector.
2. Prefer a conservative budget estimate over false token precision.
3. Preserve conversation structure that tools need.
4. Preserve system/skill/tool context anchors.
5. Keep the selector deterministic and testable.

#### First-pass recommended design

Introduce a `ContextWindowManager` or similar with inputs:

- system prompt size estimate
- active skill instructions size estimate
- tool descriptor size estimate
- persisted session summary if present
- full recent message history (not already trimmed to 32)
- target budget for this provider/profile

And outputs:

- selected message history in order
- whether truncation occurred
- whether a summary was inserted
- selection diagnostics for tests/health

#### Selection policy

Recommended first-pass policy:

1. Always reserve room for:
   - system prompt
   - selected skill instructions
   - tool descriptors

2. Always keep the latest user message.

3. Always keep the most recent assistant/user exchange window.

4. Keep tool-call dependency closure:
   - if an assistant tool-call message is kept, keep the corresponding tool result(s)
   - if a tool result is kept, keep the originating assistant tool-call message

5. If `summaryText` exists and older history would otherwise be dropped, inject the summary before the selected recent window.

6. Drop oldest low-value history first once budget is exceeded.

#### Budgeting method

Do **not** claim exact token counting in v6 first pass.

Use a conservative “prompt units” estimator, for example based on:

- character count
- per-message fixed overhead
- extra cost for tool descriptors and structured content
- a safety reserve for provider/system overhead

This should be explicit in code and docs as an approximation.

#### Provider-specific defaults

Add a lightweight config or constant table such as:

- fake/default: generous test budget
- OpenAI-compatible: default prompt-unit budget tuned for the expected model class
- Anthropic: its own default once provider is added

No UI for per-provider context budgets is needed in v6.

#### Acceptance criteria

- `AgentRunner` no longer hardcodes `limit = 32` as the governing strategy
- the selector preserves the latest relevant turns and tool closure
- selected history is deterministic and unit-tested

#### Validation

Add JVM tests for:

- short session -> no truncation
- long session -> truncation occurs deterministically
- tool-call pair closure preserved
- summary inserted when older history is dropped
- latest user turn never dropped
- system/tool/skill overhead reduces available message budget as expected

---

### WS5 — Introduce session-summary foundations without overcomplicating v6

#### Why this workstream exists

The schema already has `summaryText`, but it is not yet part of the working context system. Long sessions need a summary path eventually, but summary work can easily balloon in scope.

#### v6 rule

Do **not** block v0.2.0 on perfect automatic summaries.

Instead:

- first ship the budgeted selector from WS4
- then add a simple, safe summary foundation that can improve later

#### Goals

- begin using `summaryText` as a real part of context assembly
- add a low-risk path for updating summaries
- keep summary generation best-effort and failure-tolerant

#### Recommended implementation shape

Create a small `ConversationSummaryManager` with two responsibilities:

1. decide **when** a summary is stale or missing
2. decide **how** to write/update `summaryText`

The manager should support two strategies:

- `NoOpSummaryWriter` / deterministic heuristic writer for tests and `FakeProvider`
- provider-backed writer for future real-summary generation

#### First-pass summary strategy

Recommended first implementation:

- if session history exceeds a threshold and no summary exists, allow a summary refresh after a successful turn
- summary refresh is best-effort and never blocks UI completion
- summary refresh failure only logs an event; it does not fail the chat turn

For v6 first pass, either of these is acceptable:

1. **Heuristic summary writer only**
   - deterministic compressed recap of older messages/tool results
   - enough to support context insertion and tests

2. **Heuristic now + provider-backed optional enhancement later**
   - if a real provider is configured and the app is idle after a turn, a short background summary refresh may be attempted

If time is tight, ship option 1 first.

#### Summary content rules

A summary should be:

- short
- factual
- bounded in size
- explicitly about prior context, not a replacement for recent turns

Suggested structure:

- session goal / topic
- stable facts/preferences found so far
- unresolved tasks/questions
- important tool-derived facts

#### Acceptance criteria

- `summaryText` can be inserted into context assembly when old history is pruned
- there is a defined path to create/update `summaryText`
- summary generation failure cannot wedge chat

#### Validation

JVM tests:

- summary inserted only when relevant
- summary update threshold logic
- summary refresh failure does not fail the turn
- deterministic heuristic summary output for test fixtures

---

### WS6 — Expand the test surface around the real user path

#### Why this workstream exists

The repo already has strong unit coverage for many internals, but the next risk is the **interactive path**:

- streaming
- cancel/retry
- provider/tool loop continuity
- context-window selection

#### Goals

- cover the new interactive runtime path deeply with JVM tests
- add a small, durable set of Compose/device tests for chat UX
- avoid bloating the repo with fragile UI suites

#### Testing strategy

1. **JVM-first for runtime logic**
   - stream parsing
   - delta aggregation
   - cancellation semantics
   - retry state
   - context budget selection
   - summary manager

2. **Small Compose test layer for user-visible state**
   - one or two chat-screen scenarios
   - no large screenshot matrix
   - no animation-heavy behavior dependence

3. **One scripted end-to-end runtime test**
   - user message
   - model requests tool
   - tool executes
   - model returns final text
   - session messages persist in correct order

#### Recommended new test doubles

Add small scripted test doubles instead of abusing the existing fake provider:

- `ScriptedProvider`
- `ScriptedStreamingProvider`

They should let tests specify exact events/responses, including tool calls and failures.

#### Acceptance criteria

- there is at least one end-to-end scripted runtime test that covers tool use on the happy path
- there are explicit tests for streaming aggregation and cancellation
- there is at least one Compose/instrumentation test for the chat streaming/retry surface

#### Validation

Required:

- `./gradlew :app:testDebugUnitTest`
- `./gradlew :app:assembleDebugAndroidTest`

Recommended targeted device run:

- `./scripts/run_windows_android_test.sh --avd AndroidClawApi34 --test-class <new-chat-test-class>`

---

### WS7 — Migrate Room from `kapt` to `ksp` and pay down legibility debt

#### Why this workstream exists

`kapt` is maintenance-mode technology and adds avoidable build overhead. This is a good time to pay down that debt while the codebase is still small. [R11][R12]

#### Goals

- migrate Room annotation processing from `kapt` to `ksp`
- preserve schema export and migration tests
- make the codebase easier to navigate without large architecture churn

#### Implementation steps

1. Add the KSP plugin to the version catalog/build.
2. Replace the `kapt` plugin with `ksp` in the app module.
3. Replace `kapt(libs.androidx.room.compiler)` with `ksp(...)`.
4. Move Room schema-location arguments to KSP configuration.
5. Rebuild and confirm generated sources, schema export, and migration tests still work.
6. Update any docs or comments that still reference `kapt`.

#### Legibility follow-ups in the same workstream

Without changing runtime behavior:

- split `BuiltInTools.kt` into small files by domain:
  - `TaskTools.kt`
  - `HealthTools.kt`
  - `SessionTools.kt`
  - `SkillTools.kt`
  - `NotificationTools.kt`
- optionally split parts of `AppContainer` into tiny helper factories if readability clearly improves

Do **not** adopt Hilt/Koin here.

#### Optional low-risk developer ergonomics

If this can be done without slowing the repo down:

- add a lightweight formatter step (for example `ktlint` or `ktfmt`) only if it is easy to keep deterministic in CI

This is optional. Do not let style tooling delay the main workstreams.

#### Acceptance criteria

- `kapt` is removed from the production build
- Room still exports schemas
- migration tests still pass
- tool implementation files are easier to navigate

#### Validation

- `./gradlew :app:assembleDebug`
- `./gradlew :app:testDebugUnitTest`
- `./gradlew :app:assembleDebugAndroidTest`
- `./gradlew :app:lintDebug`

---

### WS8 — Add a native Anthropic provider after the streaming contract is stable

#### Why this workstream exists

OpenAI-compatible is a useful first real-provider path, but it is too narrow as the only real backend. Anthropic is the highest-value second provider family because it is common, tool-aware, and directly aligned with the ecosystem AndroidClaw draws inspiration from.

#### Key decision

Add a **native Anthropic provider**, not just “tell users to use an OpenAI-compatible shim.”

Reason:

- Anthropic’s native Messages API has its own message and tool-use semantics
- streaming/tool-use behavior is documented distinctly
- native support gives better compatibility and clearer future extension [R14]

#### Goals

- add `ProviderType.Anthropic`
- support non-streaming and streaming Anthropic messages
- support Anthropic tool-use semantics
- integrate it into Settings and secret storage with minimal surface growth

#### First implementation scope

In scope:

- text messages
- system prompt
- tool definitions
- tool-use requests
- text streaming
- tool-use streaming aggregation
- API key + model + timeout + base URL if needed

Out of scope for first Anthropic pass:

- vision/files
- extended thinking
- prompt caching
- PDFs
- MCP

#### Implementation steps

1. Extend `ProviderType` and settings models.
2. Add Anthropic API key storage entry in the existing provider secret store.
3. Add Settings UI fields needed for Anthropic.
4. Implement request/response models for the Anthropic Messages API.
5. Map AndroidClaw’s `ModelRequest` / `ModelResponse` to Anthropic semantics.
6. Implement streaming event parsing and aggregation.
7. Handle tool-use stop reasons and content-block event ordering correctly.
8. Add MockWebServer tests for non-streaming and streaming Anthropic paths.

#### Acceptance criteria

- Anthropic can be selected in Settings
- a configured Anthropic provider can complete a normal text turn
- Anthropic tool-use can drive the existing tool runtime
- Anthropic streaming works on the chat screen

#### Validation

- new provider-specific JVM tests
- targeted manual QA with a real Anthropic key if available
- fast loop remains green

---

### WS9 — Add export/share and basic search

#### Why this workstream exists

Once the chat experience is responsive, the next highest-value user utilities are:

- exporting what happened
- finding prior content

These are both practical, bounded features that do not threaten the lightweight goal.

#### Goals

- export/share session history in a lightweight format
- search sessions/messages/tasks in a simple first-pass way

#### Export scope

First pass should support:

- Markdown export for a session
- JSON export for a session
- Android sharesheet integration via a temporary cache file + `FileProvider`

Do **not** block this work on PDF export.

#### Search scope

First pass should support:

- search sessions by title
- search messages by content snippet
- search tasks by title/description

Keep the UI simple. Possible first-pass surfaces:

- search box on Chat session list
- search box on Tasks screen
- optional global search later

#### Implementation notes

- keep DB queries bounded and indexed where reasonable
- avoid FTS in the first pass
- surface match snippets if cheap; otherwise just show matching records

#### Acceptance criteria

- the current session can be exported as Markdown and JSON
- the user can share the exported file via Android sharesheet
- basic search exists for at least sessions and tasks, preferably messages too

#### Validation

- JVM tests for export formatting and repository search queries
- instrumentation/robolectric tests for `FileProvider` share path if practical
- manual QA on device/emulator for sharing

---

### WS10 — Lightweight release hygiene: network posture, backup hygiene, startup/perf evidence, and distribution readiness

#### Why this workstream exists

The app is already small and installable, but a few low-cost hygiene issues should be fixed before broader beta use.

This is **not** a full security program.

#### Goals

- fix secret-backup hygiene
- add explicit network posture
- improve release/distribution readiness
- keep size/startup evidence current

#### WS10A — Backup hygiene for secret stores

Current issue:

- backup rules include all shared preferences
- provider/skill secrets are stored in encrypted shared preferences backed by Android Keystore semantics
- restored secret blobs may become undecryptable on a new device/key context [R18][R19][R20]

Implementation:

- exclude the secret preference files from both `backup_rules.xml` and `data_extraction_rules.xml`
- likely exclude:
  - `androidclaw_provider_secrets.xml`
  - `androidclaw_skill_secrets.xml`
- keep ordinary settings backed up if desired

Acceptance:

- secret stores are excluded from cloud backup and device-transfer backup
- settings backup still works for non-secret settings unless intentionally changed

#### WS10B — Explicit network posture

Current issue:

- there is no `network_security_config.xml`

Recommended low-cost posture:

- add an explicit network security config
- keep production default conservative
- do not break the existing compatibility story accidentally

Because AndroidClaw supports OpenAI-compatible endpoints, some users may point it at local/self-hosted services. Therefore the first explicit posture should be careful, not dogmatic.

Recommended approach:

- document the intended default as HTTPS-first
- if cleartext local development needs to remain possible, scope it intentionally rather than implicitly
- if a compatibility-preserving exception is needed later, make it an explicit product decision with docs/tests

Do not spend this phase on certificate pinning or large network-security machinery.

#### WS10C — Startup/performance evidence refresh

Current position:

- Baseline Profiles are still deferred in docs
- the app is already very small
- there is no fresh startup evidence tied to the new streaming/chat work

Implementation:

- refresh `docs/PERFORMANCE.md` after the streaming/context changes
- record updated shrunk artifact sizes
- optionally measure startup and first-chat-open timings using existing Android guidance [R10][R16]
- if Baseline Profile collection is now environment-feasible, create a small child plan and implement it
- if not feasible, keep the deferral explicit again with fresh evidence

#### WS10D — Distribution readiness

Implementation:

- make sure `bundleRelease` remains green
- document the intended signing path
- document whether the near-term beta route is:
  - direct APK side-load
  - Play internal testing
  - internal app sharing
- update `docs/BETA_HANDOFF.md` and `docs/RELEASE_CHECKLIST.md`

#### Acceptance criteria

- secret preference files are excluded from backup
- network posture is explicit rather than accidental
- release docs accurately describe the intended distribution route
- performance docs reflect the post-v6 state

#### Validation

- `./gradlew :app:assembleQa :app:assembleRelease :app:bundleRelease`
- doc inspection
- artifact size refresh

---

### WS11 — Beta handoff for the new responsive-chat baseline

#### Why this workstream exists

After the code and docs change, the repo should produce a crisp tester-facing packet. Otherwise the project remains “technically better” but operationally vague.

#### Goals

- create a truthful beta packet for the post-v6 baseline
- define exactly what external testers should validate
- record evidence for the new user-critical behavior

#### Beta packet should include

- updated `README.md`
- updated `docs/BETA_HANDOFF.md`
- updated `docs/KNOWN_LIMITATIONS.md`
- updated `docs/qa/beta-validation.md`
- at least one screenshot or short text evidence of streaming/cancel/retry behavior if practical

#### Manual QA matrix for the beta packet

At minimum:

1. FakeProvider basic chat
2. OpenAI-compatible streaming chat
3. OpenAI-compatible provider failure -> retry
4. cancel active stream
5. long session still behaves plausibly under budgeted context selection
6. task create/run_now / health visibility still work
7. skill import/enable/config still work after `ksp` migration and minification

#### Acceptance criteria

- the beta handoff doc describes the real current build and validation truth
- the known-limitations doc is updated for any remaining streaming/provider/context caveats
- the release checklist reflects the new build/distribution state

---

## 11. Recommended execution packets for Codex

The workstreams above are large. Codex should usually attack them in smaller packets.

### Packet A — plan adoption and repo truth

- adopt `PLANv6.md`
- repoint `AGENTS.md` and `README.md`
- add short supersession note to `PLANv5.md`

### Packet B — provider streaming SPI

- add `ProviderCapabilities`
- add default `streamGenerate()`
- add `AgentTurnEvent`
- keep existing batch path intact

### Packet C — OpenAI-compatible SSE path

- add `okhttp-sse`
- implement SSE stream parsing
- implement chunk aggregation
- add MockWebServer tests

### Packet D — chat UI/runtime streaming integration

- wire `ChatViewModel` to streaming events
- add transient assistant text
- add cancel action
- add retry action
- add tests

### Packet E — context budgeting

- create `ContextWindowManager`
- replace hardcoded recent-message slice
- add tests

### Packet F — summary foundation

- create summary manager/writer
- make `summaryText` usable in context assembly
- add heuristic implementation/tests

### Packet G — runtime E2E and Compose tests

- add scripted provider test doubles
- add one end-to-end tool-loop test
- add focused chat UI tests

### Packet H — KSP and legibility

- migrate Room to KSP
- split `BuiltInTools.kt`
- keep schema export and migration tests green

### Packet I — native Anthropic provider

- add settings/secrets
- add Messages API support
- add streaming/tool-use mapping
- add tests

### Packet J — export/share and search

- markdown/json export
- sharesheet path
- basic search repositories + UI

### Packet K — release hygiene and beta handoff

- backup exclusions
- explicit network posture
- performance doc refresh
- release/beta doc refresh

This packetization is important. It lets Codex make progress without carrying too much context in one change set.

---

## 12. Validation matrix

### 12.1 Always-required fast loop

These must remain green after each material work packet:

```bash
./gradlew :app:assembleDebug
./gradlew :app:testDebugUnitTest
./gradlew :app:lintDebug
```

### 12.2 Packaging-required loop

Run after any build, shrinker, manifest, resource, or release-doc change:

```bash
./gradlew :app:assembleQa
./gradlew :app:assembleRelease
./gradlew :app:bundleRelease
./gradlew :app:assembleDebugAndroidTest
```

### 12.3 Device/emulator loop

Run when chat UI behavior, startup, scheduler integration, or export/share behavior changes materially:

```bash
ANDROIDCLAW_JAVA_HOME=/path/to/jdk17 ./scripts/run_windows_android_test.sh --avd AndroidClawApi34 --test-class ai.androidclaw.app.MainActivitySmokeTest
```

Add targeted classes for new chat-related instrumentation once they exist.

### 12.4 Exact-alarm regression loop

This is no longer the main blocker, but should still run if scheduler/exact-alarm code changes:

```bash
ANDROIDCLAW_JAVA_HOME=/path/to/jdk17 ./scripts/run_exact_alarm_regression.sh --api34-avd AndroidClawApi34 --api31-avd AndroidClawApi31
```

### 12.5 Real-provider manual QA loop

This is required when streaming or provider changes land:

- OpenAI-compatible provider configured with a real endpoint
- send a medium-length message and verify visible streaming
- cancel a longer run
- intentionally break endpoint or key and verify retryable error UX
- if Anthropic provider exists, repeat for Anthropic

### 12.6 Shrunk-build smoke

After changes to serialization, YAML parsing, providers, export/share, or network config:

```bash
ANDROIDCLAW_JAVA_HOME=/path/to/jdk17 ./scripts/run_windows_android_test.sh --variant qa --launch-smoke --avd AndroidClawApi34 --launch-component ai.androidclaw.app/.MainActivity
```

This remains important because minification and resource shrinking are part of the product story.

---

## 13. Definition of done by horizon

### 13.1 v0.2.0 done means

All of the following are true:

- OpenAI-compatible provider can stream visibly in chat
- chat shows partial assistant output during generation
- user can cancel active generation
- user can retry a failed provider turn
- context selection is budgeted, not fixed-count only
- fast loop is green
- packaging loop is green
- docs point to `PLANv6.md`
- known-limitations doc is updated

### 13.2 v0.3.0 done means

All of the following are true:

- session summary foundations exist and are used
- native Anthropic provider exists
- export/share exists
- basic search exists
- Room uses KSP instead of kapt
- release hygiene items are closed or explicitly deferred with fresh rationale

### 13.3 v0.4.0 done means

All of the following are true:

- signing/distribution route is formalized
- beta handoff is crisp and repeatable
- performance evidence is updated
- optional Baseline Profile story is either working or honestly deferred

---

## 14. Human blockers and assumptions

These are the main likely non-code blockers.

### 14.1 Provider credentials

Real-provider manual QA requires valid provider credentials and reachable endpoints.

### 14.2 Distribution credentials

Broader beta work may require:

- signing-key decisions
- Play Console access
- internal testing setup

### 14.3 Environment differences for performance lanes

If Baseline Profile or benchmark work is attempted again, the environment must actually support artifact resolution and emulator/device execution cleanly.

### 14.4 Self-hosted compatibility choices

If users rely on non-HTTPS local OpenAI-compatible endpoints, explicit network posture work may require a product decision rather than a purely technical one.

---

## 15. Risk register

### Risk 1 — Streaming refactor breaks stable batch behavior

Mitigation:

- keep `generate()` intact
- add streaming as an additive path
- test both streaming and non-streaming routes

### Risk 2 — Tool-call streaming aggregation is buggy

Mitigation:

- isolate aggregation logic in a small testable helper
- add fragmented-JSON fixture tests
- keep final-response validation strict

### Risk 3 — Cancellation leaves the app wedged

Mitigation:

- cancellation tests at provider, agent-runner, and view-model layers
- ensure lane cleanup in `finally`

### Risk 4 — Budgeted context selector drops important content

Mitigation:

- explicit selection-policy tests
- keep latest turn + tool-closure invariant non-negotiable
- add session-summary hook

### Risk 5 — Summary work balloons in scope

Mitigation:

- do WS4 before WS5
- allow heuristic summary first
- keep summary refresh best-effort and non-blocking

### Risk 6 — KSP migration breaks schema export or tests

Mitigation:

- do migration as a bounded standalone packet
- run migration/instrumentation checks immediately after

### Risk 7 — Anthropic provider expands surface too much

Mitigation:

- do it only after streaming contract is stable
- keep first implementation text+tools only

### Risk 8 — Network/backup hygiene accidentally hurts compatibility

Mitigation:

- keep changes explicit and documented
- favor narrow exclusions and clearly justified network defaults
- do not over-harden blindly

### Risk 9 — UI tests become flaky

Mitigation:

- keep the Compose suite very small
- use deterministic scripted providers
- avoid screenshot/golden suites in v6 core path

---

## 16. What should happen first, in order

This is the literal recommended order for the next work.

1. **Adopt `PLANv6.md` and repoint docs.**
2. **Add the provider/agent streaming contracts.**
3. **Implement OpenAI-compatible streaming with tests.**
4. **Wire chat UI for streaming, cancel, retry, and clearer failure states.**
5. **Replace the hardcoded message-count context slice with a budgeted selector.**
6. **Add summary foundations.**
7. **Expand runtime/Compose tests around the new chat path.**
8. **Migrate Room from kapt to KSP and split oversized files.**
9. **Add native Anthropic provider.**
10. **Add export/share and basic search.**
11. **Close low-cost release hygiene items and refresh beta docs.**

If schedule pressure appears, do **not** cut WS1–WS4. Those are the heart of the plan.

If anything must slip, let it be:

- Baseline Profiles
- style tooling
- richer export formats
- broader search
- additional providers beyond Anthropic

---

## 17. Progress log seed

Use this section as the running execution ledger after `PLANv6.md` is adopted.

### Completed before `PLANv6`

- Single-module Kotlin-first app established.
- Compose screens for chat, tasks, skills, settings, and health established.
- FakeProvider path established.
- OpenAI-compatible provider path established.
- Scheduler with `once` / `interval` / `cron` established.
- Exact-alarm degradation and diagnostics established.
- Bundled / local / workspace skill loading established.
- Task tools expanded beyond listing-only.
- Tool execution context introduced.
- Runtime/tool failure hardening landed.
- Installable shrunk `qa` lane landed.
- `release` shrinking landed.
- Windows AVD repo migration landed.
- Exact-alarm regression evidence closed in `docs/qa/`.

### Completed in `PLANv6`

- 2026-03-16: `WS0` adopted `PLANv6.md` as canonical, repointed top-level docs, and marked `PLANv5.md` historical.
- 2026-03-16: `WS1` landed the streaming-capable provider/runtime contract, interactive streaming turn path, and cancellation/lane cleanup coverage.

### Next expected check-ins

- `PLANv6.md` adoption
- streaming SPI landed
- OpenAI-compatible SSE landed
- chat streaming UX landed
- budgeted context selector landed
- KSP migration landed
- Anthropic provider landed
- beta handoff refreshed

---

## 18. Discoveries log seed

Record new facts here when they materially change implementation.

- The supplied review is partially stale relative to the newest repo snapshot.
- Exact-alarm regression evidence is already closed in the latest `docs/qa/`.
- The repo already uses AndroidKeystore-backed AES/GCM for secrets.
- Secret preference backup exclusion is still missing even though secrets are encrypted.
- `AgentRunner` still uses a hardcoded recent-message count and therefore still needs context work.
- `OpenAiCompatibleProvider` is still whole-response only and therefore still needs streaming.
- Linux-side Gradle validation is currently blocked in this harness because the sandbox denies `java.net.NetworkInterface.getNetworkInterfaces()`, so WSL fast-loop verification needs either a relaxed harness or an external Windows/device lane.
- This WSL runtime currently denies Java socket creation for `NetworkInterface` enumeration, so local Gradle startup can fail with the wildcard-IP error; GitHub Actions is the fallback validation lane when that host restriction is active.
- This shell currently cannot open outbound sockets to GitHub either, so `git push` / `gh` validation handoff can be temporarily blocked even when repo work is ready; local milestone commits should stay segmented so they can be pushed in order once connectivity returns.

---

## 19. Decision log seed

Record intentional trade-offs here.

1. **Keep AndroidClaw Kotlin-first and Android-native.**
   No Node/Docker/Chromium transplant.

2. **Keep Chat Completions compatibility on the OpenAI-compatible provider path for now.**
   Streaming is added on top of the existing compatible path rather than by wholesale API migration.

3. **Add streaming as an additive provider capability, not a breaking rewrite.**

4. **Keep partial assistant streaming text ephemeral until final completion.**
   Do not persist every chunk.

5. **Improve context with a budgeted selector before adding a heavy tokenizer.**

6. **Use summary foundations without blocking v0.2.0 on perfect automatic summarization.**

7. **When the host blocks local Gradle startup, use GitHub Actions as the truthful validation lane rather than pretending local verification happened.**

8. **If outbound GitHub connectivity is blocked, keep milestone work in ordered local commits rather than collapsing multiple workstreams into one opaque diff.**

7. **Keep manual DI during v6.**
   Refactor for readability if needed, but do not adopt Hilt/Koin in this phase.

8. **Treat security as low-cost hygiene in v6, not as the main project.**

---

## 20. References

These references informed the plan and are intentionally a mix of official docs and a small number of research papers.

- **[R1]** OpenAI — *Harness engineering: leveraging Codex in an agent-first world*  
  https://openai.com/index/harness-engineering/

- **[R2]** OpenClaw docs — *Android platform / companion node positioning*  
  https://docs.openclaw.ai/platforms/android

- **[R3]** OpenClaw docs — *Skills*  
  https://docs.openclaw.ai/tools/skills

- **[R4]** OpenClaw docs — *Tools*  
  https://docs.openclaw.ai/tools

- **[R5]** OpenClaw docs — *Cron / scheduled jobs*  
  https://docs.openclaw.ai/tools/cron

- **[R6]** NanoClaw official repository / docs  
  https://github.com/qwibitai/nanoclaw  
  https://nanoclaw.net/#docs

- **[R7]** Android Developers — *Persistent work with WorkManager*  
  https://developer.android.com/develop/background-work/background-tasks/persistent

- **[R8]** Android Developers — *Schedule exact alarms*  
  https://developer.android.com/develop/background-work/services/alarms/schedule

- **[R9]** Android Developers — *App Standby Buckets*  
  https://developer.android.com/topic/performance/appstandby

- **[R10]** Android Developers — *Baseline Profiles overview*  
  https://developer.android.com/topic/performance/baselineprofiles/overview

- **[R11]** Kotlin docs — *kapt in maintenance mode / KSP recommendation*  
  https://kotlinlang.org/docs/kapt.html

- **[R12]** Android Developers / Room docs and release notes  
  https://developer.android.com/training/data-storage/room  
  https://developer.android.com/jetpack/androidx/releases/room

- **[R13]** OpenAI API docs — *Streaming responses / Chat Completions streaming support*  
  https://platform.openai.com/docs/guides/streaming-responses  
  https://platform.openai.com/docs/api-reference/chat/create

- **[R14]** Anthropic docs — *Messages API, streaming, and tool use*  
  https://platform.claude.com/docs/api/messages  
  https://platform.claude.com/docs/build-with-claude/streaming  
  https://platform.claude.com/docs/agents-and-tools/tool-use/overview

- **[R15]** OkHttp docs — *okhttp-sse / EventSource*  
  https://square.github.io/okhttp/5.x/okhttp-sse/index.html  
  https://square.github.io/okhttp/5.x/okhttp-sse/okhttp3.sse/-event-source/index.html

- **[R16]** Android Developers — *App startup time / Android vitals*  
  https://developer.android.com/topic/performance/vitals/launch-time

- **[R17]** Android Developers — *Compose testing*  
  https://developer.android.com/develop/ui/compose/testing  
  https://developer.android.com/develop/ui/compose/testing/synchronization

- **[R18]** Android Developers — *Back up user data with Auto Backup*  
  https://developer.android.com/identity/data/autobackup

- **[R19]** Android Developers — *EncryptedSharedPreferences*  
  https://developer.android.com/reference/androidx/security/crypto/EncryptedSharedPreferences

- **[R20]** Android Developers — *Security recommendations for backups*  
  https://developer.android.com/privacy-and-security/risks/backup-best-practices

- **[R21]** Android Developers / Play — *Android App Bundles*  
  https://developer.android.com/guide/app-bundle

- **[R22]** Play Console Help — *Play App Signing*  
  https://support.google.com/googleplay/android-developer/answer/9842756

- **[R23]** Play Console Help — *Internal testing / internal app sharing*  
  https://support.google.com/googleplay/android-developer/answer/9845334  
  https://support.google.com/googleplay/android-developer/answer/9303479

- **[R24]** Shinde et al. — *Extractive Summarization of Long Documents by Combining Global and Local Context*  
  https://aclanthology.org/2024.findings-naacl.93/

- **[R25]** Park et al. — *Recursively summarizing enables long-term dialogue memory in large language models*  
  https://www.sciencedirect.com/science/article/pii/S2666651025000085

- **[R26]** Seow, S. C. — *Designing and Engineering Time: The Psychology of Time Perception in Software* / response-time literature overview  
  https://www.sciencedirect.com/science/article/pii/S1071581908000519

---

## 21. Final note

If the project must choose only three things to do next, they should be:

1. streaming on the existing OpenAI-compatible path
2. chat UX for cancel/retry and visible progress
3. budgeted context assembly

Those three changes would do more to improve real-user experience than a much longer list of secondary features.
