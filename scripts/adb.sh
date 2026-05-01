#!/usr/bin/env bash
set -euo pipefail

repo_root="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
source "$repo_root/scripts/android_env.sh"

androidclaw_use_android_sdk "$repo_root"
exec "$ANDROIDCLAW_ADB" "$@"
