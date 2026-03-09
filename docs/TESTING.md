# TESTING

> This file records the supported validation lanes for AndroidClaw v0 RC work.

## Fast loop

From the repository root:

- `./gradlew :app:assembleDebug`
- `./gradlew :app:testDebugUnitTest`
- `./gradlew :app:lintDebug`

GitHub Actions keeps this loop fast and currently does not gate every PR on device/emulator instrumentation.

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

The preflight summary reports:

- WSL Java version and source
- whether `wslpath` exists
- whether `powershell.exe` is callable
- Windows SDK root
- presence of `emulator.exe`
- presence of `adb.exe`
- HypervisorPlatform / WHPX state
- available AVD names

## Deprecated lane

LDPlayer wrappers remain in the repo only as compatibility shims for older notes. They are not an official validation lane for v0 RC.
