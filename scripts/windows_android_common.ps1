Set-StrictMode -Version Latest

function Get-AndroidSdkRoot {
    if ($env:ANDROID_SDK_ROOT -and (Test-Path $env:ANDROID_SDK_ROOT)) {
        return $env:ANDROID_SDK_ROOT
    }

    $sdkRoot = Join-Path $env:LOCALAPPDATA "Android\Sdk"
    if (Test-Path $sdkRoot) {
        return $sdkRoot
    }

    throw "Android SDK root not found. Expected ANDROID_SDK_ROOT or $sdkRoot."
}

function Get-AndroidToolPaths {
    $sdkRoot = Get-AndroidSdkRoot
    $emulator = Join-Path $sdkRoot "emulator\emulator.exe"
    $adb = Join-Path $sdkRoot "platform-tools\adb.exe"

    foreach ($path in @($emulator, $adb)) {
        if (!(Test-Path $path)) {
            throw "Required Android SDK tool not found: $path"
        }
    }

    return @{
        SdkRoot = $sdkRoot
        Emulator = $emulator
        Adb = $adb
    }
}

function Get-HypervisorPlatformInstallState {
    $feature = Get-CimInstance Win32_OptionalFeature | Where-Object { $_.Name -eq "HypervisorPlatform" } | Select-Object -First 1
    if ($feature) {
        return $feature.InstallState
    }

    return $null
}

function Get-AvailableAvdNames {
    param(
        [Parameter(Mandatory = $true)]
        [string]$Emulator
    )

    if (!(Test-Path $Emulator)) {
        return @()
    }

    $avds = & $Emulator -list-avds 2>$null
    if ($LASTEXITCODE -ne 0) {
        return @()
    }

    return @(
        $avds |
            Where-Object { $_ -and $_.Trim() } |
            ForEach-Object { $_.Trim() }
    )
}

function Get-EmulatorSerialForAvd {
    param(
        [Parameter(Mandatory = $true)]
        [string]$Adb,
        [Parameter(Mandatory = $true)]
        [string]$AvdName
    )

    $lines = & $Adb devices
    foreach ($line in $lines) {
        if ($line -notmatch "^(?<serial>emulator-\d+)\s+device$") {
            continue
        }
        $serial = $matches["serial"]
        $reportedName = (& $Adb -s $serial emu avd name 2>$null | Select-Object -First 1).Trim()
        if ($reportedName -eq $AvdName) {
            return $serial
        }
    }

    return $null
}

function Wait-ForAndroidBoot {
    param(
        [Parameter(Mandatory = $true)]
        [string]$Adb,
        [Parameter(Mandatory = $true)]
        [string]$Serial,
        [Parameter(Mandatory = $true)]
        [int]$BootTimeoutSeconds
    )

    & $Adb -s $Serial wait-for-device | Out-Null
    $deadline = (Get-Date).AddSeconds($BootTimeoutSeconds)
    do {
        $bootCompleted = (& $Adb -s $Serial shell getprop sys.boot_completed 2>$null | Select-Object -First 1).Trim()
        if ($bootCompleted -eq "1") {
            return
        }
        Start-Sleep -Seconds 2
    } while ((Get-Date) -lt $deadline)

    throw "Android emulator $Serial did not finish booting within $BootTimeoutSeconds seconds."
}

function Start-AndroidAvd {
    param(
        [Parameter(Mandatory = $true)]
        [string]$Emulator,
        [Parameter(Mandatory = $true)]
        [string]$Adb,
        [Parameter(Mandatory = $true)]
        [string]$AvdName,
        [Parameter(Mandatory = $true)]
        [int]$BootTimeoutSeconds,
        [switch]$NoWindow,
        [switch]$WipeData
    )

    $existingSerial = Get-EmulatorSerialForAvd -Adb $Adb -AvdName $AvdName
    if ($existingSerial) {
        Wait-ForAndroidBoot -Adb $Adb -Serial $existingSerial -BootTimeoutSeconds $BootTimeoutSeconds
        return $existingSerial
    }

    $arguments = @(
        "-avd", $AvdName,
        "-gpu", "swiftshader_indirect",
        "-netdelay", "none",
        "-netspeed", "full"
    )
    if ($NoWindow) {
        $arguments += @("-no-window", "-no-audio", "-no-boot-anim")
    }
    if ($WipeData) {
        $arguments += "-wipe-data"
    }

    Start-Process -FilePath $Emulator -ArgumentList $arguments | Out-Null

    $deadline = (Get-Date).AddSeconds($BootTimeoutSeconds)
    do {
        Start-Sleep -Seconds 2
        $existingSerial = Get-EmulatorSerialForAvd -Adb $Adb -AvdName $AvdName
    } while (-not $existingSerial -and (Get-Date) -lt $deadline)

    if (-not $existingSerial) {
        throw "AVD $AvdName did not expose an adb serial within $BootTimeoutSeconds seconds."
    }

    Wait-ForAndroidBoot -Adb $Adb -Serial $existingSerial -BootTimeoutSeconds $BootTimeoutSeconds
    return $existingSerial
}

function Install-AndroidTestPackages {
    param(
        [Parameter(Mandatory = $true)]
        [string]$Adb,
        [Parameter(Mandatory = $true)]
        [string]$Serial,
        [Parameter(Mandatory = $true)]
        [string]$RepoRoot,
        [string]$Variant = "debug"
    )

    $normalizedVariant = $Variant.ToLowerInvariant()
    $appApk = Join-Path $RepoRoot "app\build\outputs\apk\$normalizedVariant\app-$normalizedVariant.apk"
    $testApk = Join-Path $RepoRoot "app\build\outputs\apk\androidTest\debug\app-debug-androidTest.apk"

    foreach ($path in @($appApk, $testApk)) {
        if (!(Test-Path $path)) {
            throw "Required APK not found: $path"
        }
    }

    & $Adb -s $Serial install -r $appApk
    if ($LASTEXITCODE -ne 0) {
        throw "Failed to install $normalizedVariant APK on $Serial."
    }
    & $Adb -s $Serial install -r -t $testApk
    if ($LASTEXITCODE -ne 0) {
        throw "Failed to install shared androidTest APK on $Serial."
    }
}

function Invoke-AndroidInstrumentation {
    param(
        [Parameter(Mandatory = $true)]
        [string]$Adb,
        [Parameter(Mandatory = $true)]
        [string]$Serial,
        [Parameter(Mandatory = $true)]
        [string]$TestRunner,
        [string]$TestClass,
        [string[]]$InstrumentationArgs = @()
    )

    $command = @("shell", "am", "instrument", "-w")
    if ($TestClass) {
        $command += @("-e", "class", $TestClass)
    }

    foreach ($pair in $InstrumentationArgs) {
        $delimiterIndex = $pair.IndexOf("=")
        if ($delimiterIndex -lt 1) {
            throw "Instrumentation arg must be key=value, got: $pair"
        }
        $key = $pair.Substring(0, $delimiterIndex)
        $value = $pair.Substring($delimiterIndex + 1)
        $command += @("-e", $key, $value)
    }

    $command += $TestRunner
    & $Adb -s $Serial @command
    if ($LASTEXITCODE -ne 0) {
        throw "Instrumentation failed on $Serial."
    }
}

function Uninstall-AndroidPackageIfPresent {
    param(
        [Parameter(Mandatory = $true)]
        [string]$Adb,
        [Parameter(Mandatory = $true)]
        [string]$Serial,
        [Parameter(Mandatory = $true)]
        [string]$PackageName
    )

    & $Adb -s $Serial uninstall $PackageName | Out-Null
}
