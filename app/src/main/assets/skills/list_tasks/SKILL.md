---
name: list_tasks
description: Dispatch directly to the tasks.list tool.
user-invocable: true
disable-model-invocation: true
command-dispatch: tool
command-tool: tasks.list
metadata:
  android:
    requiresTools: ["tasks.list"]
---

Use this slash command to inspect known scheduler capabilities or persisted tasks without a model round-trip.

