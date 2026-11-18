#!/usr/bin/env bash
# Shared by the Bash entrypoints; compatible with macOS Bash 3.2.
set -euo pipefail

PROJECT_ROOT=$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)
cd "$PROJECT_ROOT"

require_command() {
    command -v "$1" >/dev/null 2>&1 || {
        printf 'Required command not found: %s. See README.md prerequisites.\n' "$1" >&2
        exit 1
    }
}

tools_compose() {
    local files=(-f compose.yaml)
    if [[ -f compose.override.yaml ]]; then
        files+=(-f compose.override.yaml)
    fi
    docker compose "${files[@]}" -f compose.tools.yaml "$@"
}
