#!/usr/bin/env bash

androidclaw_local_property() {
  local property_name="$1"
  local properties_file="$2"

  if [[ ! -f "$properties_file" ]]; then
    return 1
  fi

  sed -n "s#^${property_name}=##p" "$properties_file" | tail -n 1
}

androidclaw_resolve_android_sdk_root() {
  local repo_root="$1"
  local local_sdk
  local candidate
  local candidates=()

  if [[ -n "${ANDROID_SDK_ROOT:-}" ]]; then
    candidates+=("$ANDROID_SDK_ROOT")
  fi
  if [[ -n "${ANDROID_HOME:-}" ]]; then
    candidates+=("$ANDROID_HOME")
  fi

  local_sdk="$(androidclaw_local_property "sdk.dir" "$repo_root/local.properties" || true)"
  if [[ -n "$local_sdk" ]]; then
    candidates+=("$local_sdk")
  fi

  candidates+=(
    "$HOME/.local/android-sdk"
    "$HOME/Android/Sdk"
  )

  for candidate in "${candidates[@]}"; do
    if [[ -x "$candidate/platform-tools/adb" ]]; then
      printf '%s\n' "$candidate"
      return 0
    fi
  done

  return 1
}

androidclaw_use_android_sdk() {
  local repo_root="$1"
  local sdk_root

  sdk_root="$(androidclaw_resolve_android_sdk_root "$repo_root")" || {
    cat >&2 <<EOF
Android SDK platform-tools were not found.
Resolution order:
1. ANDROID_SDK_ROOT
2. ANDROID_HOME
3. sdk.dir in local.properties
4. $HOME/.local/android-sdk
5. $HOME/Android/Sdk
Install Android SDK platform-tools or set ANDROID_SDK_ROOT, then rerun.
EOF
    return 1
  }

  export ANDROID_SDK_ROOT="$sdk_root"
  export ANDROID_HOME="$sdk_root"
  export ANDROIDCLAW_ADB="$sdk_root/platform-tools/adb"
  export PATH="$sdk_root/platform-tools:$PATH"
}
