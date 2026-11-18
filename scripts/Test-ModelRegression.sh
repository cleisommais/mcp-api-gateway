#!/usr/bin/env bash
set -euo pipefail
source "$(dirname "${BASH_SOURCE[0]}")/common.sh"
provider=openai
label=
while [[ $# -gt 0 ]]; do
    case "$1" in
        --provider|--label)
            if [[ $# -lt 2 ]]; then echo "Missing value for $1" >&2; exit 2; fi
            if [[ "$1" == --provider ]]; then provider=$2; else label=$2; fi
            shift 2 ;;
        -h|--help) echo 'Usage: bash scripts/Test-ModelRegression.sh [--provider openai|ollama] --label LABEL'; exit 0 ;;
        *) echo "Unknown argument: $1" >&2; exit 2 ;;
    esac
done
if [[ "$provider" != openai && "$provider" != ollama ]]; then echo 'Provider must be openai or ollama' >&2; exit 2; fi
if [[ -z "$label" ]]; then echo '--label is required' >&2; exit 2; fi
require_command docker
export REGRESSION_PROVIDER=$provider
tools_compose run --build --rm model-regression capture
tools_compose run --rm model-regression run "$label"
