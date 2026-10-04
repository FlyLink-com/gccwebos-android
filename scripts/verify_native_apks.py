#!/usr/bin/env python3
"""Verify production JNI packaging/alignment; exclude debug lab components from release."""
from pathlib import Path
import struct
import zipfile
root = Path(__file__).resolve().parents[1]
def verify_libraries(z):
    for abi in ('arm64-v8a', 'x86_64'):
        data = z.read(f'lib/{abi}/libgccmihomo.so')
        assert data[:6] == b'\x7fELF\x02\x01', 'Expected 64-bit little endian ELF'
        header = struct.unpack_from('<16sHHIQQQIHHHHHH', data)
        _, _, machine, _, _, phoff, _, _, _, phsize, phnum, *_ = header
        assert machine == (183 if abi == 'arm64-v8a' else 62)
        loads = 0
        for n in range(phnum):
            kind, flags, offset, vaddr, paddr, filesz, memsz, align = struct.unpack_from('<IIQQQQQQ', data, phoff + n * phsize)
            if kind == 1:
                loads += 1
                assert align >= 16384 and vaddr % 16384 == offset % 16384, 'ELF LOAD lacks 16KiB alignment'
        assert loads > 0
        print(f'PASS {abi}: {len(data)} bytes, {loads} LOAD segments aligned at >=16KiB')
    for notice in ('Mihomo-GPL-3.0.txt', 'CMFA-GPL-3.0.txt'):
        assert len(z.read('assets/' + notice)) > 1000, 'Native license notice missing'
    manifest = z.read('AndroidManifest.xml')
    assert b'ProductionVpnService' in manifest or 'ProductionVpnService'.encode('utf-16le') in manifest
debug = root / 'app/build/outputs/apk/debug/app-debug.apk'
with zipfile.ZipFile(debug) as z:
    verify_libraries(z)
release_apks = list((root / 'app/build/outputs/apk/release').glob('*.apk'))
assert release_apks, 'Release APK must be compiled separately'
for apk in release_apks:
    with zipfile.ZipFile(apk) as z:
        verify_libraries(z)
        # Android manifests are binary XML but ASCII class names are UTF-8 pool entries in these builds.
        manifest = z.read('AndroidManifest.xml')
        for marker in ('LabVpnService', 'NativeVpnLabActivity', 'FOREGROUND_SERVICE_SPECIAL_USE'):
            assert marker.encode() not in manifest and marker.encode('utf-16le') not in manifest, marker
    print(f'PASS production JNI and release lab exclusion: {apk.name}')
