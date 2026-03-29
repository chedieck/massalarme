#!/usr/bin/env bash
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"

if ! command -v uv &>/dev/null; then
    echo "Error: uv is not installed. Install it from https://docs.astral.sh/uv/"
    exit 1
fi

echo "=== Massalarmee Installer ==="
make -C "$SCRIPT_DIR" install
