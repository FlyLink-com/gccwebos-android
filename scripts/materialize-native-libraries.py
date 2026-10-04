#!/usr/bin/env python3
"""Reuse only source-identical JNI libraries; ALWAYS rebuild current app/test APKs.
Archive identity and native input identity are independent gates. No APK is extracted.
"""
import hashlib
import json
from pathlib import Path
import shutil
import subprocess
import sys
from zipfile import ZipFile

SOURCE = 'e498a1a55e7499d031d172265ee05673ef4201b3'
ARTIFACT_ID = 11301226820
RUN_ID = 37193652121
ZIP_SHA = '23f310b79a337988e500008f1362ec0552d82260ef2e1cb3c66d84b929d14138'
NATIVE_TREE = 'e61fa5df04e449748bba0be253b8bd8e4f802d65'
LIBRARIES = {
    'arm64-v8a': '0f9adb1e0b26e5add0d50419bf3b853853ec6dd01d9e620fd871e0c272e3cd25',
    'x86_64': '738a803bb322d8b5a13bccb2ee9766d454c0f7e3f25ccba07adce3239daebe70',
}
BOUNDARIES = ['native', 'app/src/main/java/com/gccwebos/android/vpn/SafeProxyConfig.kt',
              'app/src/main/java/com/gccwebos/android/vpn/MihomoNative.kt',
              'app/src/test/java/com/gccwebos/android/vpn/ProtocolContractTest.kt']
ROOT = Path(__file__).resolve().parents[1]
OUT = ROOT / '.build-output/native'

def git(ref):
    return subprocess.check_output(['git', 'rev-parse', ref], cwd=ROOT, text=True).strip()

def identities_match():
    try:
        return git(SOURCE + ':native') == NATIVE_TREE and all(git(SOURCE + ':' + p) == git('HEAD:' + p) for p in BOUNDARIES)
    except subprocess.CalledProcessError:
        return False  # Source object absent in a future checkout: rebuild, never guess provenance.

def digest(stream):
    value = hashlib.sha256()
    for chunk in iter(lambda: stream.read(1024 * 1024), b''):
        value.update(chunk)
    return value.hexdigest()

def verify_metadata(metadata):
    assert metadata['id'] == ARTIFACT_ID and not metadata['expired']
    assert metadata['size_in_bytes'] == 211787753 and metadata['digest'] == 'sha256:' + ZIP_SHA
    assert metadata['workflow_run']['id'] == RUN_ID and metadata['workflow_run']['head_sha'] == SOURCE
    assert metadata['workflow_run']['repository_id'] == 1393547812

def main():
    if sys.argv[1:] == ['--can-reuse']:
        if identities_match():
            print('Source-identical native inputs; pinned-library reuse is eligible')
            return 0
        print('Native/projector inputs changed; a fresh native build is required')
        return 3
    archive_path, metadata_path = map(Path, sys.argv[1:3])
    verify_metadata(json.loads(metadata_path.read_text()))
    assert identities_match(), 'Native/projector input identity changed'
    with archive_path.open('rb') as file:
        assert digest(file) == ZIP_SHA, 'Artifact archive digest mismatch'
    OUT.mkdir(parents=True, exist_ok=True)
    with ZipFile(archive_path) as archive:
        assert len(archive.namelist()) == len(set(archive.namelist())), 'Duplicate archive entry'
        assert archive.read('.build-output/native/source-commit.txt').decode().strip() == SOURCE
        packet_log = archive.read('.build-output/native/packet-tests.log')
        assert b'"case": "success", "result": "PASS"' in packet_log
        assert b'"case": "deny", "result": "PASS"' in packet_log
        # Validate BOTH libraries before writing either. Copy only these known entries, not APKs.
        for abi, expected in LIBRARIES.items():
            path = f'native/build/jniLibs/{abi}/libgccmihomo.so'
            assert 1_000_000 < archive.getinfo(path).file_size < 90_000_000
            with archive.open(path) as source:
                assert digest(source) == expected, 'JNI library digest mismatch'
        for abi in LIBRARIES:
            directory = ROOT / 'native/build/jniLibs' / abi
            directory.mkdir(parents=True, exist_ok=True)
            for name in ('libgccmihomo.so', 'elf-program-headers.txt', 'elf-dynamic.txt'):
                path = f'native/build/jniLibs/{abi}/{name}'
                with archive.open(path) as source, (directory / name).open('wb') as target:
                    shutil.copyfileobj(source, target)
        for path in archive.namelist():
            if path.startswith('native/notices/') and not path.endswith('/'):
                relative = Path(path)
                assert '..' not in relative.parts and archive.getinfo(path).file_size < 2_000_000
                target = ROOT / relative; target.parent.mkdir(parents=True, exist_ok=True)
                target.write_bytes(archive.read(path))
        prior = OUT / 'reused-source-evidence'; prior.mkdir(exist_ok=True)
        (prior / 'packet-tests.log').write_bytes(packet_log)
    receipt = {'mode': 'source-identical JNI reuse, fresh app/test APK build',
               'native_source_commit': SOURCE, 'application_source_commit': git('HEAD'),
               'native_tree': NATIVE_TREE, 'artifact_id': ARTIFACT_ID, 'artifact_sha256': ZIP_SHA,
               'input_git_objects': {p: git('HEAD:' + p) for p in BOUNDARIES},
               'raw_native_library_sha256': LIBRARIES,
               'prior_run_real_tun_passed': False}
    (OUT / 'native-reuse.json').write_text(json.dumps(receipt, indent=2) + '\n')
    print('PASS: exact native/projector inputs and both JNI hashes verified; no old APK reused')
    return 0

if __name__ == '__main__':
    sys.exit(main())
