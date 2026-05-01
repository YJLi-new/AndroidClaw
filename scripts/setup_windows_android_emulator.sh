#!/usr/bin/env bash
set -euo pipefail

repo_root="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
script_root="$repo_root/scripts"

install_android_studio=0
enable_hypervisor_platform=0
launch_android_studio=0

while [[ $# -gt 0 ]]; do
  case "$1" in
    --install-android-studio)
      install_android_studio=1
      shift
      ;;
    --enable-hypervisor-platform)
      enable_hypervisor_platform=1
      shift
      ;;
    --launch-android-studio)
      launch_android_studio=1
      shift
      ;;
    *)
      echo "Unknown argument: $1" >&2
      exit 1
      ;;
  esac
done

script_path_windows="$(wslpath -w "$script_root/setup_windows_android_emulator.ps1")"

powershell_args=(
  -NoProfile
  -ExecutionPolicy
  Bypass
  -File
  "$script_path_windows"
)

if [[ "$install_android_studio" -eq 1 ]]; then
  powershell_args+=(-InstallAndroidStudio)
fi

if [[ "$enable_hypervisor_platform" -eq 1 ]]; then
  powershell_args+=(-EnableHypervisorPlatform)
fi

if [[ "$launch_android_studio" -eq 1 ]]; then
  powershell_args+=(-LaunchAndroidStudio)
fi

powershell.exe "${powershell_args[@]}"
