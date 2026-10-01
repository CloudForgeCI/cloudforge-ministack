#!/usr/bin/env bash
# Deprecated standalone Docker helpers. Edge lifecycle is owned by
# EmulatorEdgeLifecycle (scripts/emulator-edge-via-maven.sh).
# Kept only for path defaults if you need to inspect .emulator-edge manually.
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
EDGE_CONTAINER="${CFC_EDGE_CONTAINER:-cfc-emulator-edge}"
EDGE_RUNTIME_DIR="${CFC_EDGE_RUNTIME_DIR:-$ROOT/.emulator-edge}"
EDGE_CONF_DIR="$EDGE_RUNTIME_DIR/conf.d"
