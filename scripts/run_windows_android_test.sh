#!/usr/bin/env bash
set -euo pipefail

repo_root="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
script_root="$repo_root/scripts"

# Shared Java 17 resolution keeps the WSL wrapper portable across workstations.
source "$script_root/java_env.sh"

test_class="ai.androidclaw.app.MainActivitySmokeTest"
avd_name="AndroidClawApi34"
boot_timeout="300"
variant="debug"
instrumentation_args=()
no_window=0
wipe_data=0

while [[ $# -gt 0 ]]; do
  case "$1" in
    --test-class)
      test_class="$2"
      shift 2
      ;;
    --avd)
      avd_name="$2"
      shift 2
      ;;
    --boot-timeout)
      boot_timeout="$2"
      shift 2
      ;;
    --variant)
      variant="$2"
      shift 2
      ;;
    --instrumentation-arg)
      instrumentation_args+=("$2")
      shift 2
      ;;
    --no-window)
      no_window=1
      shift
      ;;
    --wipe-data)
      wipe_data=1
      shift
      ;;
    *)
      test_class="$1"
      shift
      ;;
  esac
done

"$script_root/check_host_prereqs.sh" --required-avd "$avd_name"
androidclaw_use_java17

androidclaw_build_android_test_artifacts "$repo_root" "$variant"

script_path_windows="$(wslpath -w "$script_root/run_windows_android_test.ps1")"
repo_root_windows="$(wslpath -w "$repo_root")"

powershell_args=(
  -NoProfile
  -ExecutionPolicy
  Bypass
  -File
  "$script_path_windows"
  -RepoRoot
  "$repo_root_windows"
  -AvdName
  "$avd_name"
  -Variant
  "$variant"
  -BootTimeoutSeconds
  "$boot_timeout"
  -TestClass
  "$test_class"
)

for arg in "${instrumentation_args[@]}"; do
  powershell_args+=(-InstrumentationArg "$arg")
done

if [[ "$no_window" -eq 1 ]]; then
  powershell_args+=(-NoWindow)
fi

if [[ "$wipe_data" -eq 1 ]]; then
  powershell_args+=(-WipeData)
fi

powershell.exe "${powershell_args[@]}"
