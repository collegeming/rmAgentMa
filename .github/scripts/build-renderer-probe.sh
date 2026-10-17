#!/usr/bin/env bash
set -euo pipefail

: "${RUNNER_TEMP:?RUNNER_TEMP is required}"
: "${GITHUB_WORKSPACE:?GITHUB_WORKSPACE is required}"
source_dir="$RUNNER_TEMP/aionui-source"
work_dir="$RUNNER_TEMP/aionui-renderer-work"
output_dir="$RUNNER_TEMP/probe-renderer"
reports="$RUNNER_TEMP/renderer-reports"
mkdir "$reports"
cd "$GITHUB_WORKSPACE"
python3 - renderer/upstream.json <<'PY'
import json
import sys

pin = json.load(open(sys.argv[1], encoding="utf-8"))
assert pin["repository"] == "https://github.com/iOfficeAI/AionUi"
assert pin["commit"] == "6744099b279b991c17e31c243f0920477bd31cb6"
PY

git init --quiet "$source_dir"
git -C "$source_dir" remote add origin https://github.com/iOfficeAI/AionUi.git
GIT_TERMINAL_PROMPT=0 git -C "$source_dir" fetch --depth=1 --no-tags origin 6744099b279b991c17e31c243f0920477bd31cb6
git -C "$source_dir" -c advice.detachedHead=false checkout --detach FETCH_HEAD
[[ "$(git -C "$source_dir" rev-parse HEAD)" == 6744099b279b991c17e31c243f0920477bd31cb6 ]]
node renderer/scripts/prepare.mjs --source "$source_dir" --workdir "$work_dir" 2>&1 | tee "$reports/prepare.log"
node renderer/scripts/install.mjs --workdir "$work_dir" 2>&1 | tee "$reports/install.log"
node "$work_dir/node_modules/vitest/vitest.mjs" run --config "$GITHUB_WORKSPACE/renderer/tests/vitest.config.mjs" \
  --reporter=default --reporter=json --outputFile="$reports/build-tools-tests.json" 2>&1 | tee "$reports/build-tools-tests.log"
node renderer/scripts/build.mjs --workdir "$work_dir" --output "$output_dir" 2>&1 | tee "$reports/build.log"
node renderer/scripts/check-assets.mjs --dir "$work_dir/out/renderer" --workdir "$work_dir" 2>&1 | tee "$reports/asset-audit.log"
node renderer/scripts/build.mjs --verify "$output_dir" 2>&1 | tee "$reports/manifest-verification.log"
