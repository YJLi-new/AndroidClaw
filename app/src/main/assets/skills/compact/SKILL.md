---
name: compact
description: Store an explicit compacted summary for the current session.
user-invocable: true
disable-model-invocation: true
command-dispatch: tool
command-tool: sessions.compact
metadata:
  android:
    requiresTools: ["sessions.compact"]
---

Use `/compact <summary>` to save a concise, explicit summary for the current session without a model round-trip.

The summary should capture stable context that should survive long-session pruning, such as the current goal, important decisions, and unresolved next steps.
