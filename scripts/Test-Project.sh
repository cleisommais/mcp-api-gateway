#!/usr/bin/env bash
set -euo pipefail
source "$(dirname "${BASH_SOURCE[0]}")/common.sh"
if [[ $# -ne 0 ]]; then echo 'Usage: bash scripts/Test-Project.sh' >&2; exit 2; fi
require_command docker
mkdir -p reports/tests
tools_compose run --build --rm checks
