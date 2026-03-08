#!/usr/bin/env bash
set -euo pipefail

repo_root="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
script_root="$repo_root/scripts"
java_home_default="/home/lanla/.local/jdks/jdk-17.0.18+8"

if [[ -z "${JAVA_HOME:-}" && -d "$java_home_default" ]]; then
  export JAVA_HOME="$java_home_default"
fi

if [[ -n "${JAVA_HOME:-}" ]]; then
  export PATH="$JAVA_HOME/bin:$PATH"
fi

"$repo_root/gradlew" :app:assembleDebug :app:assembleDebugAndroidTest

script_path_windows="$(wslpath -w "$script_root/run_ldplayer_android_test.ps1")"
repo_root_windows="$(wslpath -w "$repo_root")"

powershell.exe -NoProfile -ExecutionPolicy Bypass -File "$script_path_windows" -RepoRoot "$repo_root_windows"
