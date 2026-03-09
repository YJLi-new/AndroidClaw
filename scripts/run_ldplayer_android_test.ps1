param(
    [Parameter(Mandatory = $true)]
    [string]$RepoRoot,
    [int]$BootTimeoutSeconds = 180,
    [string]$TestClass = "ai.androidclaw.app.MainActivitySmokeTest",
    [string]$AvdName = "AndroidClawApi34",
    [string[]]$InstrumentationArg = @(),
    [switch]$NoWindow,
    [switch]$WipeData
)

$ErrorActionPreference = "Stop"
Write-Warning "scripts/run_ldplayer_android_test.ps1 is deprecated. Delegating to scripts/run_windows_android_test.ps1."

$delegate = Join-Path $PSScriptRoot "run_windows_android_test.ps1"
$arguments = @(
    "-RepoRoot", $RepoRoot,
    "-BootTimeoutSeconds", $BootTimeoutSeconds,
    "-TestClass", $TestClass,
    "-AvdName", $AvdName
)
foreach ($arg in $InstrumentationArg) {
    $arguments += @("-InstrumentationArg", $arg)
}
if ($NoWindow) {
    $arguments += "-NoWindow"
}
if ($WipeData) {
    $arguments += "-WipeData"
}

& $delegate @arguments
if ($LASTEXITCODE -ne 0) {
    exit $LASTEXITCODE
}
