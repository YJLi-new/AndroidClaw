# AGENTS.md

## Mission

Build **AndroidClaw**: a lightweight Android-native assistant app inspired by NanoClaw and OpenClaw.

The compatibility goal is **behavioral compatibility** with the core runtime concepts that matter on phone: sessions, tools, skills, and scheduled automations. It is **not** source compatibility with the current desktop Node.js / Docker runtime.

The first release optimizes for these outcomes, in this order:

1. One installable APK that works after install.
2. Low memory use, fast startup, and smooth UI on ordinary Android phones.
3. OpenClaw-like runtime semantics for sessions, skills, and automations.
4. Simple Kotlin code and minimal dependencies.
5. A small, understandable GUI.
6. Security hardening later.

## Read this first

1. Read `PLANv3.md` before making any non-trivial change.
2. Treat `PLANv3.md` as the current source of truth for architecture, milestones, acceptance criteria, and trade-offs.
3. If the task changes scope or invalidates the plan, update `PLANv3.md` first, then code.
4. Keep `PLANv3.md` current while you work. It is a living document, not a static design note.

## Working model

Humans steer. Codex executes.

Work in this loop:

1. Read the plan.
2. Pick the next small milestone or sub-step.
3. Make the smallest diff that completes that step.
4. Run validation immediately.
5. Fix failures before moving on.
6. Update `PLANv3.md` with progress, discoveries, and decisions.

Do not ask for “next steps” if the plan already names them. Continue until the current milestone is done or you hit a real blocker.

## Hard constraints

These are non-negotiable for v0 unless `PLANv3.md` is explicitly changed.

- Do **not** embed Node.js, Docker, Chromium, or a desktop host runtime in the base app.
- Do **not** build AndroidClaw as a remote-first companion node. The phone app is the host in v0.
- Do **not** add large frameworks when the Android platform already provides the feature.
- Keep production code in **one Android app module** in v0. A test-only baseline profile module is allowed later if needed.
- Use **manual dependency wiring**. Do not introduce Hilt, Koin, or other DI frameworks.
- Prefer **Room**, **WorkManager**, **coroutines**, **kotlinx serialization**, and plain interfaces.
- Prefer **OkHttp + kotlinx serialization** over large vendor SDKs.
- Prefer typed native tools over shell execution.
- Do not spend time on sandboxing, encryption, auth, remote sync, or enterprise security hardening yet. Keep boundaries clean so those can be added later.

## Compatibility target

Implement these contracts in order:

1. **Session Contract**: main session, normal sessions, persistent history, lightweight summaries.
2. **Tool Contract**: typed tools with clear inputs, outputs, and capability metadata.
3. **Skill Contract**: `SKILL.md` parsing, frontmatter, enable/disable, import, and command dispatch.
4. **Automation Contract**: once / interval / cron scheduling with main-session and isolated execution modes.

If a requested change does not improve one of these contracts or the lightweight GUI around them, defer it.

## Product boundaries for v0

These are out of scope unless `PLANv3.md` explicitly adds them:

- Telegram, WhatsApp, Gmail, Discord, Slack, or other external channel integrations
- Browser automation, embedded Chromium, or heavy WebView-driven features
- Remote bridge mode or desktop gateway pairing
- Arbitrary shell access or host command execution
- Voice wake word, continuous audio, camera workflows, or screen recording workflows
- Multi-device sync and cloud account systems

## Architecture taste

Optimize for **agent legibility** and **runtime lightness**.

- Favor boring, stable, fully inspectable dependencies.
- Keep package layering explicit and shallow.
- Prefer one obvious path over many abstractions.
- Parse and validate data at boundaries.
- Keep files reasonably small and readable.
- Avoid reflection-heavy or codegen-heavy libraries unless clearly justified.
- Avoid always-on services and background polling loops.
- Make state durable through repository-local code and markdown, not chat memory.

## Expected repository shape

At minimum, the repo should converge toward:

- `AGENTS.md`
- `PLANv3.md`
- `app/`
- optional later: `baselineprofile/`
- optional later: `docs/exec-plans/active/`
- optional later: `docs/exec-plans/completed/`

Inside `app/`, prefer package-level layering instead of many Gradle modules. The expected package areas are:

- `app` for app wiring and activity entry points
- `ui` for theme, navigation, and shared Compose pieces
- `feature` for chat, tasks, skills, settings, and health screens
- `data` for Room entities, DAOs, and repositories
- `runtime` for orchestration, providers, tools, skills, sessions, and scheduler code

## Validation rules

Run the smallest relevant checks first, then the full fast suite before stopping.

From the repository root, the default validation commands are:

- `./gradlew :app:assembleDebug`
- `./gradlew :app:testDebugUnitTest`
- `./gradlew :app:lintDebug`
- `./gradlew :app:connectedDebugAndroidTest` when an emulator or device is available

If a baseline profile module exists and startup-critical code changed, also run the project’s baseline profile generation task.

Never declare success with red tests, red lint, or a broken build.

## Performance rules

The main product differentiator is that the app feels small and fast.

- No persistent foreground service while idle.
- No eager loading of all sessions, messages, tasks, or skills on startup.
- Use bounded logs and bounded in-memory caches.
- Prefer pagination, lazy lists, and incremental queries.
- Avoid unnecessary animations, blurred surfaces, and complex visual effects.
- Add Baseline Profiles once the main flows exist.
- When forced to choose, choose lower memory and simpler control flow over theoretical elegance.

## Testing strategy

Prefer deterministic local testing over flaky remote testing.

- Build a **FakeProvider** for debug builds and tests so chat, tool calling, and scheduling flows can be exercised without external API keys.
- Use JVM tests for parsers, schedulers, repositories, and tool contracts.
- Use instrumentation tests for Room integration, navigation smoke tests, and a minimal end-to-end UI path.
- Keep external network dependencies out of required tests.

## Documentation rules

`AGENTS.md` is the map, not the encyclopedia.

- Keep this file short and stable.
- Put active implementation detail in `PLANv3.md`.
- If work becomes too large for one plan, create a child plan under `docs/exec-plans/active/<slug>.md` and link it from `PLANv3.md`.
- Delete or update stale docs immediately. Outdated docs are bugs.

## Decision rules

When in doubt:

1. Preserve the single-APK, lightweight host vision.
2. Preserve the four compatibility contracts.
3. Prefer simpler Android-native code over desktop-port cleverness.
4. Prefer testable, additive changes over broad refactors.
5. Record the trade-off in `PLANv3.md`.
