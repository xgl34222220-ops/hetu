#!/usr/bin/env python3
"""Apply UI15 on exact UI14 source. No private YAML or UI redesign is involved."""
from pathlib import Path
import hashlib,json,subprocess
BASE='0a4b0c6b1f60b5b9d8d26d2be27b46d343082ffc'
ROOT='android-app/app/src/main/java/io/github/xgl34222220/hetu/'
TEST='android-app/app/src/test/java/io/github/xgl34222220/hetu/'
OUT=Path('integration-evidence');OUT.mkdir(exist_ok=True)
contract={}
def save(path,text):
    p=Path(path);p.parent.mkdir(parents=True,exist_ok=True);p.write_text(text)
    contract[path]=hashlib.sha256(p.read_bytes()).hexdigest()
def baseline(path):
    raw=subprocess.check_output(['git','show',BASE+':'+path]);assert Path(path).read_bytes()==raw,('baseline changed',path)
    return raw.decode()
for name,prefix in [('RuntimeYaml15.java',ROOT),('RuntimeYaml15Test.java',TEST),('RuntimeStartup15Test.java',TEST)]:
    assert not Path(prefix+name).exists(),('already applied',name)
    save(prefix+name,Path('tools/ui15/'+name).read_text())
p=ROOT+'RuntimeCompatibility14.java';s=baseline(p)
a=s.index('    private static Node tree(');b=s.index('    private static Node field(',a)
s=s[:a]+'''    private static Node tree(String text) throws IOException {
        return RuntimeYaml15.compose(text);
    }
'''+s[b:]
a=s.index('    private static Node inherited(Node node, String key, Set<Node> visited)');b=s.index('    static boolean ruleFakeIpFilter',a)
s=s[:a]+'''    private static Node inherited(Node node, String key) {
        return RuntimeYaml15.inherited(node, key);
    }
'''+s[b:]
s=s.replace('return "配置结构不可解析；未输出配置或凭据。";', 'return error.getMessage() + "\\n未输出配置或凭据。";')
for line in ['import org.yaml.snakeyaml.Yaml;\n','import org.yaml.snakeyaml.LoaderOptions;\n','import org.yaml.snakeyaml.constructor.SafeConstructor;\n']:s=s.replace(line,'')
save(p,s)
p='android-app/app/build.gradle.kts';s=baseline(p)
assert s.count('versionCode = 1015')==1 and s.count('0.4.0-ui92-r146.14')==1
save(p,s.replace('versionCode = 1015','versionCode = 1016').replace('0.4.0-ui92-r146.14','0.4.0-ui92-r146.15'))
proof=json.loads(Path('tools/ui15/local-reproduction.json').read_text())
for path,sha in proof['compiled_production_sources'].items():assert hashlib.sha256(Path(path).read_bytes()).hexdigest()==sha,('differs from locally tested code',path)
(OUT/'ui15-contract.json').write_text(json.dumps(contract,indent=2))
(OUT/'local-reproduction.json').write_text(json.dumps(proof,ensure_ascii=False,indent=2))
print('Applied exact locally tested UI15 parser hotfix; 27 new checks; UI, source YAML and core binaries untouched.')
