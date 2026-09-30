#!/usr/bin/env python3
"""Install only the exact ordinary APK pair from the failed API36 source run."""
import hashlib
import json
from pathlib import Path
import shutil
import subprocess
import sys
from zipfile import ZipFile

SOURCE = '556cae8bd4f470ab5fbc38e6be179554ac25b43c'
ARTIFACT_ID = 11115460761
RUN_ID = 36751807464
ZIP_SIZE = 13976780
ZIP_SHA = '4f908db66a569b17c0e14966ce3cda840d1585cc050cedd253bfcfea41674973'
CERT_SHA = '11a4d583ca219d6081c248beaf1f92312a36b30767c699a7181af985c9e3e6c7'
APK_HASHES = {
    'app/build/outputs/apk/debug/app-debug.apk': '0dd78c167abe817fb67fab659ba2f6c854b1cfcb35e87d40bb8938ff083f3b8e',
    'app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk': 'c03b7198521f0825bd58d1cb153c53289b3f8e3e2931902b675053b87496f0b9',
}
ROOT = Path(__file__).resolve().parents[1]
OUT = ROOT / '.build-output/runtime-api33'

def digest(data):
    return hashlib.sha256(data).hexdigest()

def git(ref):
    return subprocess.check_output(['git', 'rev-parse', ref], cwd=ROOT, text=True).strip()

def validate_archive(path, metadata):
    assert metadata['id'] == ARTIFACT_ID and metadata['expired'] is False
    assert metadata['size_in_bytes'] == ZIP_SIZE and path.stat().st_size == ZIP_SIZE
    assert metadata['digest'] == 'sha256:' + ZIP_SHA
    run = metadata['workflow_run']
    assert run['id'] == RUN_ID and run['head_sha'] == SOURCE
    assert run['repository_id'] == 1393547812
    assert digest(path.read_bytes()) == ZIP_SHA, 'Archive identity mismatch'
    with ZipFile(path) as archive:
        names = archive.namelist()
        assert len(names) == len(set(names)), 'Duplicate archive entries'
        manifest = json.loads(archive.read('.build-output/build-manifest.json'))
        assert manifest['revision'] == SOURCE
        assert manifest['native_engine_embedded'] is False
        assert manifest['native_libraries_sha256'] == {}
        assert manifest['production_or_store_release'] is False
        assert manifest['real_vpn'] == 'not_integrated'
        assert manifest['application_id'] == 'com.gccwebos.android.debug'
        assert manifest['version_code'] == 6 and manifest['version_name'] == '0.4.0-mobile-parity-debug'
        assert manifest['sha256'] == APK_HASHES['app/build/outputs/apk/debug/app-debug.apk']
        payloads = {name: archive.read(name) for name in APK_HASHES}
        for name, payload in payloads.items():
            assert digest(payload) == APK_HASHES[name], 'APK hash mismatch'
        return manifest, payloads

def main():
    archive_path, metadata_path = map(Path, sys.argv[1:3])
    metadata = json.loads(metadata_path.read_text())
    manifest, payloads = validate_archive(archive_path, metadata)
    # The harness is new, but every compiled input must retain the source object.
    paths = ['app', 'native', 'build.gradle.kts', 'settings.gradle.kts', 'gradle.properties', 'gradle']
    identities = {p: git(SOURCE + ':' + p) for p in paths}
    assert all(git('HEAD:' + p) == value for p, value in identities.items()), 'Compiled inputs differ'
    OUT.mkdir(parents=True, exist_ok=True)
    for name, payload in payloads.items():
        target = ROOT / name
        target.parent.mkdir(parents=True, exist_ok=True)
        target.write_bytes(payload)
        with ZipFile(target) as package:
            assert not any(n.endswith('/libgccmihomo.so') for n in package.namelist()), 'Ordinary APK unexpectedly embeds Mihomo'
    receipt = {
        'provenance_verified': True, 'binary_source_commit': SOURCE, 'harness_commit': git('HEAD'),
        'source_workflow_run': RUN_ID, 'source_artifact_id': ARTIFACT_ID,
        'source_archive_sha256': ZIP_SHA, 'apk_sha256': APK_HASHES,
        'binary_input_git_objects': identities, 'certificate_sha256': CERT_SHA,
        'application_id': manifest['application_id'], 'version_code': 6,
        'version_name': manifest['version_name'], 'native_engine_embedded': False,
        'runtime_api_under_test': 33, 'api36_status': 'failed_preinstall_system_anr_run_36751807464_unchanged',
        'physical_device_tested': False, 'real_tun_traffic_tested': False,
        'runtime_status': 'not_run_yet',
    }
    (OUT / 'provenance.json').write_text(json.dumps(receipt, indent=2) + '\n')
    (OUT / 'source-build-manifest.json').write_text(json.dumps(manifest, indent=2) + '\n')
    shutil.copyfile(metadata_path, OUT / 'source-artifact-metadata.json')
    print('PASS: exact ordinary archive, both APK hashes, compiled input identity; no rebuild or resign')

if __name__ == '__main__':
    main()
