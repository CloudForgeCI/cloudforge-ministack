#!/usr/bin/env bash
# Runs a main class with this checkout's code on the classpath of an installed CloudForge CLI.
#
#   validation/run-from-source.sh com.cloudforgeci.cli.Main deploy --context ctx.json --target ministack
#   validation/run-from-source.sh com.cloudforge.core.local.EmulatorEdgeCli reconcile
#
# The classpath is this module's classes, the dependencies Maven resolves for it (cloudforge-core and
# cloudforge-api at the versions in the pom), then the installed CLI's own classes and libraries, so the
# code under test wins over the copies bundled with the CLI. CLOUDFORGE_CLI_HOME points at the CLI's
# libexec directory (default: Homebrew's cloudforge-cli); JAVA_HOME selects the JVM, as for the CLI.
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
CLI_HOME="${CLOUDFORGE_CLI_HOME:-$(brew --prefix cloudforge-cli)/libexec}"
CLASSPATH_FILE="$ROOT/target/runtime-classpath.txt"

if [[ ! -f "$CLASSPATH_FILE" || "$ROOT/pom.xml" -nt "$CLASSPATH_FILE" ]]; then
  (cd "$ROOT" && mvn -B -q -DskipTests compile dependency:build-classpath -Dmdep.outputFile="$CLASSPATH_FILE" >&2)
fi

JAVA="java"
if [[ -n "${JAVA_HOME:-}" && -x "$JAVA_HOME/bin/java" ]]; then
  JAVA="$JAVA_HOME/bin/java"
fi
MAIN="${1:?usage: $0 <main-class> [arguments]}"
shift
exec "$JAVA" -cp "$ROOT/target/classes:$(cat "$CLASSPATH_FILE"):$CLI_HOME/classes:$CLI_HOME/dependency/*" "$MAIN" "$@"
