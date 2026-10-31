# PLANv12 - Rework: lean runtime, phone-first UI, verified end to end

Status: active
Owner: main session (planning, merges, pushes) + Opus subagents (execution)
Started: 2026-10-30
Supersedes: `docs/past_plans/PLANv11.md`

## User request (verbatim, 2026-10-30)

> 把这个项目好好重做一下，功能都补全，bug都修了。推送到 https://github.com/YJLi-new/AndroidClaw

Interpretation recorded by the main session: rework the existing app in place
(keep the solid data / provider / scheduler core, rebuild what is bloated or
hostile to users), complete the phone-assistant feature set inside the AGENTS.md
boundaries, fix every bug that tests, lint, or device runs expose, then push.
Delivery is a `rework/v12` branch pushed to `origin` with a PR; merging into
`main` waits for the user's go-ahead.

## Diagnosis (survey 2026-10-30)

1. **Tool sprawl.** 168 registered tools; `runtime/tools` is ~35k of 53k main
   LOC. Up to 100 tool schemas (`MAX_PROMPT_TOOLS = 100`) are sent to the model
   every turn and also listed again in the system-prompt text; anything past 100
   is silently dropped. Most are meta tools (`*.doctor`, `*.handoff`,
   `*.example`, `*.export`/`*.import`, `*.stats`, `*.setup.matrix`,
   `runtime.portability.audit`). This costs tokens and latency, hurts tool
   choice, and breaks small-context models.
2. **Chat screen is not transcript-first.** Session title field, Rename/Archive
   buttons, provider banner and session chips take the top half of the screen.
3. **Task form is hostile.** Run time is typed as ISO-8601 UTC text; raw enum
   labels (`MainSession`, `IsolatedSession`); target-session chips show
   "Main session" twice; "Approximate WorkManager" jargon.
4. **FakeProvider replies dump internals** (session/request UUIDs, every tool
   name) into the chat.
5. **No localization.** All UI strings are hard-coded English (one string
   resource exists). The user works in Chinese.
6. **Missing phone-assistant capabilities**: fetching a web page, device status,
   opening links/apps/settings, alarms/timers/calendar events through system
   intents, clipboard, editable assistant persona/workspace files.
7. **Baseline build** (2026-10-30, offline, WSL): see ledger.

## Target end state (acceptance)

### A. Runtime (`rt` line)
- Model-facing tool set of at most 40 tools, OpenClaw-like grouping: sessions,
  memory, tasks (cron), skills, web fetch, device/intents, notifications,
  workspace files. Each description at most 300 chars; total serialized tool
  payload sent to a provider at most 24 KB (unit-tested).
- Native tool calling only: the tool list is not repeated in the system-prompt
  text for providers with native tools.
- Meta tools removed from the model surface. Any that back a UI feature become
  plain Kotlin services; the rest are deleted together with their tests.
- Workspace persona files (OpenClaw-style `AGENTS.md`, `SOUL.md`, `USER.md`,
  `MEMORY.md` notes) stored app-private, editable, injected into the system
  prompt with a byte budget.
- New typed tools: `web_fetch` (OkHttp, HTML to text, size/time bounded),
  `device_status`, `open_url` / `open_app` / `open_settings`, `set_alarm`,
  `set_timer`, `calendar_add_event` (system insert intent), `clipboard_get` /
  `clipboard_set`. Foreground-only tools declare it and fail cleanly from
  scheduled runs.
- FakeProvider gives short, human-readable deterministic replies and keeps its
  scripted tool-call paths for tests.
- Bundled skills updated to the new tool names.

### B. UI (`ui` line)
- Chat: top app bar (session title + menu), modal drawer with the session list
  (new, switch, rename, archive, delete), transcript-first message list with
  markdown, collapsible tool-call cards, streaming with a Stop button, slash
  command suggestions, overflow menu for search / export-share / usage /
  compact.
- Tasks: list first, FAB to a create/edit screen with local date and time
  pickers, interval presets, cron with next-run preview, readable labels,
  editing of existing tasks, enable toggle, run now, run history.
- Skills: list with toggles, detail view, import.
- Settings: provider pick/configure/test, memory, appearance, language,
  Diagnostics (former Health tab). Bottom navigation: Chat, Tasks, Skills,
  Settings.
- Every user-visible string in resources: English default plus complete
  `values-zh-rCN`.

### C. Quality gates
- `./scripts/run_ktlint.sh`, `:app:assembleDebug`, `:app:testDebugUnitTest`,
  `:app:lintDebug` green; `:app:assembleQa :app:assembleRelease` green.
- Emulator journey on the Windows AVD `AndroidClawApi34`: onboarding, chat with
  FakeProvider including a tool call, task create/edit/run-now, skill toggle and
  import, provider test, diagnostics; zero crash lines in logcat; screenshots
  saved under `handoff/screens/`.
- Room migrations tested for any schema bump.

### D. Docs and delivery
- README (English plus a Chinese section), `docs/` and `site/` match the app.
- `rework/v12` pushed, PR opened; merge to `main` on user approval.

## Execution layout

- Main checkout `E:\OPC\AndroidClaw` (branch `main`, then `rework/v12`): main
  session only. Holds `PLANv12.md`, `AGENTS.md`, `handoff/`, `notes/`
  (both locally excluded from git).
- Worktrees (created by the main session with Windows git):
  - `E:\OPC\AndroidClaw-wt\rt` on `rework/rt` for agent `rt`.
  - `E:\OPC\AndroidClaw-wt\ui` on `rework/ui` for agent `ui`.
- Rules every agent reads first: `handoff/agent_rules.md`.
- Pre-registered line plans: `notes/rt_plan.md`, `notes/ui_plan.md`.

## Batches

1. **Batch 0 (main):** baseline build, this plan, worktrees.
2. **Batch 1 (parallel):** `rt` (runtime rework) and `ui` (UI rework +
   localization). Emulator belongs to `ui` during batch 1.
3. **Batch 2:** main merges `rework/rt` and `rework/ui` into `rework/v12`; agent
   `int` integrates (workspace editor UI on top of the `rt` workspace store,
   device-tool permission UX, full emulator journey, bug fixes found there,
   docs/README/site truth).
4. **Batch 3 (main):** full gate, push, PR.

## Decisions

- 2026-10-30: Rework in place rather than a from-scratch rewrite: data layer,
  providers, scheduler and Room schema carry real, tested behavior; the tool
  layer and UI are rebuilt.
- 2026-10-30: No watchdog/backup agents (no remote server in this project);
  the main session pushes to GitHub at batch boundaries.
- 2026-10-30: Agents use Windows git (Git Bash) inside worktrees and WSL only
  for Gradle; WSL git cannot read worktrees created by Windows git.

## Running ledger

- 2026-10-30 22:55: Plan created from survey. Baseline green: 665/665 unit tests, lint, assembleDebug.
- 2026-10-31 00:00: rt line done (b44e944): tools 168→36 (34 model-facing), tool JSON/turn 46,158→12,889 B, runtime/tools 29,361→3,406 LOC, 467/467 tests; fixed dotted tool names, dropped tools, payloads not reaching the model, dangling tool calls on cancel.
- 2026-10-31 00:45: Live probes with the user-provided OpenAI key: chat/completions + tools returns 400 on gpt-6-luna / gpt-6.1-sol; /v1/responses works. Decision: official OpenAI → Responses API, preset default gpt-6-luna (user: "gpt-6-luna就可以"). rt revision 1 started (notes/live_plan.md).
- 2026-10-31 01:00: ui line done (a65fdc8): 4 tabs, 397 strings + 13 plurals en/zh-rCN, 174→0 hard-coded strings, task editing, 687/687 tests, instrumentation 11/11, journey 13/13. Merged rt+ui into rework/v12 (1b754c3). Batch 2 dispatched: int + doc.
