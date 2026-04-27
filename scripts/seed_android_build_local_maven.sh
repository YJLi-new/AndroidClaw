#!/usr/bin/env bash
set -euo pipefail

repo_root="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
gradle_cache="${GRADLE_USER_HOME:-$HOME/.gradle}/caches/modules-2/files-2.1"
local_repo="$repo_root/.gradle/local-maven"

group_path() {
  local group="$1"
  printf '%s\n' "${group//./\/}"
}

copy_cached_file() {
  local group="$1"
  local artifact="$2"
  local version="$3"
  local extension="$4"
  local source_root="$gradle_cache/$group/$artifact/$version"
  local target_dir="$local_repo/$(group_path "$group")/$artifact/$version"
  local source_file

  if [[ ! -d "$source_root" ]]; then
    return 1
  fi

  source_file="$(find "$source_root" -name "$artifact-$version.$extension" -print -quit)"
  if [[ -z "$source_file" ]]; then
    return 1
  fi

  mkdir -p "$target_dir"
  cp "$source_file" "$target_dir/$artifact-$version.$extension"
}

download_file() {
  local group="$1"
  local artifact="$2"
  local version="$3"
  local extension="$4"
  local base_url="$5"
  local target_dir="$local_repo/$(group_path "$group")/$artifact/$version"
  local url="$base_url/$(group_path "$group")/$artifact/$version/$artifact-$version.$extension"

  mkdir -p "$target_dir"
  curl --http1.1 --fail --location --silent --show-error "$url" \
    --output "$target_dir/$artifact-$version.$extension"
}

seed_artifact() {
  local group="$1"
  local artifact="$2"
  local version="$3"
  local repository="$4"
  local target_dir="$local_repo/$(group_path "$group")/$artifact/$version"
  local extension
  local required_extensions=(pom jar)
  local optional_extensions=(module)
  local base_url

  case "$repository" in
    google)
      base_url="https://dl.google.com/dl/android/maven2"
      ;;
    central)
      base_url="https://repo1.maven.org/maven2"
      ;;
    *)
      echo "Unknown repository '$repository' for $group:$artifact:$version" >&2
      return 1
      ;;
  esac

  for extension in "${required_extensions[@]}"; do
    if [[ -f "$target_dir/$artifact-$version.$extension" ]]; then
      continue
    fi
    if copy_cached_file "$group" "$artifact" "$version" "$extension"; then
      continue
    fi
    download_file "$group" "$artifact" "$version" "$extension" "$base_url"
  done

  for extension in "${optional_extensions[@]}"; do
    if [[ -f "$target_dir/$artifact-$version.$extension" ]]; then
      continue
    fi
    if copy_cached_file "$group" "$artifact" "$version" "$extension"; then
      continue
    fi
    download_file "$group" "$artifact" "$version" "$extension" "$base_url" 2>/dev/null || true
  done
}

seed_artifact "androidx.concurrent" "concurrent-futures" "1.1.0" "google"
seed_artifact "androidx.concurrent" "concurrent-futures-ktx" "1.1.0" "google"

seed_artifact "com.android.tools.lint" "lint-gradle" "31.13.0" "google"
seed_artifact "com.android.tools.lint" "lint" "31.13.0" "google"
seed_artifact "com.android.tools.lint" "lint-checks" "31.13.0" "google"
seed_artifact "com.android.tools.lint" "lint-api" "31.13.0" "google"
seed_artifact "com.android.tools.external.com-intellij" "intellij-core" "31.13.0" "google"
seed_artifact "com.android.tools.external.com-intellij" "kotlin-compiler" "31.13.0" "google"
seed_artifact "com.android.tools.external.org-jetbrains" "uast" "31.13.0" "google"
seed_artifact "com.android.tools" "play-sdk-proto" "31.13.0" "google"

seed_artifact "org.codehaus.groovy" "groovy" "3.0.22" "central"
seed_artifact "org.jetbrains.kotlin" "kotlin-reflect" "2.2.0" "central"
seed_artifact "com.google.errorprone" "error_prone_annotations" "2.28.0" "central"
seed_artifact "org.apache.httpcomponents" "httpclient" "4.5.6" "central"
seed_artifact "commons-codec" "commons-codec" "1.10" "central"

cat <<EOF
Seeded Android build fallback repo:
$local_repo

You can now run debug packaging and lint with --offline when Gradle cannot reach remote Maven repositories.
EOF
