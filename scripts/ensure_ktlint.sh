#!/usr/bin/env bash
set -euo pipefail

readonly ANDROIDCLAW_KTLINT_VERSION="1.5.0"
readonly ANDROIDCLAW_KTLINT_SHA256="a27854622198800f50971a049dcdba38f2105d47e7e7258786c7d28045c5735d"
readonly ANDROIDCLAW_KTLINT_URL="https://repo.maven.apache.org/maven2/com/pinterest/ktlint/ktlint-cli/${ANDROIDCLAW_KTLINT_VERSION}/ktlint-cli-${ANDROIDCLAW_KTLINT_VERSION}-all.jar"

ktlint_cache_root="${ANDROIDCLAW_KTLINT_CACHE_DIR:-${XDG_CACHE_HOME:-$HOME/.cache}/androidclaw-tools/ktlint/${ANDROIDCLAW_KTLINT_VERSION}}"
ktlint_jar="$ktlint_cache_root/ktlint-cli-${ANDROIDCLAW_KTLINT_VERSION}-all.jar"

mkdir -p "$ktlint_cache_root"

download_ktlint() {
  local temp_file

  temp_file="$(mktemp "$ktlint_cache_root/.ktlint.XXXXXX.jar")"
  trap 'rm -f "$temp_file"' RETURN
  curl --noproxy '*' -fsSL "$ANDROIDCLAW_KTLINT_URL" -o "$temp_file"
  printf '%s  %s\n' "$ANDROIDCLAW_KTLINT_SHA256" "$temp_file" | sha256sum --check --status
  mv "$temp_file" "$ktlint_jar"
  trap - RETURN
}

if [[ ! -f "$ktlint_jar" ]]; then
  download_ktlint
else
  if ! printf '%s  %s\n' "$ANDROIDCLAW_KTLINT_SHA256" "$ktlint_jar" | sha256sum --check --status; then
    rm -f "$ktlint_jar"
    download_ktlint
  fi
fi

printf '%s\n' "$ktlint_jar"
