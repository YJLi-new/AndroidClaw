#!/usr/bin/env bash
set -euo pipefail

repo_root="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
script_root="$repo_root/scripts"
java_home_default="/home/lanla/.local/jdks/jdk-17.0.18+8"

api34_avd="AndroidClawApi34"
api31_avd="AndroidClawApi31"
boot_timeout="300"
no_window=0

while [[ $# -gt 0 ]]; do
  case "$1" in
    --api34-avd)
      api34_avd="$2"
      shift 2
      ;;
    --api31-avd)
      api31_avd="$2"
      shift 2
      ;;
    --boot-timeout)
      boot_timeout="$2"
      shift 2
      ;;
    --no-window)
      no_window=1
      shift
      ;;
    *)
      echo "Unknown argument: $1" >&2
      exit 1
      ;;
  esac
done

if [[ -z "${JAVA_HOME:-}" && -d "$java_home_default" ]]; then
  export JAVA_HOME="$java_home_default"
fi

if [[ -n "${JAVA_HOME:-}" ]]; then
  export PATH="$JAVA_HOME/bin:$PATH"
fi

"$repo_root/gradlew" :app:assembleDebug :app:assembleDebugAndroidTest

script_path_windows="$(wslpath -w "$script_root/run_exact_alarm_regression.ps1")"
repo_root_windows="$(wslpath -w "$repo_root")"

powershell_args=(
  -NoProfile
  -ExecutionPolicy
  Bypass
  -File
  "$script_path_windows"
  -RepoRoot
  "$repo_root_windows"
  -Api34AvdName
  "$api34_avd"
  -Api31AvdName
  "$api31_avd"
  -BootTimeoutSeconds
  "$boot_timeout"
)

if [[ "$no_window" -eq 1 ]]; then
  powershell_args+=(-NoWindow)
fi

powershell.exe "${powershell_args[@]}"
