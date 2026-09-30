#!/usr/bin/env python3
import hashlib,json,shutil,subprocess,sys
from pathlib import Path
from zipfile import ZipFile
SOURCE='25a4d75e1afdeb3f9097e2f8f133a7e21c065e6e'
ZIP_SHA='4d008dd341eccac0f575210e9778af9c8490bc831a8ad70a1cfe142d47e8957c'
HASHES={'app/build/outputs/apk/debug/app-debug.apk':'1fb07516067e9113e197592883dc8d80cd5eb54afde69b520097cd340c071079','app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk':'a806426ec28e46a835e3c1e278a534cfb1bf0975ef79d109271d8ffaa668714f'}
ROOT=Path(__file__).resolve().parents[1]
OUT=ROOT/'.build-output/runtime-api33'
OUT.mkdir(parents=True,exist_ok=True)
def git(ref):return subprocess.check_output(['git','rev-parse',ref],cwd=ROOT,text=True).strip()
def main():
 archive,metadata=map(Path,sys.argv[1:3]); m=json.loads(metadata.read_text())
 assert m['id']==11090630515 and not m['expired'] and m['size_in_bytes']==12645039
 assert m['digest']=='sha256:'+ZIP_SHA and hashlib.sha256(archive.read_bytes()).hexdigest()==ZIP_SHA
 assert m['workflow_run']['id']==36701158610 and m['workflow_run']['head_sha']==SOURCE and m['workflow_run']['repository_id']==1393547812
 identities={p:git(SOURCE+':'+p) for p in ['app','build.gradle.kts','settings.gradle.kts','gradle.properties','gradle']}
 assert all(git('HEAD:'+p)==sha for p,sha in identities.items()),'App inputs changed; fresh binary required'
 with ZipFile(archive) as z:
  assert len(z.namelist())==len(set(z.namelist()))
  manifest=json.loads(z.read('.build-output/build-manifest.json'))
  assert manifest['revision']==SOURCE and manifest['sha256']==HASHES[manifest['artifact']]
  assert manifest['version_code']==3 and manifest['version_name']=='0.2.0-m1-debug'
  assert manifest['unit_tests']==dict(tests=102,failures=0,errors=0,skipped=0) and manifest['lint_error_count']==0
  for path,sha in HASHES.items():
   data=z.read(path);assert hashlib.sha256(data).hexdigest()==sha
   target=ROOT/path;target.parent.mkdir(parents=True,exist_ok=True);target.write_bytes(data)
  (OUT/'source-build-manifest.json').write_text(json.dumps(manifest,indent=2)+'\n')
 with ZipFile(ROOT/'app/build/outputs/apk/debug/app-debug.apk') as z:assert not any(n.endswith('/libgccmihomo.so') for n in z.namelist())
 receipt=dict(provenance_verified=True,binary_source_commit=SOURCE,harness_commit=git('HEAD'),source_workflow_run=36701158610,source_artifact_id=11090630515,source_archive_sha256=ZIP_SHA,binary_input_git_objects=identities,apk_sha256=HASHES,runtime_api_under_test=33,compile_sdk=36,target_sdk=36,min_sdk=26,api36_status='original_post_test_relaunch_failed_system_anr_preserved',real_tun_traffic_tested=False,physical_device_tested=False)
 (OUT/'provenance.json').write_text(json.dumps(receipt,indent=2)+'\n');shutil.copyfile(metadata,OUT/'source-artifact-metadata.json')
 print('PASS exact merged-M1 archive, APK hashes, build inputs and source provenance')
if __name__=='__main__':main()
