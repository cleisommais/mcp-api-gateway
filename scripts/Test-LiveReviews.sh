#!/usr/bin/env bash
set -euo pipefail
source "$(dirname "${BASH_SOURCE[0]}")/common.sh"
require_command python3
require_command docker
python3 scripts/local_tools.py live "$@"
