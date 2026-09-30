#!/usr/bin/env bash
# Diagnostic JVM fallback. Does not replace pinned Gradle, Lint or instrumentation.
set -euo pipefail
cd -- "$(dirname -- "$0")/.."
: "${KOTLIN_TOOLS_DIR:?Set KOTLIN_TOOLS_DIR to verified Kotlin 2.3.10/JUnit/coroutines/JSON/OkHttp/SnakeYAML jars}"
CP=$(printf '%s:' "$KOTLIN_TOOLS_DIR"/*.jar)
mkdir -p .local-tests
mapfile -t SRC < <(find app/src/main/java -name '*.kt' | grep -vE 'CommercePanel|SessionStore|LocalProfileStore|FileCommerceRecovery|MainActivity|AccountPanel|BrandTheme|DeliveryStore|UpdatePanel')
mapfile -t TEST < <(find app/src/test -name '*.kt')
java -cp "$CP" org.jetbrains.kotlin.cli.jvm.K2JVMCompiler -no-stdlib -no-reflect -jvm-target 17 \
  -classpath "$CP" -d .local-tests/all-tests.jar "${SRC[@]}" "${TEST[@]}"
CLASSES=$(python3 - <<'PY'
import zipfile
with zipfile.ZipFile('.local-tests/all-tests.jar') as z:
    names=[n[:-6].replace('/','.') for n in z.namelist() if n.endswith(('Test.class','Tests.class')) and '$' not in n]
assert names, 'No JUnit classes discovered'
print(' '.join(names))
PY
)
# Deliberate word splitting: names are validated JVM class identifiers.
java -cp ".local-tests/all-tests.jar:app/src/test/resources:native/bridge/testdata:$CP" org.junit.runner.JUnitCore $CLASSES | tee .local-tests/all-tests.log
