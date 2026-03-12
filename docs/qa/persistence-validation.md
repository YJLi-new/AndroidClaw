# Persistence Validation

Date: 2026-03-12

## Host and device lane

- Validation lane: Windows AVD from WSL
- Windows SDK root: `%LOCALAPPDATA%\Android\Sdk`
- AVD used for current persistence evidence: `Medium_Phone_API_36.1`
- WHPX state during run: enabled

## Commands run

Fast repo suite:

```bash
ANDROIDCLAW_JAVA_HOME=/tmp/androidclaw-jdk17-extract/jdk-17.0.18+8 \
./gradlew --no-daemon \
  -Dkotlin.compiler.execution.strategy=in-process \
  -Dkotlin.incremental=false \
  :app:testDebugUnitTest \
  :app:assembleDebug \
  :app:assembleDebugAndroidTest \
  :app:lintDebug \
  --no-configuration-cache \
  --no-build-cache
```

Device-backed migration instrumentation:

```bash
powershell.exe -NoProfile -ExecutionPolicy Bypass -File \
  "$(wslpath -w /mnt/e/opc/androidclaw/scripts/run_windows_android_test.ps1)" \
  -RepoRoot "$(wslpath -w /mnt/e/opc/androidclaw)" \
  -AvdName Medium_Phone_API_36.1 \
  -BootTimeoutSeconds 300 \
  -TestClass ai.androidclaw.data.db.AndroidClawDatabaseMigrationTest \
  -NoWindow
```

Device-backed startup-maintenance instrumentation:

```bash
powershell.exe -NoProfile -ExecutionPolicy Bypass -File \
  "$(wslpath -w /mnt/e/opc/androidclaw/scripts/run_windows_android_test.ps1)" \
  -RepoRoot "$(wslpath -w /mnt/e/opc/androidclaw)" \
  -AvdName Medium_Phone_API_36.1 \
  -BootTimeoutSeconds 300 \
  -TestClass ai.androidclaw.app.StartupMaintenanceIntegrationTest \
  -NoWindow
```

Official WSL wrapper validation after Gradle hardening:

```bash
ANDROIDCLAW_JAVA_HOME=/tmp/androidclaw-jdk17-extract/jdk-17.0.18+8 \
./scripts/run_windows_android_test.sh \
  --avd Medium_Phone_API_36.1 \
  --test-class ai.androidclaw.app.StartupMaintenanceIntegrationTest \
  --no-window
```

## Results

- `:app:testDebugUnitTest` passed
- `:app:assembleDebug` passed
- `:app:assembleDebugAndroidTest` passed
- `:app:lintDebug` passed
- `AndroidClawDatabaseMigrationTest` passed on `Medium_Phone_API_36.1`
- `StartupMaintenanceIntegrationTest` passed on `Medium_Phone_API_36.1`
- `run_windows_android_test.sh` passed end to end on `Medium_Phone_API_36.1` after switching the wrapper to the repo's stable Gradle flags

## What the current evidence proves

- migration `1 -> 2` preserves realistic legacy user data across:
  - sessions
  - messages
  - tasks
  - task runs
  - event logs
  - bundled/local/workspace legacy skill rows
- startup maintenance:
  - recreates the main session when missing
  - preserves live sessions, messages, and tasks
  - prunes only old `TaskRun` and `EventLog` rows
  - re-enqueues pending work through the real scheduler path

## Host quirk observed

The first direct PowerShell device run hit a transient ADB race during initial emulator boot:

- `adb.exe: device 'emulator-5554' not found`

Rerunning against the already-booted AVD succeeded without repo changes. This does not currently block the persistence lane, but it is worth keeping in mind when the exact-alarm API 31/API 34 matrix is run later.

## Follow-up

- Exact-alarm regression is no longer blocked; device-backed results now live in `docs/qa/exact-alarm-regression.md`.
- This persistence document remains focused on migration and startup-maintenance proof only.
