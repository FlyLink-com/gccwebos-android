#!/usr/bin/env python3
"""Run only after successful Gradle tasks. Never create a success manifest without a real APK."""
from __future__ import annotations
from datetime import datetime, timezone
import hashlib
import json
import os
import re
import subprocess
from pathlib import Path
import xml.etree.ElementTree as ET
from zipfile import ZipFile

ROOT = Path(__file__).resolve().parents[1]
APK = ROOT / 'app/build/outputs/apk/debug/app-debug.apk'
OUT = ROOT / '.build-output'
if not APK.is_file() or APK.stat().st_size == 0:
    raise SystemExit('No Debug APK exists. No success manifest was created.')
native_libraries = {}
with ZipFile(APK) as archive:
    if archive.testzip() is not None:
        raise SystemExit('Corrupt APK')
    for name in archive.namelist():
        if re.fullmatch(r'lib/(arm64-v8a|x86_64)/libgccmihomo\.so', name):
            digest = hashlib.sha256()
            with archive.open(name) as library:
                for chunk in iter(lambda: library.read(1024 * 1024), b''):
                    digest.update(chunk)
            native_libraries[name] = digest.hexdigest()
    for required in ('AndroidManifest.xml', 'classes.dex', 'resources.arsc'):
        if required not in archive.namelist():
            raise SystemExit(f'Incomplete APK: {required} missing')
xml_files = list((ROOT / 'app/build/test-results/testDebugUnitTest').glob('TEST-*.xml'))
if not xml_files:
    raise SystemExit('No Gradle unit test XML. Offline diagnostics do not satisfy this gate.')
counts = dict(tests=0, failures=0, errors=0, skipped=0)
for path in xml_files:
    suite = ET.parse(path).getroot()
    for key in counts:
        counts[key] += int(suite.get(key, '0'))
if counts['tests'] < 123 or any(counts[key] for key in ('failures', 'errors', 'skipped')):
    raise SystemExit(f'Unit test gate not satisfied: {counts}')
lint_path = ROOT / 'app/build/reports/lint-results-debug.xml'
if not lint_path.is_file():
    raise SystemExit('No Android Lint XML')
issues = ET.parse(lint_path).getroot().findall('issue')
if any(issue.get('severity', '').lower() in ('fatal', 'error') for issue in issues):
    raise SystemExit('Android Lint errors remain')
try:
    revision = subprocess.check_output(['git', 'rev-parse', 'HEAD'], cwd=ROOT, text=True).strip()
except (OSError, subprocess.CalledProcessError):
    revision = None
# Read package identity from the actual compiled APK, not a stale source constant.
sdk = os.environ.get('ANDROID_HOME') or os.environ.get('ANDROID_SDK_ROOT')
if not sdk:
    raise SystemExit('ANDROID_HOME or ANDROID_SDK_ROOT is required to verify APK identity')
aapt = Path(sdk) / 'build-tools/36.0.0/aapt2'
badging = subprocess.check_output([str(aapt), 'dump', 'badging', str(APK)], text=True)
package = re.search(r"^package: name='([^']+)' versionCode='([^']+)' versionName='([^']+)'", badging, re.MULTILINE)
if package is None or package.group(1) != 'com.gccwebos.android.debug':
    raise SystemExit('Unexpected or missing compiled APK identity')
OUT.mkdir(exist_ok=True)
manifest = {
    'created_at_utc': datetime.now(timezone.utc).isoformat(),
    'revision': revision,
    'artifact': str(APK.relative_to(ROOT)),
    'sha256': hashlib.sha256(APK.read_bytes()).hexdigest(),
    'bytes': APK.stat().st_size,
    'variant': 'debug',
    'application_id': package.group(1),
    'version_code': int(package.group(2)),
    'version_name': package.group(3),
    'unit_tests': counts,
    'lint_error_count': 0,
    'lint_warning_count': sum(i.get('severity', '').lower() == 'warning' for i in issues),
    'device_tests': 'not_run_by_this_collector',
    'native_engine_embedded': bool(native_libraries),
    'native_libraries_sha256': native_libraries,
    'real_vpn': 'debug_lab_only_runtime_unverified' if native_libraries else 'not_integrated',
    'production_or_store_release': False,
}
(OUT / 'build-manifest.json').write_text(json.dumps(manifest, indent=2, ensure_ascii=False) + '\n')
print(json.dumps(manifest, indent=2, ensure_ascii=False))
