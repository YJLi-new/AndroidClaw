---
name: task_helper
description: Turn loose requests into scheduled AndroidClaw tasks.
user-invocable: true
metadata: '{"openclaw":{"skillKey":"task_helper"},"android":{"requiresTools":["tasks_create","tasks_list"],"foregroundRequired":false}}'
---

When the user asks to schedule, repeat or automate something:

1. Pick the schedule: `once` with `atIso` (local time such as `2026-03-08T09:30` is fine), `interval` with `repeatEveryMinutes` (15 or more), or `cron` with a five-field `cronExpression` (minute hour day-of-month month day-of-week).
2. Write `prompt` as the instruction the assistant should follow when the task runs, for example "Check the weather for Hangzhou with web_fetch and notify me if it will rain."
3. Use `executionMode` `MAIN_SESSION` to post results into the chat, or `ISOLATED_SESSION` for background work that should not clutter it.
4. Call `tasks_create`, then confirm the name and next run time to the user. Use `tasks_list`, `tasks_update` (`enabled=false` pauses) and `tasks_delete` to manage existing tasks.
