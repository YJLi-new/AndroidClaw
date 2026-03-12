param(
    [string[]]$RequiredAvdName = @()
)

$ErrorActionPreference = "Stop"
. (Join-Path $PSScriptRoot "windows_android_common.ps1")

$problems = New-Object System.Collections.Generic.List[string]
$sdkRoot = $null
$emulatorPath = $null
$adbPath = $null
$availableAvds = @()
$hypervisorState = Get-HypervisorPlatformInstallState

try {
    $sdkRoot = Get-AndroidSdkRoot
} catch {
    $problems.Add($_.Exception.Message)
}

if ($sdkRoot) {
    $emulatorPath = Join-Path $sdkRoot "emulator\emulator.exe"
    $adbPath = Join-Path $sdkRoot "platform-tools\adb.exe"

    if (!(Test-Path $emulatorPath)) {
        $problems.Add("Windows emulator.exe not found at $emulatorPath. Complete Android Studio SDK setup first.")
    }
    if (!(Test-Path $adbPath)) {
        $problems.Add("Windows adb.exe not found at $adbPath. Install platform-tools from Android Studio SDK Manager.")
    }
    if (Test-Path $emulatorPath) {
        $availableAvds = @(Get-AvailableAvdNames -Emulator $emulatorPath)
    }
}

if ($hypervisorState -ne 1) {
    $problems.Add("HypervisorPlatform/WHPX is not enabled (InstallState=$hypervisorState). Enable it from Windows Features or rerun the setup helper from an elevated PowerShell session.")
}

if ($RequiredAvdName.Count -gt 0) {
    $normalizedRequiredAvdNames = @(
        $RequiredAvdName |
            ForEach-Object { $_ -split "," } |
            ForEach-Object { $_.Trim() } |
            Where-Object { $_ }
    )
    $missingAvds = @($normalizedRequiredAvdNames | Where-Object { $_ -notin $availableAvds })
    if ($missingAvds.Count -gt 0) {
        $problems.Add("Missing required AVD(s): $($missingAvds -join ', '). Create them in Android Studio Device Manager.")
    }
}

Write-Output "WINDOWS_SDK_ROOT=$(if ($sdkRoot) { $sdkRoot } else { '<missing>' })"
Write-Output "WINDOWS_EMULATOR_PRESENT=$([bool](($emulatorPath) -and (Test-Path $emulatorPath)))"
Write-Output "WINDOWS_ADB_PRESENT=$([bool](($adbPath) -and (Test-Path $adbPath)))"
Write-Output "WHPX_INSTALL_STATE=$(if ($null -ne $hypervisorState) { $hypervisorState } else { '<unknown>' })"
Write-Output "AVAILABLE_AVDS=$(if ($availableAvds.Count -gt 0) { $availableAvds -join ', ' } else { '<none>' })"

if ($problems.Count -gt 0) {
    foreach ($problem in $problems) {
        Write-Error $problem
    }
    exit 1
}
