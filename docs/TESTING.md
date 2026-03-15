# TESTING

> This file records the supported validation lanes for AndroidClaw v0 RC work.

## Fast loop

From the repository root:

- `./gradlew :app:assembleDebug`
- `./gradlew :app:testDebugUnitTest`
- `./gradlew :app:lintDebug`

Current lint posture:

- production-source lint is required and green
- test-source lint is intentionally ignored for now because AGP 8.13 + Kotlin FIR crashes while analyzing `debugUnitTest` and `debugAndroidTest` sources on this workstation
- unit tests and androidTest compilation remain the repo-supported guardrails for those source sets until the toolchain issue is removed or upgraded away

GitHub Actions keeps this loop fast and currently does not gate every PR on device/emulator instrumentation.

## CI parity

GitHub Actions now runs two required jobs on pushes and pull requests:

- `fast`
  - `:app:assembleDebug`
  - `:app:testDebugUnitTest`
  - `:app:lintDebug`
- `packaging`
  - `:app:assembleDebugAndroidTest`
  - `:app:assembleQa`
  - `:app:assembleRelease`
  - `:app:bundleRelease`

Uploaded workflow artifacts:

- `fast-reports`
  - lint output and unit-test reports
- `packaging-outputs`
  - debug APKs, the installable `qa` APK, and the release bundle/output directories

The device-smoke and exact-alarm emulator lanes remain repo-owned manual or workflow-dispatch style paths, not mandatory PR gates.

## Installable QA lane

Use `qa` when you need a locally installable, release-like APK before production signing exists.

Repo tasks:

- `./gradlew :app:assembleQa`
- `./gradlew :app:assembleAndroidTest`

Windows AVD launch smoke through the WSL wrapper:

```bash
ANDROIDCLAW_JAVA_HOME=/path/to/jdk17 ./scripts/run_windows_android_test.sh --variant qa --launch-smoke --avd AndroidClawApi34 --launch-component ai.androidclaw.app/.MainActivity
```

Use the dedicated launch-smoke mode for minified `qa`. The shared `androidTest` APK remains the repo-owned debug instrumentation lane, but it is not a truthful release-like proof once `qa` is shrunk.

This lane is intentionally separate from `release`:

- `debug` = fastest local correctness loop
- `qa` = installable release-like validation lane
- `release` = eventual production packaging lane

## Device lane 1: Gradle Managed Device

Use this on hosts where the Android emulator can run directly under Gradle-managed infrastructure.

Preferred command:

```bash
./gradlew :app:pixel8Api36DebugAndroidTest -Pandroid.testoptions.manageddevices.emulator.gpu=swiftshader_indirect
```

This remains the official long-term repo-native lane.

## Device lane 2: Windows AVD from WSL

Use this on the current workstation class where Gradle Managed Devices are not the practical path.

Java resolution for the WSL wrappers is:

1. `ANDROIDCLAW_JAVA_HOME`
2. `JAVA_HOME`
3. `java` on `PATH` if it is Java 17+

The wrappers fail early if no Java 17+ runtime is available.

Preflight:

```bash
ANDROIDCLAW_JAVA_HOME=/path/to/jdk17 ./scripts/check_host_prereqs.sh --required-avd AndroidClawApi34 --required-avd AndroidClawApi31
```

Targeted smoke:

```bash
ANDROIDCLAW_JAVA_HOME=/path/to/jdk17 ./scripts/run_windows_android_test.sh --avd AndroidClawApi34 --test-class ai.androidclaw.app.MainActivitySmokeTest
ANDROIDCLAW_JAVA_HOME=/path/to/jdk17 ./scripts/run_windows_android_test.sh --avd AndroidClawApi34 --test-class ai.androidclaw.runtime.scheduler.TaskExecutionWorkerSmokeTest
```

Exact-alarm regression:

```bash
ANDROIDCLAW_JAVA_HOME=/path/to/jdk17 ./scripts/run_exact_alarm_regression.sh --api34-avd AndroidClawApi34 --api31-avd AndroidClawApi31
```

Recorded evidence:

- `docs/qa/qa-build-validation.md`
- `docs/qa/release-size-validation.md`
- `docs/qa/windows-emulator-validation.md`
- `docs/qa/exact-alarm-regression.md`

The preflight summary reports:

- WSL Java version and source
- whether `wslpath` exists
- whether `powershell.exe` is callable
- Windows SDK root
- presence of `emulator.exe`
- presence of `adb.exe`
- HypervisorPlatform / WHPX state
- available AVD names

## Persistence and upgrade validation

Current persistence floor:

- Room schema version is `2`
- migration `1 -> 2` is explicit and exported under `app/schemas/`
- androidTest assets include the exported schemas so `MigrationTestHelper` can validate real upgrades

Current startup-maintenance behavior:

- keeps or recreates the main session before scheduler rebuild
- prunes only `TaskRun` rows older than 30 days
- prunes only `EventLog` rows older than 14 days
- does not automatically prune live tasks, live sessions, chat messages, or skill records
- replays scheduler restore at app startup and on:
  - `BOOT_COMPLETED`
  - `MY_PACKAGE_REPLACED`
  - `TIME_CHANGED`
  - `TIMEZONE_CHANGED`

Recorded evidence:

- `docs/qa/persistence-validation.md`

Repo-side validation:

```bash
./gradlew :app:testDebugUnitTest
./gradlew :app:assembleDebugAndroidTest
```

Targeted migration instrumentation on any available Windows AVD:

```bash
ANDROIDCLAW_JAVA_HOME=/path/to/jdk17 ./scripts/run_windows_android_test.sh --avd <available-avd> --test-class ai.androidclaw.data.db.AndroidClawDatabaseMigrationTest
```

Targeted startup-maintenance / restore coverage lives in JVM tests:

- `ai.androidclaw.app.StartupMaintenanceTest`
- `ai.androidclaw.runtime.scheduler.SchedulerRestoreReceiverTest`

## Deprecated lane

LDPlayer wrappers remain in the repo only as compatibility shims for older notes. They are not an official validation lane for v0 RC.

## Release validation

Current repo-supported release check:

- `./gradlew :app:assembleQa`
- `./gradlew :app:assembleRelease`
- `./gradlew :app:bundleRelease`
- `ANDROIDCLAW_JAVA_HOME=/path/to/jdk17 ./scripts/run_windows_android_test.sh --variant qa --launch-smoke --avd AndroidClawApi34 --launch-component ai.androidclaw.app/.MainActivity`

Recorded evidence:

- `docs/qa/release-build-validation.md`
- `docs/qa/rc-validation.md`
- `docs/RELEASE_CHECKLIST.md`
- `docs/KNOWN_LIMITATIONS.md`
