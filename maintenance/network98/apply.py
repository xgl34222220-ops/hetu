#!/usr/bin/env python3
"""Apply one locally tested, hash-pinned network-only patch. Refuse drift."""
from pathlib import Path
import base64,gzip,hashlib,json,subprocess
root=Path(__file__).resolve().parents[2]
parts=Path(__file__).resolve().parent
text=''.join((parts/f'part{i}.b64').read_text().strip() for i in range(5))
# Restore the single transport transcription omission before verifying the blob.
text=text.replace('EOpILJzG6eiFtMRGLyTinJgR1bi','EOpILJzG6eiFtMRGLyJrbvRGkTinJgR1bi')
payload=base64.b64decode(text,validate=True)
assert hashlib.sha256(payload).hexdigest()=='b8986c33adb029dab8abfc75106fb59d9281233ac1661ab87ab0cd7a6639d420','Patch transport hash mismatch'
decoded=gzip.decompress(payload)
assert len(decoded)<2000000
bundle=json.loads(decoded)
allowed={
'android-app/app/src/main/assets/hetu-root.sh',
'android-app/app/src/main/java/io/github/xgl34222220/hetu/ProxyNetworkMatchService.java',
'android-app/app/src/main/java/io/github/xgl34222220/hetu/RootProxyManager.java',
'android-app/app/build.gradle.kts','tests/ui-runtime-baseline.json',
'tools/test_root_ipv6.py','tools/test_network_integrity.py','tools/test_network_namespace.py','docs/test98-network.md'}
assert {x['path'] for x in bundle['files']}==allowed
for f in bundle['files']:
    p=root/f['path']
    assert not p.is_symlink()
    assert (hashlib.sha256(p.read_bytes()).hexdigest() if p.exists() else None)==f['before'],f['path']
patch=bundle['patch'].encode()
subprocess.run(['git','apply','--check','-'],cwd=root,input=patch,check=True)
subprocess.run(['git','apply','-'],cwd=root,input=patch,check=True)
for f in bundle['files']:
    assert hashlib.sha256((root/f['path']).read_bytes()).hexdigest()==f['after'],f['path']
changed=set(subprocess.check_output(['git','diff','--name-only'],cwd=root,text=True).splitlines())
assert changed<=allowed,changed-allowed
print('Verified network-only patch applied; UI files unchanged')
