---
name: compact
description: Compact the current session into a durable summary and hide older turns.
user-invocable: true
disable-model-invocation: true
command-dispatch: tool
command-tool: sessions.compact
metadata:
  android:
    requiresTools: ["sessions.compact"]
---

Use `/compact` to compact earlier turns into a concise summary. AndroidClaw uses the active provider when available and falls back to a deterministic local summary in offline or fake-provider mode.

Use `/compact <summary>` to store an explicit summary and compact the earlier turns without a model round-trip.

The summary should capture stable context that should survive long-session pruning, such as the current goal, important decisions, and unresolved next steps.
