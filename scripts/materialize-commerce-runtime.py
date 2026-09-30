#!/usr/bin/env python3
"""Verify the exact NEW ordinary commerce APK before offline Android13 execution."""
import hashlib, json, os, pathlib, subprocess, sys, zipfile
SOURCE = '56481eb470dcc460058f9e9782b403b1ebafd684'
ARTIFACT = 11089909601
ARCHIVE = '52111e2c4a740f9843199822dd9b81056fc5f77e34cd997eece4f4b3406a79b6'
HASHES = {
    'app/build/outputs/apk/debug/app-debug.apk': '4d741e1b6b451493ec63e54e698c28273ef0ef57a96307f9431d7d64a3b8d1ee',
    'app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk': '2ad67139014dd7f3c7a42ef470d6720132afc3a0e9f6d1ca6cce84578dfa26a2',
}
archive, metadata = map(pathlib.Path, sys.argv[1:])
meta = json.loads(metadata.read_text())
assert meta['id'] == ARTIFACT and not meta['expired']
assert meta['workflow_run']['head_sha'] == SOURCE
assert meta['digest'] == 'sha256:' + ARCHIVE
assert hashlib.sha256(archive.read_bytes()).hexdigest() == ARCHIVE
subprocess.run(['git','diff','--exit-code',SOURCE,'HEAD','--','app','native','gradle','build.gradle.kts',
                'settings.gradle.kts','gradle.properties','gradlew','build.sh','scripts/verify_source.py',
                'scripts/collect_build_evidence.py','scripts/native-runtime-api33.sh'],check=True)
with zipfile.ZipFile(archive) as z:
    assert len(z.namelist()) == len(set(z.namelist()))
    manifest = json.loads(z.read('.build-output/build-manifest.json'))
    assert manifest['revision'] == SOURCE
    assert manifest['application_id'] == 'com.gccwebos.android.debug'
    assert manifest['version_code'] == 5 and manifest['version_name'] == '0.3.0-commerce-draft-debug'
    assert manifest['unit_tests'] == {'tests':193,'failures':0,'errors':0,'skipped':0}
    assert manifest['lint_error_count'] == 0 and manifest['native_engine_embedded'] is False
    assert manifest['sha256'] == HASHES['app/build/outputs/apk/debug/app-debug.apk']
    for name, digest in HASHES.items():
        data = z.read(name); assert hashlib.sha256(data).hexdigest() == digest
        p = pathlib.Path(name); p.parent.mkdir(parents=True,exist_ok=True); p.write_bytes(data)
        with zipfile.ZipFile(p) as apk: assert not any(n.endswith('/libgccmihomo.so') for n in apk.namelist())
out = pathlib.Path('.build-output/runtime-api33');out.mkdir(parents=True,exist_ok=True)
(out/'new-ordinary-provenance.json').write_text(json.dumps({
    'source_commit':SOURCE,'harness_commit':subprocess.check_output(['git','rev-parse','HEAD'],text=True).strip(),
    'artifact_id':ARTIFACT,'archive_sha256':ARCHIVE,'apk_hashes':HASHES,
    'original_build_run':36701872124,'original_api36_status':'not_validated_by_this_api33_run',
    'production_payment':False,'physical_device_tested':False,'native_engine_embedded':False,
},indent=2)+'\n')
print('PASS: exact new ordinary APK/test APK, source tree, artifact digest, unit tests and Lint provenance verified')
