#!/usr/bin/env bash
set -euo pipefail

repo_root="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
script_root="$repo_root/scripts"

source "$script_root/java_env.sh"
androidclaw_use_java17

ktlint_jar="$("$script_root/ensure_ktlint.sh")"

mode_args=()
pattern_args=()

while [[ $# -gt 0 ]]; do
  case "$1" in
    --format)
      mode_args+=("--format")
      shift
      ;;
    --)
      shift
      while [[ $# -gt 0 ]]; do
        pattern_args+=("$1")
        shift
      done
      ;;
    *)
      pattern_args+=("$1")
      shift
      ;;
  esac
done

if [[ "${#pattern_args[@]}" -eq 0 ]]; then
  # `git ls-files` fails when WSL git reads a worktree created by Windows git;
  # fall back to the repository's Kotlin globs instead of silently linting nothing.
  if tracked_files="$(cd "$repo_root" && git ls-files '*.kt' '*.kts' 2>/dev/null)" && [[ -n "$tracked_files" ]]; then
    mapfile -t pattern_args <<<"$tracked_files"
  else
    echo "git ls-files unavailable; linting default globs." >&2
    pattern_args=("app/src/**/*.kt" "baselineprofile/src/**/*.kt" "*.kts" "*/*.kts")
  fi
fi

if [[ "${#pattern_args[@]}" -eq 0 ]]; then
  echo "No Kotlin files found to lint." >&2
  exit 1
fi

cd "$repo_root"
java -jar "$ktlint_jar" --relative "${mode_args[@]}" "${pattern_args[@]}"
