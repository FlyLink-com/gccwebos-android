#!/usr/bin/env python3
"""Validate immutable binary provenance; materialize only two known APK entries.
This does not build code or turn the failed API36 source run into a successful run.
"""
import hashlib
import json
import os
from pathlib import Path
import shutil
import struct
import subprocess
import sys
from zipfile import ZipFile

SOURCE = '6166f9b1ee93847eb7189e7c17fedbefa903cd87'
MODE = os.environ['BINARY_MODE']
assert MODE in ('native', 'ordinary')
ARTIFACT_ID = 11083682500
RUN_ID = 36683677503
ZIP_SHA = 'a9288d269943cbb710afe43542eb90e66868ddf1d06b3a035fdc8e614b93aa89'
NATIVE_TREE = 'c43b045cd5e2b7f09df079d25283b08bd3294465'
APK_HASHES = {
    'app/build/outputs/apk/debug/app-debug.apk': 'bf4bb4bd94b62a3ac792564d3b8a2b3503d281eb13d4c1b9fec8d7c0db567bb6',
    'app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk': '7783e46772f51a087bb21b8d3aec54a357722a57e11cf7fb3b5601014dc730ec',
}
PACKAGED_LIBS = {
    'lib/arm64-v8a/libgccmihomo.so': 'b3b97805549e8d449fe1fd36f477891708270262d118ff97ace3a406eae47ffe',
    'lib/x86_64/libgccmihomo.so': '47afaa65a8e2294027c12b75f4b9860e6fb4813611b5f21b15c1e0da92675e9b',
}
RAW_LIBS = {
    'native/build/jniLibs/arm64-v8a/libgccmihomo.so': 'f51962ce7d89df3ab75d54ceea716b88e12a7837f0323b06aaf6c0cbee34096c',
    'native/build/jniLibs/x86_64/libgccmihomo.so': '0a2a1fb4bd965e460da32bd35297fb6fae7d7319293a9647e55c8f12b6869a9b',
}
if MODE == 'ordinary':
    ARTIFACT_ID = 11083353155
    RUN_ID = 36683677539
    ZIP_SHA = '1f260675ac0c1192c402010523a262021d524b461a7623d9c7344f2578e50539'
    APK_HASHES = {
        'app/build/outputs/apk/debug/app-debug.apk': 'b842e937820c792ba35f17871b0d2dfd5eafdd658201dfa3ac5f5185709b40c2',
        'app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk': 'b16823001c9fda4930b0f66914c3bd31733e0465c2a80c4be1ecfc478b6830b0',
    }
    PACKAGED_LIBS = {}
    RAW_LIBS = {}
ROOT = Path(__file__).resolve().parents[1]
OUT = ROOT / '.build-output/runtime-api33'
OUT.mkdir(parents=True, exist_ok=True)

def digest(file):
    h = hashlib.sha256()
    for chunk in iter(lambda: file.read(1024 * 1024), b''):
        h.update(chunk)
    return h.hexdigest()

def git(ref):
    return subprocess.check_output(['git', 'rev-parse', ref], cwd=ROOT, text=True).strip()

def main():
    archive_path, metadata_path = map(Path, sys.argv[1:3])
    metadata = json.loads(metadata_path.read_text())
    assert metadata['id'] == ARTIFACT_ID and not metadata['expired']
    assert metadata['size_in_bytes'] == (102942966 if MODE == 'native' else 12776761)
    assert metadata['digest'] == 'sha256:' + ZIP_SHA
    assert metadata['workflow_run']['id'] == RUN_ID
    assert metadata['workflow_run']['head_sha'] == SOURCE
    assert metadata['workflow_run']['repository_id'] == 1393547812
    with archive_path.open('rb') as f:
        assert digest(f) == ZIP_SHA, 'Immutable artifact archive mismatch'
    # The harness may change; compiled app/native/build inputs must not silently change.
    equal_paths = ['app', 'native', 'build.gradle.kts', 'settings.gradle.kts', 'gradle.properties', 'gradle',
                   'scripts/verify_native_instrumentation.py']
    identities = {p: git(SOURCE + ':' + p) for p in equal_paths}
    assert identities['native'] == NATIVE_TREE
    assert all(git('HEAD:' + p) == value for p, value in identities.items()), 'Binary inputs changed; a fresh build is required'
    with ZipFile(archive_path) as archive:
        assert len(archive.namelist()) == len(set(archive.namelist())), 'Duplicate ZIP entries'
        if MODE == 'native':
            assert archive.read('.build-output/native/source-commit.txt').decode().strip() == SOURCE
        prefix = '.build-output/native/' if MODE == 'native' else '.build-output/'
        manifest = json.loads(archive.read(prefix + 'build-manifest.json'))
        assert manifest['revision'] == SOURCE and manifest['native_engine_embedded'] is (MODE == 'native')
        assert manifest['application_id'] == 'com.gccwebos.android.debug'
        assert manifest['version_code'] == 3 and manifest['version_name'] == '0.2.0-m1-debug'
        assert manifest['native_libraries_sha256'] == PACKAGED_LIBS
        for path, expected in RAW_LIBS.items():
            with archive.open(path) as f:
                assert digest(f) == expected, 'Raw native library mismatch'
        for path, expected in APK_HASHES.items():
            target = ROOT / path
            target.parent.mkdir(parents=True, exist_ok=True)
            with archive.open(path) as source, target.open('wb') as target_file:
                shutil.copyfileobj(source, target_file)
            with target.open('rb') as f:
                assert digest(f) == expected, 'APK mismatch'
        # Preserve the original API36 failure evidence separately, never as API33 results.
        original = OUT / 'source-api36-failure'
        original.mkdir(exist_ok=True)
        for path in archive.namelist():
            if path.startswith('.build-output/device/') and path.endswith(('.png', '.log', '.xml')):
                relative = Path(path).relative_to('.build-output/device')
                assert '..' not in relative.parts and not relative.is_absolute()
                assert archive.getinfo(path).file_size < 16 * 1024 * 1024
                target = original / relative
                target.parent.mkdir(parents=True, exist_ok=True)
                target.write_bytes(archive.read(path))
        for name in ['build-manifest.json', 'apk-signature.txt', 'arm64-v8a-elf-program-headers.txt',
                     'x86_64-elf-program-headers.txt', 'libraries.sha256']:
            if prefix + name in archive.namelist():
                (OUT / ('source-' + name)).write_bytes(archive.read(prefix + name))
    apk = ROOT / 'app/build/outputs/apk/debug/app-debug.apk'
    with ZipFile(apk) as package:
        assert sorted(n for n in package.namelist() if n.endswith('/libgccmihomo.so')) == sorted(PACKAGED_LIBS)
        for path, expected in PACKAGED_LIBS.items():
            data = package.read(path)
            assert hashlib.sha256(data).hexdigest() == expected, 'Packaged JNI library mismatch'
            assert data[:6] == b'\x7fELF\x02\x01'
            h = struct.unpack_from('<16sHHIQQQIHHHHHH', data)
            assert h[2] == (183 if 'arm64-v8a' in path else 62)
            loads = 0
            for i in range(h[10]):
                entry = struct.unpack_from('<IIQQQQQQ', data, h[5] + i * h[9])
                if entry[0] == 1:
                    loads += 1
                    assert entry[7] >= 16384 and entry[3] % 16384 == entry[2] % 16384
            assert loads > 0
    receipt = {
        'binary_mode': MODE, 'provenance_verified': True, 'binary_source_commit': SOURCE, 'harness_commit': git('HEAD'),
        'source_workflow_run': RUN_ID, 'source_artifact_id': ARTIFACT_ID,
        'source_archive_sha256': ZIP_SHA, 'binary_input_git_objects': identities,
        'apk_sha256': APK_HASHES, 'packaged_library_sha256': PACKAGED_LIBS,
        'raw_library_sha256': RAW_LIBS,
        'application_id': manifest['application_id'], 'version_code': 3, 'version_name': '0.2.0-m1-debug',
        'runtime_api_under_test': 33, 'compile_sdk': 36, 'target_sdk': 36, 'min_sdk': 26,
        'api36_status': ('blocked_before_emulator_by_go_cache_cleanup' if MODE == 'native' else 'blocked_by_system_anr'),
        'physical_device_tested': False, 'real_tun_traffic_tested': False,
        'runtime_status': 'not_run_yet',
    }
    (OUT / 'provenance.json').write_text(json.dumps(receipt, indent=2) + '\n')
    shutil.copyfile(metadata_path, OUT / 'source-artifact-metadata.json')
    print('PASS immutable artifact, source identity, APK hashes, both raw/packaged ABI hashes and ELF alignment')

if __name__ == '__main__':
    main()
