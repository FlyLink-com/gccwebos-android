#!/usr/bin/env sh
# gccwebos launcher. Not a byte-for-byte Gradle-generated script.
# Bootstraps a checksum-pinned official JAR, then delegates to Gradle's wrapper.
set -eu
ROOT=$(CDPATH= cd -- "$(dirname -- "$0")" && pwd)
if [ -n "${JAVA_HOME:-}" ]; then JAVA="$JAVA_HOME/bin/java"; else JAVA=java; fi
if ! command -v "$JAVA" >/dev/null 2>&1; then
    echo 'Java is unavailable. Install JDK 17 and set JAVA_HOME.' >&2
    exit 1
fi
sh "$ROOT/scripts/bootstrap-wrapper.sh"
# Use JAVA_TOOL_OPTIONS for optional JVM flags; arguments are never eval'd.
exec "$JAVA" -Dfile.encoding=UTF-8 -Dorg.gradle.appname=gradlew -jar "$ROOT/gradle/wrapper/gradle-wrapper.jar" "$@"
