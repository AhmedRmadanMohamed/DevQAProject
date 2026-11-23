#!/usr/bin/env bash
# DevQA development runner: IntelliJ sandbox + continuous plugin rebuilds.
# React is bundled in the plugin; this is NOT Vite browser HMR.
set -Eeuo pipefail

cd "$(dirname "${BASH_SOURCE[0]}")"

if [[ ! -f gradlew ]]; then
  echo "ERROR: Run this script from the DevQAProject checkout." >&2
  exit 1
fi

# CHANGELOG.md was intentionally removed from this repo.
if [[ ! -f CHANGELOG.md ]] && grep -Eq '^[[:space:]]*id\("org\.jetbrains\.changelog"\)' build.gradle.kts; then
  echo "ERROR: Remove id(\"org.jetbrains.changelog\") from build.gradle.kts first." >&2
  echo "The deleted CHANGELOG.md otherwise causes 'Version is missing: Unreleased'." >&2
  exit 1
fi

ide_pid=""
watch_pid=""
cleanup() {
  trap - EXIT INT TERM
  if [[ -n "$watch_pid" ]]; then
    kill "$watch_pid" 2>/dev/null || true
    wait "$watch_pid" 2>/dev/null || true
  fi
  if [[ -n "$ide_pid" ]]; then
    kill "$ide_pid" 2>/dev/null || true
    wait "$ide_pid" 2>/dev/null || true
  fi
}
trap cleanup EXIT INT TERM

echo "Launching DevQA in the IntelliJ sandbox..."
bash ./gradlew --no-configuration-cache --no-daemon runIde &
ide_pid=$!

echo "Watching Kotlin, React and shared source files for changes..."
echo "When a rebuild completes, focus the sandbox IDE to trigger plugin auto-reload."
echo "React uses a bundled IIFE: this does not provide instant browser-style HMR."
echo "Press Ctrl+C to stop both processes."
bash ./gradlew --no-configuration-cache --no-daemon --continuous buildPlugin &
watch_pid=$!

status=0
wait -n "$ide_pid" "$watch_pid" || status=$?
exit "$status"
