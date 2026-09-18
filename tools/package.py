#!/usr/bin/env python3
"""Build module, embed exact same bytes in APK, then create auditable source archive."""
import argparse
import hashlib
import json
import shutil
import subprocess
import sys
import zipfile
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
VERSION = '0.3.0-beta.1'
CODE = 301

def verify_module(path):
    """Reject archives a root manager cannot identify before invoking any script."""
    with zipfile.ZipFile(path) as z:
        names = z.namelist()
        required = {'module.prop', 'customize.sh', 'service.sh', 'bin/hetu', 'sources.tsv'}
        if not required.issubset(names) or len(names) != len(set(names)):
            raise RuntimeError('Invalid module ZIP: root entries missing or duplicated')
        if any(n.startswith('/') or '..' in n.split('/') for n in names):
            raise RuntimeError('Unsafe module ZIP path')
        if any(i.flag_bits & 1 for i in z.infolist()) or z.testzip() is not None:
            raise RuntimeError('Encrypted or corrupt module ZIP')
        props = dict(line.split('=', 1) for line in z.read('module.prop').decode('utf-8').splitlines() if '=' in line)
        if props.get('id') != 'hetu' or props.get('version') != VERSION or props.get('versionCode') != str(CODE):
            raise RuntimeError('Module metadata and build version disagree')

def sha(path):
    return hashlib.sha256(path.read_bytes()).hexdigest()

def main():
    parser = argparse.ArgumentParser()
    parser.add_argument('--build-app', action='store_true')
    parser.add_argument('--prepare-only', action='store_true')
    args = parser.parse_args()
    out = ROOT / 'out'
    out.mkdir(exist_ok=True)
    assets = ROOT / 'android-app/app/src/main/assets'
    assets.mkdir(parents=True, exist_ok=True)
    module = out / f'Hetu-{VERSION}-module.zip'
    with zipfile.ZipFile(module, 'w', zipfile.ZIP_DEFLATED, compresslevel=9) as z:
        for p in sorted((ROOT / 'module').rglob('*')):
            if p.is_file():
                name = p.relative_to(ROOT / 'module').as_posix()
                info = zipfile.ZipInfo(name, (2026, 9, 12, 0, 0, 0))
                info.create_system = 3
                info.external_attr = (0o100755 if name.endswith('.sh') or name == 'bin/hetu' else 0o100644) << 16
                z.writestr(info, p.read_bytes(), compress_type=zipfile.ZIP_DEFLATED)
    verify_module(module)
    shutil.copy2(module, assets / 'hetu-module.zip')
    (assets / 'module-info.json').write_text(json.dumps({'file': 'hetu-module.zip', 'version': VERSION, 'versionCode': CODE, 'sha256': sha(module)}, indent=2) + '\n')
    shutil.copytree(ROOT / 'module/rules', assets / 'rules', dirs_exist_ok=True)
    shutil.copy2(ROOT / 'module/sources.tsv', assets / 'sources.tsv')
    shutil.copy2(ROOT / 'module/THIRD_PARTY_NOTICES.md', assets / 'THIRD_PARTY_NOTICES.md')
    shutil.copy2(ROOT / 'module/LICENSE', ROOT / 'LICENSE')
    if args.prepare_only:
        print(module)
        return
    if args.build_app:
        subprocess.run([sys.executable, str(ROOT / 'tools/build_app.py')], cwd=ROOT, check=True)
    apk = out / f'Hetu-{VERSION}.apk'
    if apk.exists():
        with zipfile.ZipFile(apk) as z:
            embedded = z.read('assets/hetu-module.zip')
            assert hashlib.sha256(embedded).hexdigest() == sha(module), 'APK embeds a different module'
            assert json.loads(z.read('assets/module-info.json'))['sha256'] == sha(module)
    source = out / f'Hetu-{VERSION}-source.zip'
    with zipfile.ZipFile(source, 'w', zipfile.ZIP_DEFLATED, compresslevel=9) as z:
        for p in sorted(ROOT.rglob('*')):
            rel = p.relative_to(ROOT)
            if not p.is_file() or any(x in {'out', 'downloads', 'build', '.git', '__pycache__', '.gradle', 'node_modules'} for x in rel.parts):
                continue
            if p.suffix in {'.keystore', '.jks', '.pyc'} or p.name in {'local.properties'}:
                continue
            z.write(p, str(Path('Hetu') / rel))
    products = [module, source] + ([apk] if apk.exists() else [])
    sums = out / 'SHA256SUMS.txt'
    sums.write_text(''.join(f'{sha(p)}  {p.name}\n' for p in products))
    for p in products + [sums]:
        print(f'{p.name}: {p.stat().st_size:,} bytes')

if __name__ == '__main__':
    main()
