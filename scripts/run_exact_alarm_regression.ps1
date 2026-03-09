param(
    [Parameter(Mandatory = $true)]
    [string]$RepoRoot,
    [string]$Api34AvdName = "AndroidClawApi34",
    [string]$Api31AvdName = "AndroidClawApi31",
    [int]$BootTimeoutSeconds = 300,
    [switch]$NoWindow
)

$ErrorActionPreference = "Stop"
. (Join-Path $PSScriptRoot "windows_android_common.ps1")

$appPackage = "ai.androidclaw.app"
$testPackage = "ai.androidclaw.app.test"
$testClass = "ai.androidclaw.runtime.scheduler.ExactAlarmRegressionTest"
$runner = "ai.androidclaw.app.test/androidx.test.runner.AndroidJUnitRunner"

function Set-ExactAlarmAccess {
    param(
        [Parameter(Mandatory = $true)]
        [string]$Adb,
        [Parameter(Mandatory = $true)]
        [string]$Serial,
        [Parameter(Mandatory = $true)]
        [string]$Mode
    )

    $operations = @("SCHEDULE_EXACT_ALARM", "android:schedule_exact_alarm")
    foreach ($operation in $operations) {
        & $Adb -s $Serial shell cmd appops set $appPackage $operation $Mode 2>$null
        if ($LASTEXITCODE -eq 0) {
            return
        }
    }

    & $Adb -s $Serial shell am start -a android.settings.REQUEST_SCHEDULE_EXACT_ALARM -d "package:$appPackage" | Out-Null
    throw "Unable to change exact alarm access via appops on $Serial. Settings screen opened for manual completion."
}

function Invoke-ExactAlarmAssertion {
    param(
        [Parameter(Mandatory = $true)]
        [string]$Adb,
        [Parameter(Mandatory = $true)]
        [string]$Serial,
        [Parameter(Mandatory = $true)]
        [bool]$ExpectedGranted,
        [bool]$VerifyShellAlarm = $false
    )

    $instrumentationArgs = @("expectedExactAlarmGranted=$($ExpectedGranted.ToString().ToLowerInvariant())")
    if ($VerifyShellAlarm) {
        $instrumentationArgs += "verifyShellAlarm=true"
    }
    Invoke-AndroidInstrumentation `
        -Adb $Adb `
        -Serial $Serial `
        -TestRunner $runner `
        -TestClass $testClass `
        -InstrumentationArgs $instrumentationArgs
}

$toolPaths = Get-AndroidToolPaths

$serial34 = Start-AndroidAvd `
    -Emulator $toolPaths.Emulator `
    -Adb $toolPaths.Adb `
    -AvdName $Api34AvdName `
    -BootTimeoutSeconds $BootTimeoutSeconds `
    -NoWindow:$NoWindow `
    -WipeData
Uninstall-AndroidPackageIfPresent -Adb $toolPaths.Adb -Serial $serial34 -PackageName $appPackage
Uninstall-AndroidPackageIfPresent -Adb $toolPaths.Adb -Serial $serial34 -PackageName $testPackage
Install-AndroidTestPackages -Adb $toolPaths.Adb -Serial $serial34 -RepoRoot $RepoRoot
Invoke-ExactAlarmAssertion -Adb $toolPaths.Adb -Serial $serial34 -ExpectedGranted $false

$serial31 = Start-AndroidAvd `
    -Emulator $toolPaths.Emulator `
    -Adb $toolPaths.Adb `
    -AvdName $Api31AvdName `
    -BootTimeoutSeconds $BootTimeoutSeconds `
    -NoWindow:$NoWindow `
    -WipeData
Uninstall-AndroidPackageIfPresent -Adb $toolPaths.Adb -Serial $serial31 -PackageName $appPackage
Uninstall-AndroidPackageIfPresent -Adb $toolPaths.Adb -Serial $serial31 -PackageName $testPackage
Install-AndroidTestPackages -Adb $toolPaths.Adb -Serial $serial31 -RepoRoot $RepoRoot

Set-ExactAlarmAccess -Adb $toolPaths.Adb -Serial $serial31 -Mode "deny"
Invoke-ExactAlarmAssertion -Adb $toolPaths.Adb -Serial $serial31 -ExpectedGranted $false

Set-ExactAlarmAccess -Adb $toolPaths.Adb -Serial $serial31 -Mode "allow"
Invoke-ExactAlarmAssertion -Adb $toolPaths.Adb -Serial $serial31 -ExpectedGranted $true -VerifyShellAlarm $true

Set-ExactAlarmAccess -Adb $toolPaths.Adb -Serial $serial31 -Mode "deny"
Invoke-ExactAlarmAssertion -Adb $toolPaths.Adb -Serial $serial31 -ExpectedGranted $false
