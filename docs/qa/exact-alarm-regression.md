# Exact Alarm Regression

Date: 2026-03-12

## Matrix

Command used:

```bash
ANDROIDCLAW_JAVA_HOME=/tmp/androidclaw-jdk17-extract/jdk-17.0.18+8 ./scripts/run_exact_alarm_regression.sh --api34-avd AndroidClawApi34 --api31-avd AndroidClawApi31 --no-window
```

Scenarios covered:

1. API 34 fresh-install precise task with exact-alarm access unavailable
2. API 31 explicit deny
3. API 31 explicit allow
4. API 31 deny after previously allowing

## Results

All four instrumentation runs passed.

Representative output:

```text
ai.androidclaw.runtime.scheduler.ExactAlarmRegressionTest:.
Time: 0.275
OK (1 test)

ai.androidclaw.runtime.scheduler.ExactAlarmRegressionTest:.
Time: 0.469
OK (1 test)

ai.androidclaw.runtime.scheduler.ExactAlarmRegressionTest:.
Time: 0.465
OK (1 test)

ai.androidclaw.runtime.scheduler.ExactAlarmRegressionTest:.
Time: 0.217
OK (1 test)
```

## Behavioral confirmation

- API 34 fresh install degraded precise work to WorkManager as expected.
- API 31 deny path degraded precise work to WorkManager as expected.
- API 31 allow path reported exact-alarm availability and passed the `dumpsys alarm` verification gate used by `ExactAlarmRegressionTest`.
- API 31 deny-after-allow regressed cleanly back to the degraded WorkManager path.

## Notes

- The regression harness uses `cmd appops set` from PowerShell and falls back to the special-access settings screen only if `appops` cannot change state. The 2026-03-12 run completed without the manual fallback.
- Exact-alarm evidence is now device-backed and sufficient to close `m5`.
