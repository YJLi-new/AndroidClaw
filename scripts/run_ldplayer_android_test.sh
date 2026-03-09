#!/usr/bin/env bash
set -euo pipefail

repo_root="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"

echo "scripts/run_ldplayer_android_test.sh is deprecated. Delegating to scripts/run_windows_android_test.sh." >&2
exec "$repo_root/scripts/run_windows_android_test.sh" "$@"
