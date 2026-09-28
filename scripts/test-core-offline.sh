#!/usr/bin/env bash
# Diagnostic fallback only. Does not replace project-locked Gradle/Android tests.
set -euo pipefail
cd -- "$(dirname -- "$0")/.."
command -v kotlinc >/dev/null || { echo 'kotlinc is required.' >&2; exit 1; }
KOTLIN_BIN=$(command -v kotlinc)
KOTLIN_ROOT=${KOTLIN_HOME:-$(cd -- "$(dirname -- "$KOTLIN_BIN")/.." && pwd)}
COROUTINES_JAR=${COROUTINES_JAR:-$KOTLIN_ROOT/lib/kotlinx-coroutines-core-jvm.jar}
[ -f "$COROUTINES_JAR" ] || { echo 'Set COROUTINES_JAR to a local coroutines-core JAR.' >&2; exit 1; }
mkdir -p .local-tests
{
    java -version 2>&1
    kotlinc -version 2>&1
    if command -v sha256sum >/dev/null; then sha256sum "$COROUTINES_JAR"; fi
} | tee .local-tests/toolchain.txt
kotlinc app/src/main/java/com/gccwebos/android/core/ConnectionCoordinator.kt \
    app/src/test/java/com/gccwebos/android/core/ConnectionCases.kt tools/OfflineCoreTests.kt \
    -cp "$COROUTINES_JAR" -include-runtime -d .local-tests/core-tests.jar
java -cp ".local-tests/core-tests.jar:$COROUTINES_JAR" OfflineCoreTestsKt .local-tests/core-tests.xml \
    | tee .local-tests/core-tests.log
