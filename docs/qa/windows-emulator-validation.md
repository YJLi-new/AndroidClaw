# Windows Emulator Validation

Date: 2026-03-12

## Host

- Windows Android SDK root: `C:\Users\lanla\AppData\Local\Android\Sdk`
- WHPX / `HypervisorPlatform`: enabled (`InstallState=1`)
- AVD inventory:
  - `AndroidClawApi31`
  - `AndroidClawApi34`
  - `Medium_Phone_API_36.1`
- WSL Java override used for repo wrappers:
  - `ANDROIDCLAW_JAVA_HOME=/tmp/androidclaw-jdk17-extract/jdk-17.0.18+8`

## Commands

```bash
ANDROIDCLAW_JAVA_HOME=/tmp/androidclaw-jdk17-extract/jdk-17.0.18+8 ./scripts/check_host_prereqs.sh --required-avd AndroidClawApi34 --required-avd AndroidClawApi31
ANDROIDCLAW_JAVA_HOME=/tmp/androidclaw-jdk17-extract/jdk-17.0.18+8 ./scripts/run_windows_android_test.sh --avd AndroidClawApi34 --test-class ai.androidclaw.app.MainActivitySmokeTest --no-window
ANDROIDCLAW_JAVA_HOME=/tmp/androidclaw-jdk17-extract/jdk-17.0.18+8 ./scripts/run_windows_android_test.sh --avd AndroidClawApi34 --test-class ai.androidclaw.runtime.scheduler.TaskExecutionWorkerSmokeTest --no-window
```

## Results

- Host preflight passed and reported the expected AVD names.
- `MainActivitySmokeTest` passed on `AndroidClawApi34`.
- `TaskExecutionWorkerSmokeTest` passed on `AndroidClawApi34`.

Representative instrumentation output:

```text
ai.androidclaw.app.MainActivitySmokeTest:.
Time: 7.984
OK (1 test)

ai.androidclaw.runtime.scheduler.TaskExecutionWorkerSmokeTest:.
Time: 0.274
OK (1 test)
```

## Notes

- The WSL preflight wrapper needed a handoff fix for `RequiredAvdName`; PowerShell `-File` argument binding treated the two AVD names as one combined string until the wrapper normalized comma-separated input.
- After that fix, the Windows-emulator lane ran end to end without `cmd.exe` or LDPlayer.
