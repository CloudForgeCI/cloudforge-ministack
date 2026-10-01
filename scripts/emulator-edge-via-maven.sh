#!/usr/bin/env bash
# Thin wrapper — edge lifecycle is owned by EmulatorEdgeLifecycle (Maven / Java), which lives in
# cfc-core's cloudforge-core module. Docs: see cfc-core's docs/guides/LOCAL_EMULATOR_EDGE.md.
#
# Runs EmulatorEdgeCli through exec-maven-plugin's fully qualified coordinates. There is no
# `cloudforge:` Maven plugin prefix, so `mvn cloudforge:emulator-edge-<goal>` does not work.
#
# Unlike cfc-core (where cloudforge-core is a sibling reactor module, reached with `-pl
# cloudforge-core`), this repo resolves cloudforge-core as an ordinary <dependency> of its own
# single-module build (inherited via the cfc-core <parent> POM). So the goal runs against this
# repo's own root module — no `-pl` needed — and cloudforge-core is already on its classpath.
set -euo pipefail

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
GOAL="${1:?usage: $0 <start|stop|restart|rebuild|status|reconcile|reload>}"
cd "$ROOT"
exec mvn -q org.codehaus.mojo:exec-maven-plugin:3.5.0:java \
  -Dexec.mainClass=com.cloudforge.core.local.EmulatorEdgeCli \
  -Dexec.args="${GOAL}" \
  -Dexec.classpathScope=runtime
