---
name: memory
description: Manage local cross-session memories.
user-invocable: true
disable-model-invocation: true
command-dispatch: tool
command-tool: memory.command
metadata:
  android:
    requiresTools: ["memory.command"]
---

Use `/memory` to inspect local cross-session Memory status.

Use `/memory remember <text>` to store an explicit memory.

Use `/memory search <query>` to search stored memories.

Use `/memory list` to list recent memories.

Use `/memory delete <id>` to delete one memory.

Use `/memory clear CONFIRM` to clear all local memories, even when Memory capture is off.
