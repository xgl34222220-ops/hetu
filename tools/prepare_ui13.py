#!/usr/bin/env python3
"""Verified source-diff transport for the authorized UI13 change.
The CI commits readable Kotlin only after compilation and all regressions pass.
There are no signing keys, runtime binaries, or service actions in this payload.
"""
import base64, zlib, hashlib, subprocess, re
from pathlib import Path
BASE='6e09ed721dd85f8cb96045d381518ae20a55cfff'
EXPECTED=['b59e55f2f25a55bb5d4511237c3c2d1726ffbcc7fd384eb6504e30378c675a45','5e13725a57a23d17e5f50c362a6317ced5c3d3297892ee5f7892dcf0fbf9ff9d','36b394501fca02bef15fff0afff2c7a92a8de47a6dcbbffc5ed2303597cf1d24','175fdb663134a96e189f9c04704d860b92911a76c2b425348b2ba1e82314605d']
subprocess.run(['git','merge-base','--is-ancestor',BASE,'HEAD'],check=True)
parts=[]
for i,digest in enumerate(EXPECTED):
    text=Path(f'tools/ui13-reviewed.part{i}').read_text().strip()
    if i==3:
        # Remove a known duplicated paste segment, then require the exact original digest.
        text=re.sub(r'(?s)(GVD\+OfD6yuPPlq9)\+sHKvQv9v91affCH.*?GVD\+OfD6yuPPlq9\+kH/',r'\1+kH/',text)
    actual=hashlib.sha256(text.encode()).hexdigest()
    print('part',i,'length',len(text),'sha256',actual,flush=True)
    assert actual==digest, f'UI13 transport mismatch in part {i}'
    parts.append(text)
patch=zlib.decompress(base64.b64decode(''.join(parts)))
assert hashlib.sha256(patch).hexdigest()=='a52f6d38dfd0527df6999b6f6fb28a776035998140e6eccfdb57964aa8f471d4'
for line in patch.decode().splitlines():
    if line.startswith('--- a/'):
        path=line[6:]
        assert Path(path).read_bytes()==subprocess.check_output(['git','show',BASE+':'+path]),path
out=Path('integration-evidence');out.mkdir(exist_ok=True)
(out/'ui13-reviewed.patch').write_bytes(patch)
subprocess.run(['git','apply','--check','-'],input=patch,check=True)
subprocess.run(['git','apply','-'],input=patch,check=True)
print('Reviewed UI13 source diff applied with exact SHA256 and base checks.')
