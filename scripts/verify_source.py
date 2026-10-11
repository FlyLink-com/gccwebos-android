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
assert {p.get(android + 'name') for p in manifest.findall('uses-permission')} == {
    'android.permission.INTERNET', 'android.permission.ACCESS_NETWORK_STATE',
    'android.permission.CAMERA',
    'android.permission.FOREGROUND_SERVICE', 'android.permission.FOREGROUND_SERVICE_SYSTEM_EXEMPTED',
    'android.permission.POST_NOTIFICATIONS', 'android.permission.RECEIVE_BOOT_COMPLETED'}
# The only receiver re-arms/handles the daily renewal alarm; it is never exported.
receivers = {r.get(android + 'name'): r for r in manifest.findall('.//receiver')}
assert set(receivers) == {'.renewal.RenewalReminderReceiver'}
assert all(r.get(android + 'exported') == 'false' for r in receivers.values())
camera = manifest.find("uses-feature[@android:name='android.hardware.camera.any']",
                       {'android': 'http://schemas.android.com/apk/res/android'})
assert camera is not None and camera.get(android + 'required') == 'false'
activities = {a.get(android + 'name'): a for a in manifest.findall('.//activity')}
scanner = activities['.manualimport.ManualQrScannerActivity']
assert scanner.get(android + 'exported') == 'false'
assert scanner.get(android + 'excludeFromRecents') == 'true'
assert activities['com.journeyapps.barcodescanner.CaptureActivity'].get(
    '{http://schemas.android.com/tools}node') == 'remove'
# The in-app payment page is private, and package visibility is limited to the Alipay app and its two link schemes.
payment = activities['.payment.PaymentActivity']
assert payment.get(android + 'exported') == 'false' and payment.find('intent-filter') is None
queries = manifest.find('queries')
assert queries is not None and [p.get(android + 'name') for p in queries.findall('package')] == ['com.eg.android.AlipayGphone']
assert sorted(i.find('data').get(android + 'scheme') for i in queries.findall('intent')) == ['alipay', 'alipays']
assert len(list(queries)) == 3, 'Only the Alipay package and its two schemes may be queried'
assert 'android.permission.QUERY_ALL_PACKAGES' not in {p.get(android + 'name') for p in manifest.findall('uses-permission')}
assert not any('payment.PaymentTestActivity' in n or 'FakeAlipayActivity' in n for n in activities), 'Debug payment test seam leaked into the main manifest'
payment_source = (ROOT / 'app/src/main/java/com/gccwebos/android/payment/PaymentActivity.kt').read_text()
for required in ('FLAG_SECURE', 'handler.cancel()', 'MIXED_CONTENT_NEVER_ALLOW', 'safeBrowsingEnabled = true',
                 'allowFileAccess = false', 'allowContentAccess = false'):
    assert required in payment_source, f'Payment page must keep: {required}'
for forbidden in ('addJavascriptInterface', 'handler.proceed', 'setWebContentsDebuggingEnabled', 'domStorageEnabled = true'):
    assert forbidden not in payment_source, f'Payment page must not use: {forbidden}'
services = {s.get(android + 'name'): s for s in manifest.findall('.//service')}
assert set(services) == {'.vpn.ConnectionOwnerService', '.vpn.ProductionVpnService'}
assert all(s.get(android + 'exported') == 'false' for s in services.values())
vpn = services['.vpn.ProductionVpnService']
assert vpn.get(android + 'permission') == 'android.permission.BIND_VPN_SERVICE'
assert vpn.get(android + 'process') == ':vpn'
assert vpn.get(android + 'foregroundServiceType') == 'systemExempted'
assert vpn.find('meta-data').get(android + 'value') == 'false'
for directory in ('main', 'release'):
    for path in (ROOT / f'app/src/{directory}').rglob('*.kt'):
        assert 'class LocalDiagnosticGateways' not in path.read_text(), 'Debug simulator leaked into production source'
# Lab components remain opt-in debug-only; production JNI packaging has its own boundary.
lab_path = ROOT / 'app/src/debug/AndroidManifest.xml'
if lab_path.exists():
    lab = ET.parse(lab_path).getroot()
    assert {p.get(android + 'name') for p in lab.findall('uses-permission')} == {
        'android.permission.ACCESS_NETWORK_STATE', 'android.permission.FOREGROUND_SERVICE',
        'android.permission.FOREGROUND_SERVICE_SPECIAL_USE', 'android.permission.POST_NOTIFICATIONS',
        '${applicationId}.permission.VPN_LAB_STATE'}
    service = lab.find('.//service')
    assert service is not None and service.get(android + 'name') == '.vpn.LabVpnService'
    assert service.get(android + 'permission') == 'android.permission.BIND_VPN_SERVICE'
    assert service.get(android + 'exported') == 'false'
    assert service.get(android + 'process') == ':vpn'
    assert service.get(android + 'enabled') == '${nativeVpnLabEnabled}'
    assert service.get(android + 'foregroundServiceType') == 'specialUse'
    permission = lab.find('permission')
    assert permission.get(android + 'protectionLevel') == 'signature'
    lab_source = (ROOT / 'app/src/debug/java/com/gccwebos/android/vpn/LabVpnService.kt').read_text()
    assert '.addAllowedApplication(packageName)' in lab_source
    assert '.addRoute("0.0.0.0", 0)' not in lab_source and '.addRoute("::", 0)' not in lab_source
    assert '.addRoute("198.18.0.10", 32)' in lab_source and '.addRoute("172.19.0.2", 32)' in lab_source
    assert 'BuildConfig.NATIVE_TUN_ACCEPTANCE' in lab_source
    assert '.allowBypass(' not in lab_source and '.allowFamily(' not in lab_source
    assert 'START_NOT_STICKY' in lab_source and 'stopSession()' in lab_source
    assert not (ROOT / 'app/src/main/jniLibs').exists(), 'Native lab binaries must not enter release source set'
root_build = (ROOT / 'build.gradle.kts').read_text()
assert 'version "9.2.0"' in root_build and 'version "2.3.10"' in root_build
assert 'id("org.jetbrains.kotlin.android")' not in root_build
# Preserve the SDK alignment gate introduced by the separately merged PR #2.
app_build = (ROOT / 'app/build.gradle.kts').read_text()
from verify_release_policy import verify_configuration
verify_configuration(app_build)
workflow = (ROOT / '.github/workflows/android.yml').read_text()
assert 'compileSdk = 36' in app_build and 'targetSdk = 36' in app_build
assert '"platforms;android-36"' in workflow, 'CI SDK must match compileSdk'
print('PASS M1/M2 source presence, checksums, XML, compiler declarations and debug separation')
print('NOT RUN by this check: Gradle, Android Lint, APK signing, device or backend tests')
