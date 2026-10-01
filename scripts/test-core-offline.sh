#!/usr/bin/env bash
# Diagnostic fallback only. Does not replace project-locked Gradle/Android tests.
set -euo pipefail
cd -- "$(dirname -- "$0")/.."
native_classpath() {
    if command -v cygpath >/dev/null; then cygpath -wp "$1"
    else printf '%s' "$1"; fi
}
if [ -n "${KOTLIN_TOOLS_DIR:-}" ]; then
    # The same verified compiler jars as the domain diagnostic; no synthetic compiler.
    CP=$(printf '%s:' "$KOTLIN_TOOLS_DIR"/*.jar)
    COMPILER=(java -cp "$(native_classpath "$CP")" org.jetbrains.kotlin.cli.jvm.K2JVMCompiler -no-stdlib -no-reflect)
    RUNTIME_OPTIONS=()
else
    command -v kotlinc >/dev/null || { echo 'kotlinc or KOTLIN_TOOLS_DIR is required.' >&2; exit 1; }
    KOTLIN_BIN=$(command -v kotlinc)
    KOTLIN_ROOT=${KOTLIN_HOME:-$(cd -- "$(dirname -- "$KOTLIN_BIN")/.." && pwd)}
    COROUTINES_JAR=${COROUTINES_JAR:-$KOTLIN_ROOT/lib/kotlinx-coroutines-core-jvm.jar}
    [ -f "$COROUTINES_JAR" ] || { echo 'Set COROUTINES_JAR to a local coroutines-core JAR.' >&2; exit 1; }
    CP=$COROUTINES_JAR
    COMPILER=(kotlinc)
    RUNTIME_OPTIONS=(-include-runtime)
fi
mkdir -p .local-tests
{
    java -version 2>&1
    "${COMPILER[@]}" -version 2>&1
    if command -v sha256sum >/dev/null; then
        if [ -n "${KOTLIN_TOOLS_DIR:-}" ]; then sha256sum "$KOTLIN_TOOLS_DIR"/*.jar
        else sha256sum "$COROUTINES_JAR"; fi
    fi
} | tee .local-tests/toolchain.txt
"${COMPILER[@]}" app/src/main/java/com/gccwebos/android/core/ConnectionCoordinator.kt \
    app/src/main/java/com/gccwebos/android/core/LocalConnectionContract.kt \
    app/src/test/java/com/gccwebos/android/core/ConnectionCases.kt tools/OfflineCoreTests.kt \
    -jvm-target 17 -cp "$(native_classpath "$CP")" "${RUNTIME_OPTIONS[@]}" -d .local-tests/core-tests.jar
java -cp "$(native_classpath ".local-tests/core-tests.jar:$CP")" OfflineCoreTestsKt .local-tests/core-tests.xml \
    | tee .local-tests/core-tests.log
