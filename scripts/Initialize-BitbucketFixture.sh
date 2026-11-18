#!/usr/bin/env bash
set -euo pipefail
source "$(dirname "${BASH_SOURCE[0]}")/common.sh"
if [[ $# -ne 0 ]]; then echo 'Usage: bash scripts/Initialize-BitbucketFixture.sh' >&2; exit 2; fi
require_command docker
mkdir -p reports/bitbucket
docker run --rm \
    --mount "type=bind,source=$PROJECT_ROOT,target=/workspace,readonly" \
    --mount "type=bind,source=$PROJECT_ROOT/reports/bitbucket,target=/workspace/reports/bitbucket" \
    --workdir /workspace python:3.12-slim python3 scripts/bitbucket_fixture.py
