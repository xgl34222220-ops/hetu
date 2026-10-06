"""Host-only stale-frame guard checks; no emulator or UI visual acceptance."""
import ast,hashlib,json,tempfile
from pathlib import Path
source=Path(__file__).with_name('smoke_hetu_apk.py')
fn=next(n for n in ast.parse(source.read_text()).body if isinstance(n,ast.FunctionDef) and n.name=='verify_distinct_webview_frames')
namespace={'hashlib':hashlib,'json':json}
exec(compile(ast.Module(body=[fn],type_ignores=[]),str(source),'exec'),namespace)
verify=namespace[fn.name]
names=[f'reference-03B-{n:03d}-native-{s}.png' for n,s in [(40,'overview'),(41,'groups'),(42,'connections'),(43,'node-dialog'),(44,'switch-failure')]]
with tempfile.TemporaryDirectory() as temp:
 out=Path(temp)
 for i,n in enumerate(names):(out/n).write_bytes(b'host-test-only-frame'+bytes([i]))
 verify(out)
 (out/names[2]).write_bytes((out/names[1]).read_bytes())
 try:verify(out)
 except AssertionError as error:assert 'Stale duplicate' in str(error)
 else:raise AssertionError('Duplicate native screenshot was accepted')
 (out/names[4]).unlink()
 try:verify(out)
 except AssertionError as error:assert 'Missing native frame' in str(error)
 else:raise AssertionError('Missing native screenshot was accepted')
print('3 host-only frame guard checks passed; native pixels still require review')
