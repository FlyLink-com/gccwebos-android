#!/usr/bin/env python3
"""Offline release source and native payload gates; never sign, install or download."""
from __future__ import annotations

import argparse
import hashlib
from pathlib import Path
import re
import zipfile

ROOT = Path(__file__).resolve().parents[1]
LIBRARIES = {
    "arm64-v8a": "2737f327ecdef298907c6d10913eaf71196d6772b7ab2bb10c0ab2d9b1f37463",
    "x86_64": "6e8ab0ed35e94cf128ce53523332bd3b50c9e10cb03ac768bd3a55c91ee252c8",
}
NOTICES = {
    name: "3972dc9744f6499f0f9b2dbf76696f2ae7ad8af9b23dde66d6af86c9dfb36986"
    for name in ("Mihomo-GPL-3.0.txt", "CMFA-GPL-3.0.txt")
}
FLAGS = {
    "CONNECTION_ENDPOINT_HOSTNAME": "true",
    "CONNECTION_SYNTHETIC_ACCEPTANCE": "false",
    "STORAGE_SYNTHETIC_ACCEPTANCE": "false",
    "NATIVE_VPN_LAB": "false",
    "NATIVE_TUN_ACCEPTANCE": "false",
}


def verify_configuration(source: str) -> None:
    """Deliberately narrow checks for this project's single release build type."""
    assert re.search(r'applicationId\s*=\s*"com\.gccwebos\.android"', source), "Official package changed"
    assert re.search(r'versionCode\s*=\s*9\b', source), "Stable versionCode must be 9"
    assert re.search(r'versionName\s*=\s*"0\.4\.2"', source), "Stable versionName must be 0.4.2"
    # The release block must stay unconditional; nested property-controlled gates are rejected.
    match = re.search(r'\brelease\s*\{([^{}]*)\}', source)
    assert match, "Missing unconditional release build type"
    release = match.group(1)
    fields = re.findall(r'buildConfigField\("boolean",\s*"([A-Z_]+)",\s*"(true|false)"\)', release)
    assert len(fields) == len(FLAGS) and dict(fields) == FLAGS, "Release flags differ from the stable policy"
    assert re.search(r'isDebuggable\s*=\s*false\b', release), "Release must not be debuggable"
    assert not re.search(r'\b(signingConfig|applicationIdSuffix|versionNameSuffix)\s*=', release), "Release identity/signing must not inherit a debug override"
    keep = list(re.finditer(r'packaging\.jniLibs\.keepDebugSymbols\s*\+=\s*"([^"]+)"', source))
    assert len(keep) == 1 and keep[0].group(1) == '**/libgccmihomo.so', "Preserve only the already-verified VPN native library bytes"
    assert source.count('keepDebugSymbols') == 1, "Additional native stripping exceptions are not allowed"
    before_keep = re.sub(r'//[^\n]*|"(?:\\.|[^"\\])*"', '', source[:keep[0].start()])
    assert before_keep.count('{') - before_keep.count('}') == 1, "Native byte preservation must apply to every Android variant"
    gate = re.search(r'val verifiedConnectionLibraries = tasks\.register\("verifyConnectionLibraries"\)\s*\{', source)
    assert gate, "Native verification must be registered independently of candidate properties"
    before_gate = re.sub(r'//[^\n]*|"(?:\\.|[^"\\])*"', '', source[:gate.start()])
    assert before_gate.count('{') == before_gate.count('}'), "Native verification registration is conditional"
    assert re.search(r'tasks\.matching \{ it\.name == "preReleaseBuild" \}\.configureEach\s*\{\s*dependsOn\(verifiedConnectionLibraries\)\s*\}', source), "Release does not depend on native verification"
    assert 'tasks.named("preBuild")' not in source, "Ordinary debug must not require native inputs"
    for expected in set(LIBRARIES.values()) | set(NOTICES.values()):
        assert expected in source, "Gradle native identity policy differs from the release verifier"


def verify_payload(read, libraries=LIBRARIES, notices=NOTICES, *, apk: bool) -> None:
    """Verify exact packaged/input bytes, including both ABI and license boundaries."""
    for abi, expected in libraries.items():
        path = f'lib/{abi}/libgccmihomo.so' if apk else f'native/build/jniLibs/{abi}/libgccmihomo.so'
        assert hashlib.sha256(read(path)).hexdigest() == expected, f'Native library identity mismatch: {abi}'
    for name, expected in notices.items():
        path = f'assets/{name}' if apk else f'native/notices/{name}'
        # Match Git's LF notice bytes while allowing Windows checkout line endings.
        assert hashlib.sha256(read(path).replace(b'\r\n', b'\n')).hexdigest() == expected, f'Native license identity mismatch: {name}'


def verify_apk(path: Path, libraries=LIBRARIES, notices=NOTICES) -> None:
    with zipfile.ZipFile(path) as archive:
        assert len(archive.namelist()) == len(set(archive.namelist())), "Duplicate APK entry"
        assert archive.testzip() is None, "APK CRC check failed"
        verify_payload(archive.read, libraries, notices, apk=True)
        manifest = archive.read('AndroidManifest.xml')
        for marker in ('LabVpnService', 'NativeVpnLabActivity', 'FOREGROUND_SERVICE_SPECIAL_USE'):
            assert marker.encode() not in manifest and marker.encode('utf-16le') not in manifest, "Debug lab component in release"
        assert any('ProductionVpnService'.encode(encoding) in manifest for encoding in ('utf-8', 'utf-16le')), "Production VPN service missing"


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--inputs', action='store_true', help='Also require the materialized native inputs')
    parser.add_argument('--apk', type=Path, help='Also verify release native payloads and lab exclusion')
    args = parser.parse_args()
    verify_configuration((ROOT / 'app/build.gradle.kts').read_text(encoding='utf-8'))
    if args.inputs:
        verify_payload(lambda name: (ROOT / name).read_bytes(), apk=False)
    if args.apk:
        verify_apk(args.apk)
    print('PASS stable build policy' + (' and native inputs' if args.inputs else '') + (' and release native payload' if args.apk else ''))
    print('Separate gates still required: compilation, artifact identity/flags, signing, alignment and device acceptance')


if __name__ == '__main__':
    main()
