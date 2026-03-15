# KNOWN LIMITATIONS

Date: 2026-03-15

## Current v0 RC limitations

- Exact alarms may be denied by default on Android 14+ devices. AndroidClaw degrades precise work to WorkManager and surfaces that state in diagnostics instead of pretending exact delivery is available.
- Notifications may be denied or disabled on Android 13+ devices. Scheduler diagnostics and reminder visibility therefore depend on notification state, not only on exact-alarm state.
- Android standby bucket and background quota behavior can affect background work timing, especially with `targetSdk = 36`.
- Baseline Profiles are not included in the current RC pass. This is a known deferral, not a hidden omission.
- The minified `qa` APK uses direct install-and-launch smoke as its release-like proof lane. The shared debug `androidTest` APK remains for debug instrumentation and exact-alarm regression, but it is not the correctness proof for shrunk release-like packaging.
- Real-provider QA requires a valid API key and compatible endpoint; the repo-required validation path uses `FakeProvider` and mock/instrumented coverage.
- AndroidClaw v0 does not include browser automation, external chat-channel integrations, remote bridge mode, shell execution, voice workflows, or cloud sync.

## Tester guidance

- Prefer the health and tasks screens when validating scheduler behavior; they expose degradation and capability state directly.
- Treat exact-alarm behavior as capability-dependent, not guaranteed.
- Treat notification visibility as a separate prerequisite from task scheduling success.
