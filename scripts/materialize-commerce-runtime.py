#!/usr/bin/env python3
"""Verify the exact NEW ordinary commerce APK before offline Android13 execution."""
import hashlib, json, os, pathlib, subprocess, sys, zipfile
SOURCE = '2ac4d63f6bbe40844566b4194f2c467b443658aa'
ARTIFACT = 11088960382
ARCHIVE = '3e21fe7f0ad5698b218bb76e34dbc35be0e250bdce28f595c835dd21a687c973'
HASHES = {
    'app/build/outputs/apk/debug/app-debug.apk': 'cbb8a4be34678e94a46c6cadc3029d9dee279e28537375aacf389018ef0e33e6',
    'app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk': '47ec8c1aa07f3ab65c022c14056adfb5c67df9326f7dd857e63f26d013be91a9',
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
    'original_build_run':36697042737,'original_api36_status':'blocked_by_com_android_systemui_anr_before_tests',
    'production_payment':False,'physical_device_tested':False,'native_engine_embedded':False,
},indent=2)+'\n')
print('PASS: exact new ordinary APK/test APK, source tree, artifact digest, unit tests and Lint provenance verified')
