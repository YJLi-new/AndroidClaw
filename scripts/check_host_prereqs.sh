#!/usr/bin/env bash
set -euo pipefail

repo_root="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
script_root="$repo_root/scripts"
required_avds=()

while [[ $# -gt 0 ]]; do
  case "$1" in
    --required-avd)
      required_avds+=("$2")
      shift 2
      ;;
    *)
      echo "Unknown argument: $1" >&2
      exit 1
      ;;
  esac
done

source "$script_root/java_env.sh"
androidclaw_use_java17

printf 'WSL_JAVA_SOURCE=%s\n' "$ANDROIDCLAW_RESOLVED_JAVA_SOURCE"
printf 'WSL_JAVA_HOME=%s\n' "$JAVA_HOME"
printf 'WSL_JAVA_VERSION=%s\n' "$ANDROIDCLAW_RESOLVED_JAVA_VERSION_LINE"

if command -v wslpath >/dev/null 2>&1; then
  echo "WSLPATH_PRESENT=true"
else
  echo "WSLPATH_PRESENT=false"
  echo "wslpath is required to hand Windows paths to the PowerShell harness." >&2
  exit 1
fi

if command -v powershell.exe >/dev/null 2>&1; then
  echo "POWERSHELL_PRESENT=true"
else
  echo "POWERSHELL_PRESENT=false"
  echo "powershell.exe is required for the Windows AVD harness." >&2
  exit 1
fi

script_path_windows="$(wslpath -w "$script_root/check_host_prereqs.ps1")"
windows_args=(
  -NoProfile
  -ExecutionPolicy
  Bypass
  -File
  "$script_path_windows"
)

if [[ "${#required_avds[@]}" -gt 0 ]]; then
  windows_args+=(-RequiredAvdName "$(IFS=,; echo "${required_avds[*]}")")
fi

if ! windows_output="$(powershell.exe "${windows_args[@]}" 2>&1)"; then
  printf '%s\n' "$windows_output"
  echo "Windows host preflight failed. Fix the issues above or use the Gradle Managed Device lane on a host with accelerator support." >&2
  exit 1
fi

printf '%s\n' "$windows_output"
