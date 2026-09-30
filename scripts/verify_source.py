#!/usr/bin/env python3
"""M0 source/package checks, not Android compilation or a complete security audit."""
from __future__ import annotations
import hashlib
from pathlib import Path
import xml.etree.ElementTree as ET

ROOT = Path(__file__).resolve().parents[1]
EXPECTED_JAR = '55243ef57851f12b070ad14f7f5bb8302daceeebc5bce5ece5fa6edb23e1145c'
EXPECTED_DIST = '2ab2958f2a1e51120c326cad6f385153bb11ee93b3c216c5fccebfdfbb7ec6cb'
REQUIRED = (
    'gradlew', 'gradlew.bat', 'build.sh', 'build.ps1',
    'scripts/bootstrap-wrapper.sh', 'scripts/bootstrap-wrapper.ps1',
    'gradle/wrapper/gradle-wrapper.properties', 'gradle/wrapper/gradle-wrapper.jar',
    '.github/workflows/android.yml',
    'app/src/main/java/com/gccwebos/android/core/ConnectionCoordinator.kt',
    'app/src/test/java/com/gccwebos/android/core/ConnectionCases.kt',
    'app/src/test/java/com/gccwebos/android/core/ConnectionCoordinatorTest.kt',
    'app/src/debug/java/com/gccwebos/android/DebugBuildPanel.kt',
    'app/src/release/java/com/gccwebos/android/DebugBuildPanel.kt',
)
for name in REQUIRED:
    p = ROOT / name
    if not p.is_file() or p.stat().st_size == 0:
        raise SystemExit(f'Missing or empty source file: {name}')
props = (ROOT / 'gradle/wrapper/gradle-wrapper.properties').read_text()
assert 'gradle-9.4.1-bin.zip' in props and f'distributionSha256Sum={EXPECTED_DIST}' in props
for name in ('scripts/bootstrap-wrapper.sh', 'scripts/bootstrap-wrapper.ps1'):
    assert EXPECTED_JAR in (ROOT / name).read_text()
jar = ROOT / 'gradle/wrapper/gradle-wrapper.jar'
assert hashlib.sha256(jar.read_bytes()).hexdigest() == EXPECTED_JAR, 'Untrusted wrapper JAR'
print('Wrapper JAR: bundled and checksum verified')
for path in (ROOT / 'app/src').rglob('*.xml'):
    ET.parse(path)
manifest = ET.parse(ROOT / 'app/src/main/AndroidManifest.xml').getroot()
android = '{http://schemas.android.com/apk/res/android}'
assert manifest.find('application').get(android + 'usesCleartextTraffic') == 'false'
assert not manifest.findall('uses-permission'), 'M0 has no real network/VPN permissions; re-review on M1 integration'
assert not manifest.findall('.//service'), 'M0 must not expose a fake VpnService'
for directory in ('main', 'release'):
    for path in (ROOT / f'app/src/{directory}').rglob('*.kt'):
        assert 'class LocalDiagnosticGateways' not in path.read_text(), 'Debug simulator leaked into production source'
root_build = (ROOT / 'build.gradle.kts').read_text()
assert 'version "9.2.0"' in root_build and 'version "2.3.10"' in root_build
assert 'id("org.jetbrains.kotlin.android")' not in root_build
# Preserve the SDK alignment gate introduced by the separately merged PR #2.
app_build = (ROOT / 'app/build.gradle.kts').read_text()
workflow = (ROOT / '.github/workflows/android.yml').read_text()
assert 'compileSdk = 36' in app_build and 'targetSdk = 36' in app_build
assert '"platforms;android-36"' in workflow, 'CI SDK must match compileSdk'
print('PASS M0 source presence, checksums, XML, compiler declarations and debug separation')
print('NOT RUN by this check: Gradle, Android Lint, APK signing, device or backend tests')
