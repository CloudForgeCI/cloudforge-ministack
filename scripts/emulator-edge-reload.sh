#!/usr/bin/env bash
# Lifecycle owned by EmulatorEdgeLifecycle — see docs/guides/LOCAL_EMULATOR_EDGE.md
set -euo pipefail
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
exec "$ROOT/scripts/emulator-edge-via-maven.sh" reload
