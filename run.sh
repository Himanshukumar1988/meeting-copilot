#!/bin/sh
# Runs the app from a private copy of the jar, so rebuilding (mvn package) while it is running
# cannot break it. Java loads classes lazily from the jar, and a replaced jar causes NoClassDefFoundError.
set -e
cd "$(dirname "$0")"
[ -f target/meeting-copilot.jar ] || mvn -q -DskipTests package
COPY="$(mktemp -t meeting-copilot).jar"
cp target/meeting-copilot.jar "$COPY"
trap 'rm -f "$COPY"' EXIT
java -jar "$COPY" "$@"
