param(
    [Parameter(Mandatory = $true)]
    [string]$RepoRoot,
    [string]$LdPlayerHome = "E:\leidian\LDPlayer9",
    [int]$InstanceIndex = 0,
    [int]$BootTimeoutSeconds = 180,
    [string]$TestClass = "ai.androidclaw.app.MainActivitySmokeTest"
)

$ErrorActionPreference = "Stop"

$adb = Join-Path $LdPlayerHome "adb.exe"
$ldconsole = Join-Path $LdPlayerHome "ldconsole.exe"
$appApk = Join-Path $RepoRoot "app\build\outputs\apk\debug\app-debug.apk"
$testApk = Join-Path $RepoRoot "app\build\outputs\apk\androidTest\debug\app-debug-androidTest.apk"
$runner = "ai.androidclaw.app.test/androidx.test.runner.AndroidJUnitRunner"

foreach ($path in @($adb, $ldconsole, $appApk, $testApk)) {
    if (!(Test-Path $path)) {
        throw "Required path not found: $path"
    }
}

function Get-DeviceSerial {
    $lines = & $adb devices
    foreach ($line in $lines) {
        if ($line -match "^(?<serial>\S+)\s+device$") {
            return $matches["serial"]
        }
    }
    return $null
}

$serial = Get-DeviceSerial
if (-not $serial) {
    & $ldconsole launch --index $InstanceIndex | Out-Null
    $deadline = (Get-Date).AddSeconds($BootTimeoutSeconds)
    do {
        Start-Sleep -Seconds 2
        $serial = Get-DeviceSerial
    } while (-not $serial -and (Get-Date) -lt $deadline)
}

if (-not $serial) {
    throw "LDPlayer did not expose an adb device within $BootTimeoutSeconds seconds."
}

& $adb -s $serial wait-for-device | Out-Null

$deadline = (Get-Date).AddSeconds($BootTimeoutSeconds)
do {
    $bootCompleted = (& $adb -s $serial shell getprop sys.boot_completed).Trim()
    if ($bootCompleted -eq "1") {
        break
    }
    Start-Sleep -Seconds 2
} while ((Get-Date) -lt $deadline)

if ($bootCompleted -ne "1") {
    throw "LDPlayer device $serial did not finish booting within $BootTimeoutSeconds seconds."
}

& $adb -s $serial install -r $appApk
& $adb -s $serial install -r -t $testApk

& $adb -s $serial shell am instrument -w -e class $TestClass $runner
if ($LASTEXITCODE -ne 0) {
    exit $LASTEXITCODE
}
