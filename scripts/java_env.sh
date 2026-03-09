#!/usr/bin/env bash

androidclaw_java_version_line() {
  local java_cmd="$1"
  "$java_cmd" -version 2>&1 | head -n 1
}

androidclaw_java_major_version() {
  local java_cmd="$1"
  local version_line
  local version_value
  local major

  version_line="$(androidclaw_java_version_line "$java_cmd")"
  version_value="$(sed -n 's/.*version "\(.*\)".*/\1/p' <<<"$version_line")"
  if [[ -z "$version_value" ]]; then
    return 1
  fi

  if [[ "$version_value" == 1.* ]]; then
    major="${version_value#1.}"
    major="${major%%.*}"
  else
    major="${version_value%%.*}"
  fi

  if [[ ! "$major" =~ ^[0-9]+$ ]]; then
    return 1
  fi

  printf '%s\n' "$major"
}

androidclaw_try_java_home() {
  local source_name="$1"
  local java_home_candidate="$2"
  local java_cmd
  local major

  if [[ -z "$java_home_candidate" ]]; then
    return 1
  fi

  java_cmd="$java_home_candidate/bin/java"
  if [[ ! -x "$java_cmd" ]]; then
    return 1
  fi

  major="$(androidclaw_java_major_version "$java_cmd")" || return 1
  if (( major < 17 )); then
    return 1
  fi

  export JAVA_HOME="$java_home_candidate"
  export PATH="$JAVA_HOME/bin:$PATH"
  export ANDROIDCLAW_RESOLVED_JAVA_SOURCE="$source_name"
  export ANDROIDCLAW_RESOLVED_JAVA_CMD="$java_cmd"
  export ANDROIDCLAW_RESOLVED_JAVA_MAJOR="$major"
  export ANDROIDCLAW_RESOLVED_JAVA_VERSION_LINE="$(androidclaw_java_version_line "$java_cmd")"
  return 0
}

androidclaw_use_java17() {
  local path_java
  local resolved_java
  local resolved_home
  local current_line="<not found>"

  if androidclaw_try_java_home "ANDROIDCLAW_JAVA_HOME" "${ANDROIDCLAW_JAVA_HOME:-}"; then
    return 0
  fi

  if androidclaw_try_java_home "JAVA_HOME" "${JAVA_HOME:-}"; then
    return 0
  fi

  if command -v java >/dev/null 2>&1; then
    path_java="$(command -v java)"
    resolved_java="$(readlink -f "$path_java")"
    resolved_home="$(cd "$(dirname "$resolved_java")/.." && pwd)"
    if androidclaw_try_java_home "PATH" "$resolved_home"; then
      return 0
    fi
    current_line="$(androidclaw_java_version_line "$resolved_java")"
  fi

  cat >&2 <<EOF
AndroidClaw requires Java 17 or newer for Gradle and Android builds.
Java resolution order:
1. ANDROIDCLAW_JAVA_HOME
2. JAVA_HOME
3. java on PATH

Current default java: $current_line
Set ANDROIDCLAW_JAVA_HOME to a valid JDK 17+ installation and rerun.
EOF
  return 1
}
