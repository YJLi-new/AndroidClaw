---
name: list_tasks
description: List scheduled tasks without a model round-trip.
user-invocable: true
disable-model-invocation: true
command-dispatch: tool
command-tool: tasks_list
metadata:
  android:
    requiresTools: ["tasks_list"]
---

Use `/list_tasks` to see every scheduled task with its id, schedule, state and next run time.
