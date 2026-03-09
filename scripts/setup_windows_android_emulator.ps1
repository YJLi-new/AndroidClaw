param(
    [switch]$InstallAndroidStudio,
    [switch]$EnableHypervisorPlatform,
    [switch]$LaunchAndroidStudio
)

$ErrorActionPreference = "Stop"

function Test-IsAdministrator {
    $identity = [Security.Principal.WindowsIdentity]::GetCurrent()
    $principal = New-Object Security.Principal.WindowsPrincipal($identity)
    return $principal.IsInRole([Security.Principal.WindowsBuiltInRole]::Administrator)
}

$studioPath = "C:\Program Files\Android\Android Studio\bin\studio64.exe"
$sdkRoot = Join-Path $env:LOCALAPPDATA "Android\Sdk"
$emulatorPath = Join-Path $sdkRoot "emulator\emulator.exe"
$adbPath = Join-Path $sdkRoot "platform-tools\adb.exe"

if (!(Test-Path $studioPath)) {
    if (!$InstallAndroidStudio) {
        throw "Android Studio is not installed. Re-run with -InstallAndroidStudio to install Google.AndroidStudio via winget."
    }

    & winget install `
        --id Google.AndroidStudio `
        --exact `
        --accept-package-agreements `
        --accept-source-agreements `
        --silent
    if ($LASTEXITCODE -ne 0) {
        throw "winget failed to install Android Studio."
    }
}

$feature = Get-CimInstance Win32_OptionalFeature | Where-Object { $_.Name -eq "HypervisorPlatform" }
if ($EnableHypervisorPlatform) {
    if (!(Test-IsAdministrator)) {
        throw "EnableHypervisorPlatform requires an elevated PowerShell session."
    }
    if ($feature -and $feature.InstallState -ne 1) {
        Enable-WindowsOptionalFeature -Online -FeatureName HypervisorPlatform -All -NoRestart | Out-Null
    }
}

Write-Output "ANDROID_STUDIO=$studioPath"
Write-Output "SDK_ROOT=$sdkRoot"
Write-Output "EMULATOR_PRESENT=$(Test-Path $emulatorPath)"
Write-Output "ADB_PRESENT=$(Test-Path $adbPath)"
Write-Output "WHPX_INSTALL_STATE=$($feature.InstallState)"

if ($LaunchAndroidStudio) {
    Start-Process -FilePath $studioPath | Out-Null
}

if ((Test-Path $emulatorPath) -and (Test-Path $adbPath)) {
    & $emulatorPath -list-avds
} else {
    Write-Warning "Windows SDK tools are not present yet. Complete Android Studio first-run SDK setup, then create AVDs named AndroidClawApi34 and AndroidClawApi31 in Device Manager."
}
