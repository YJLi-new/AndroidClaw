param(
    [Parameter(Mandatory = $true)]
    [string]$RepoRoot,
    [string]$AvdName = "AndroidClawApi34",
    [string]$Variant = "debug",
    [int]$BootTimeoutSeconds = 300,
    [string]$TestClass = "ai.androidclaw.app.MainActivitySmokeTest",
    [string[]]$InstrumentationArg = @(),
    [switch]$NoWindow,
    [switch]$WipeData
)

$ErrorActionPreference = "Stop"
. (Join-Path $PSScriptRoot "windows_android_common.ps1")

$toolPaths = Get-AndroidToolPaths
$serial = Start-AndroidAvd `
    -Emulator $toolPaths.Emulator `
    -Adb $toolPaths.Adb `
    -AvdName $AvdName `
    -BootTimeoutSeconds $BootTimeoutSeconds `
    -NoWindow:$NoWindow `
    -WipeData:$WipeData

Install-AndroidTestPackages -Adb $toolPaths.Adb -Serial $serial -RepoRoot $RepoRoot -Variant $Variant
Invoke-AndroidInstrumentation `
    -Adb $toolPaths.Adb `
    -Serial $serial `
    -TestRunner "ai.androidclaw.app.test/androidx.test.runner.AndroidJUnitRunner" `
    -TestClass $TestClass `
    -InstrumentationArgs $InstrumentationArg
