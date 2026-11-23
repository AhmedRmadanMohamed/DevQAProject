#!/usr/bin/env bash
# React-only Service 01 verification; legacy assets are not needed.
set -euo pipefail
devqa_repo_dir="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$devqa_repo_dir"
printf "DevQA Service 01: install npm workspaces\n"
npm install --no-audit --no-fund
printf "\nReact and native bridge regression tests\n"
npm run frontend:test
printf "\nTypeScript typecheck and React production bundle\n"
npm run frontend:build
if [[ "${DEVQA_RUN_GRADLE:-0}" == "1" ]]; then
  gradle --no-daemon test buildPlugin
  if [[ "${DEVQA_VERIFY_IDES:-0}" == "1" ]]; then
    gradle --no-daemon verifyPlugin
  fi
else
  printf "Native Kotlin / JCEF checks not run. Use DEVQA_RUN_GRADLE=1.\n"
fi
