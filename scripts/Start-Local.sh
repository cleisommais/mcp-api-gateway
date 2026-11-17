#!/usr/bin/env bash
set -euo pipefail
source "$(dirname "${BASH_SOURCE[0]}")/common.sh"
no_build=false
case "${1:-}" in
    --no-build) no_build=true; shift ;;
    -h|--help) echo 'Usage: bash scripts/Start-Local.sh [--no-build]'; exit 0 ;;
esac
if [[ $# -ne 0 ]]; then echo 'Usage: bash scripts/Start-Local.sh [--no-build]' >&2; exit 2; fi
require_command docker
require_command python3
bash scripts/Initialize-LocalEnvironment.sh
if [[ "$no_build" == false ]]; then docker compose build; fi
docker compose up -d --wait postgres ollama
# Resolve the same environment interpolation and overrides as Compose itself.
model=$(docker compose config --format json | python3 -c 'import json,sys; print(json.load(sys.stdin)["services"]["agent-worker"]["environment"]["OLLAMA_MODEL"])')
if ! docker compose exec -T ollama ollama show "$model" >/dev/null 2>&1; then
    docker compose exec -T ollama ollama pull "$model"
fi
docker compose up -d --wait --remove-orphans
echo 'Ready: http://localhost:18081/health. Submit a review with scripts/Invoke-Review.sh.'
