#!/usr/bin/env bash
set -euo pipefail

: "${RUNNER_TEMP:?RUNNER_TEMP is required}"
: "${GITHUB_PATH:?GITHUB_PATH is required}"
: "${GITHUB_WORKSPACE:?GITHUB_WORKSPACE is required}"

tools="$RUNNER_TEMP/renderer-tools"
mkdir "$tools"
python3 - "$GITHUB_WORKSPACE/renderer/upstream.json" <<'PY'
import json
import sys

pin = json.load(open(sys.argv[1], encoding="utf-8"))
assert pin["tools"] == {"node": "v22.23.3", "bun": "1.3.14"}
assert pin["nodeEngineCompatible"] is True
assert pin["nodeDistribution"]["url"] == "https://nodejs.org/dist/v22.23.3/node-v22.23.3-linux-x64.tar.xz"
assert pin["nodeDistribution"]["sha256"] == "df450af89261115ef9f9e3830c3eeb2cc9213b63c720b1af623cb5dcbe2e02de"
PY

curl --fail --show-error --silent --location --retry 3 \
  https://nodejs.org/dist/v22.23.3/node-v22.23.3-linux-x64.tar.xz \
  -o "$tools/node-v22.23.3-linux-x64.tar.xz"
curl --fail --show-error --silent --location --retry 3 \
  https://github.com/oven-sh/bun/releases/download/bun-v1.3.14/bun-linux-x64.zip \
  -o "$tools/bun-linux-x64.zip"
printf '%s\n' \
  'df450af89261115ef9f9e3830c3eeb2cc9213b63c720b1af623cb5dcbe2e02de  node-v22.23.3-linux-x64.tar.xz' \
  '951ee2aee855f08595aeec6225226a298d3fea83a3dcd6465c09cbccdf7e848f  bun-linux-x64.zip' \
  > "$tools/SHA256SUMS"
(cd "$tools" && sha256sum --check --strict SHA256SUMS)
tar -xJf "$tools/node-v22.23.3-linux-x64.tar.xz" -C "$tools"
unzip -q "$tools/bun-linux-x64.zip" -d "$tools"
export PATH="$tools/node-v22.23.3-linux-x64/bin:$tools/bun-linux-x64:$PATH"
[[ "$(node --version)" == v22.23.3 ]]
[[ "$(bun --version)" == 1.3.14 ]]
printf '%s\n' "$tools/node-v22.23.3-linux-x64/bin" "$tools/bun-linux-x64" >> "$GITHUB_PATH"
python3 - "$tools" <<'PY'
import json
import pathlib
import subprocess
import sys

root = pathlib.Path(sys.argv[1])
records = {
    "schemaVersion": 1,
    "node": {
        "version": subprocess.check_output(["node", "--version"], text=True).strip(),
        "url": "https://nodejs.org/dist/v22.23.3/node-v22.23.3-linux-x64.tar.xz",
        "sha256": "df450af89261115ef9f9e3830c3eeb2cc9213b63c720b1af623cb5dcbe2e02de",
        "checksumSource": "https://nodejs.org/dist/v22.23.3/SHASUMS256.txt",
    },
    "bun": {
        "version": subprocess.check_output(["bun", "--version"], text=True).strip(),
        "url": "https://github.com/oven-sh/bun/releases/download/bun-v1.3.14/bun-linux-x64.zip",
        "sha256": "951ee2aee855f08595aeec6225226a298d3fea83a3dcd6465c09cbccdf7e848f",
        "checksumSource": "https://api.github.com/repos/oven-sh/bun/releases/tags/bun-v1.3.14",
    },
}
(root / "tools.json").write_text(json.dumps(records, indent=2) + "\n", encoding="utf-8")
PY
