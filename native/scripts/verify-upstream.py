#!/usr/bin/env python3
import hashlib,json,pathlib,subprocess,sys,tarfile,tempfile
root=pathlib.Path(__file__).resolve().parent.parent
lock=json.loads((root/'source-lock.json').read_text())
for item in lock['mihomo']['patches']:
 assert hashlib.sha256((root/item['path']).read_bytes()).hexdigest()==item['sha256'],'Patch checksum differs from source-lock'
for name in ('go.mod','go.sum'):
 assert hashlib.sha256((root/name).read_bytes()).hexdigest()==lock[name.replace('.','_')+'_sha256'],name+' checksum differs from source-lock'
assert hashlib.sha256(pathlib.Path(sys.argv[1]).read_bytes()).hexdigest()==lock['mihomo']['sha256'],'Upstream archive checksum differs'

with tempfile.TemporaryDirectory() as d:
 with tarfile.open(sys.argv[1],'r:gz') as t:t.extractall(d,filter='data')
 pristine=next(pathlib.Path(d).iterdir())
 subprocess.run(['patch','-d',str(pristine),'-p1','-i',str(root/'patches/0001-owned-tun-fd.patch')],check=True,stdout=subprocess.DEVNULL)
 actual=root/'third_party/mihomo'
 expected_files={p.relative_to(pristine) for p in pristine.rglob('*') if p.is_file()}
 actual_files={p.relative_to(actual) for p in actual.rglob('*') if p.is_file()}-{pathlib.Path('.gcc-patched-source')}
 assert expected_files==actual_files,'Upstream file set differs'
 for path in expected_files:
  assert hashlib.sha256((pristine/path).read_bytes()).digest()==hashlib.sha256((actual/path).read_bytes()).digest(),f'Unexpected upstream modification: {path}'
 print('PASS: exact pinned upstream source plus documented patch')
