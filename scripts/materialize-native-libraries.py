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

SOURCE = 'b3bb94fe73aa39e03fd25e4b7fc7ef4ba5ab7987'
ARTIFACT_ID = 11114730030
RUN_ID = 36746157076
ZIP_SHA = 'b50311e89d1a2a8a438f3ef4c6ea85bb7d2b3fdc8019d6356249c5f623890efc'
NATIVE_TREE = '1d19e7c69ec3e0b8b9450a404c80d97d17831f25'
LIBRARIES = {
    'arm64-v8a': 'efe638604b4f54f76fbba85649027f6b72f67b0dab5d5c18d248a1de7e5920bb',
    'x86_64': 'c2099755603a7784f1c935fc8151b418a5f6859dc8427daa65fd3021add8fe2b',
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
    assert metadata['size_in_bytes'] == 105666028 and metadata['digest'] == 'sha256:' + ZIP_SHA
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
