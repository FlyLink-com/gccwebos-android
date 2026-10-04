#!/usr/bin/env bash
# Diagnostic JVM fallback. Does not replace pinned Gradle, Lint or instrumentation.
set -euo pipefail
cd -- "$(dirname -- "$0")/.."
: "${KOTLIN_TOOLS_DIR:?Set KOTLIN_TOOLS_DIR to verified Kotlin 2.3.10/JUnit/coroutines/JSON/OkHttp/SnakeYAML jars}"
CP=$(printf '%s:' "$KOTLIN_TOOLS_DIR"/*.jar)
# A Windows JVM needs a native path list even when relative resource paths are mixed
# with Git Bash absolute tool paths. Do not rely on implicit MSYS argument conversion.
native_classpath() {
  if command -v cygpath >/dev/null; then cygpath -wp "$1"
  else printf '%s' "$1"; fi
}
mkdir -p .local-tests
# Explicit pure-JVM boundary. Android/Compose controllers and their tests use Gradle;
# do not compile them against diagnostic stubs or silently include new UI files.
BASE=app/src/main/java/com/gccwebos/android
SRC=(
  "$BASE"/commerce/{CommerceJson,CommerceModels,CommerceRepository}.kt
  "$BASE"/core/{ConnectionCoordinator,LocalConnectionContract}.kt
  "$BASE"/design/{ResourceGuidance,ResourcePresentation}.kt
  "$BASE"/portal/{AccountUsageModels,ConfigAddressPreparer,DeliveryPersistence,FreshConnectionConfig,LegacyRecoveryJournal,LocalSubscription,PortalJson,PortalModels,PortalRepository,PortalTransport,ReferralModels,RegistrationGateway,RegistrationModels,SessionPersistence,SubscriptionImport}.kt
  "$BASE"/update/{ReleaseManifest,UpdateController,UpdateRepository}.kt
  "$BASE"/vpn/{LabPermissionAttempt,MihomoNative,NativeVpnSession,SafeProxyConfig}.kt
)
BASE=app/src/test/java/com/gccwebos/android
TEST=(
  "$BASE"/commerce/{CommerceFixtures,CommerceHistoryDetailTest,CommerceJsonTest,CommerceRepositoryTest,MobileParityContractTest}.kt
  "$BASE"/core/{ConnectionCases,ConnectionCoordinatorTest}.kt
  "$BASE"/design/{ResourceGuidanceTest,ResourcePresentationTest}.kt
  "$BASE"/portal/{LocalConnectionLifecycleTest,LocalSubscriptionTest,PortalRepositoryTest,ReferralContractTest,SessionPersistenceTest,SubscriptionImportTest}.kt
  "$BASE"/update/UpdateTests.kt
  "$BASE"/vpn/{ProtocolContractTest,SyntheticSsFixtureTest,VpnSafetyTests}.kt
  app/src/acceptanceFixtures/java/com/gccwebos/android/vpn/SyntheticSsFixture.kt
)
for path in "${SRC[@]}" "${TEST[@]}"; do
  [ -f "$path" ] || { printf 'Missing declared JVM source: %s\n' "$path" >&2; exit 1; }
done
printf '%s\n' "${SRC[@]}" > .local-tests/domain-sources.txt
printf '%s\n' "${TEST[@]}" > .local-tests/domain-test-sources.txt
printf 'Pure JVM scope: %s main sources, %s test/fixture sources; Android/Compose controller tests require Gradle.\n' "${#SRC[@]}" "${#TEST[@]}"
PYTHON=${PYTHON:-python3}
command -v "$PYTHON" >/dev/null || { printf 'Set PYTHON to an existing Python 3 executable.\n' >&2; exit 1; }
java -cp "$(native_classpath "$CP")" org.jetbrains.kotlin.cli.jvm.K2JVMCompiler -no-stdlib -no-reflect -jvm-target 17 \
  -classpath "$(native_classpath "$CP")" -d .local-tests/all-tests.jar "${SRC[@]}" "${TEST[@]}"
CLASSES=$("$PYTHON" - <<'PY'
import zipfile
with zipfile.ZipFile('.local-tests/all-tests.jar') as z:
    names=[n[:-6].replace('/','.') for n in z.namelist() if n.endswith(('Test.class','Tests.class')) and '$' not in n]
assert names, 'No JUnit classes discovered'
print(' '.join(names))
PY
)
# Deliberate word splitting: names are validated JVM class identifiers.
java -cp "$(native_classpath ".local-tests/all-tests.jar:app/src/test/resources:native/bridge/testdata:$CP")" org.junit.runner.JUnitCore $CLASSES | tee .local-tests/all-tests.log
