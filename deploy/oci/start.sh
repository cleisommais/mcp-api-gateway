#!/usr/bin/env bash
set -euo pipefail
cd /opt/prreview
mkdir -p reports
chmod 700 secrets reports
chmod 600 .env secrets/*
docker compose -f compose.vps.yaml config --quiet
docker compose -f compose.vps.yaml build
docker compose -f compose.vps.yaml up -d --wait --wait-timeout 300
docker compose -f compose.vps.yaml ps
