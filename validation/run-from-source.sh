#!/usr/bin/env bash
# Runs a main class with this checkout's code on the classpath of an installed CloudForge CLI.
#
#   validation/run-from-source.sh com.cloudforgeci.cli.Main deploy --context ctx.json --target ministack
#   validation/run-from-source.sh com.cloudforge.core.local.EmulatorEdgeCli reconcile
#
# The classpath is this module's classes, the dependencies Maven resolves for it (cloudforge-core), the
# cloudforge-api of the same version from the local Maven repository (CLOUDFORGE_API_VERSION overrides the
# version; install the cfc-core checkout under test first), then the installed CLI's own
# classes and libraries, so the code under test wins over the copies bundled with the CLI. cloudforge-api
# holds the deploy logic, so a CLI release older than the checkout would otherwise deploy with its own. CLOUDFORGE_CLI_HOME points at the CLI's
# libexec directory (default: Homebrew's cloudforge-cli); JAVA_HOME selects the JVM, as for the CLI.
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
CLI_HOME="${CLOUDFORGE_CLI_HOME:-$(brew --prefix cloudforge-cli)/libexec}"
CLASSPATH_FILE="$ROOT/target/runtime-classpath.txt"

if [[ ! -f "$CLASSPATH_FILE" || "$ROOT/pom.xml" -nt "$CLASSPATH_FILE" ]]; then
  (cd "$ROOT" && mvn -B -q -DskipTests compile dependency:build-classpath -Dmdep.outputFile="$CLASSPATH_FILE" >&2)
fi

API_VERSION="${CLOUDFORGE_API_VERSION:-$(cd "$ROOT" && mvn -B -q help:evaluate -Dexpression=project.version -DforceStdout)}"
API_JAR="$ROOT/target/api/cloudforge-api-$API_VERSION.jar"
if [[ ! -f "$API_JAR" ]]; then
  (cd "$ROOT" && mvn -B -q dependency:copy -Dartifact="com.cloudforgeci:cloudforge-api:$API_VERSION:jar" \
    -DoutputDirectory="$ROOT/target/api" >&2)
fi

JAVA="java"
if [[ -n "${JAVA_HOME:-}" && -x "$JAVA_HOME/bin/java" ]]; then
  JAVA="$JAVA_HOME/bin/java"
fi
MAIN="${1:?usage: $0 <main-class> [arguments]}"
shift
exec "$JAVA" -cp "$ROOT/target/classes:$(cat "$CLASSPATH_FILE"):$API_JAR:$CLI_HOME/classes:$CLI_HOME/dependency/*" "$MAIN" "$@"
