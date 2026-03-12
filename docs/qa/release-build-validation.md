# Release Build Validation

Date: 2026-03-12

## Command

```bash
ANDROIDCLAW_JAVA_HOME=/tmp/androidclaw-jdk17-extract/jdk-17.0.18+8 \
./gradlew --no-daemon \
  -Dkotlin.compiler.execution.strategy=in-process \
  -Dkotlin.incremental=false \
  :app:assembleDebug \
  :app:testDebugUnitTest \
  :app:lintDebug \
  :app:assembleRelease \
  --console=plain \
  --no-configuration-cache \
  --no-build-cache
```

## Result

- `:app:assembleDebug` passed
- `:app:testDebugUnitTest` passed
- `:app:lintDebug` passed
- `:app:assembleRelease` passed

Build result:

```text
BUILD SUCCESSFUL in 5m 22s
109 actionable tasks: 12 executed, 97 up-to-date
```

## Artifact

- release APK path:
  - `app/build/outputs/apk/release/app-release-unsigned.apk`
- release APK size:
  - `10,189,093` bytes

Largest uncompressed entries in the APK:

- `classes.dex`: `13,963,960` bytes (`4,800,740` compressed)
- `classes2.dex`: `10,023,716` bytes (`3,494,842` compressed)
- `classes3.dex`: `2,570,396` bytes (`966,887` compressed)
- `resources.arsc`: `474,928` bytes
- `okhttp3/internal/publicsuffix/publicsuffixes.gz`: `41,394` bytes

## Notes

- Release shrinking remains disabled in the current repo state. This run records the unshrunk baseline artifact rather than claiming a speculative optimization win.
- `lintDebug` is currently scoped to production sources. Test-source lint is intentionally ignored because AGP 8.13 + Kotlin FIR crashes while analyzing `debugUnitTest` and `debugAndroidTest` sources on this workstation.
- Network-backed lint version-check detectors are disabled so the repo fast loop remains deterministic and local.
