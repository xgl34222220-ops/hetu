#!/usr/bin/env python3
"""UI15 startup hotfix: exact source scope, same signing identity, no private YAML export."""
from pathlib import Path
import hashlib,importlib.util,json,os,subprocess,sys,zipfile
BASE='0a4b0c6b1f60b5b9d8d26d2be27b46d343082ffc'
ROOT='android-app/app/src/main/java/io/github/xgl34222220/hetu/'
TEST='android-app/app/src/test/java/io/github/xgl34222220/hetu/'
OUT=Path('integration-evidence');OUT.mkdir(exist_ok=True)
VERSION='0.4.0-ui92-r146.15'
APK=Path('android-app/app/build/outputs/apk/debug/app-debug.apk')
spec=importlib.util.spec_from_file_location('ui14_tools','tools/finish_ui14.py')
ui14=importlib.util.module_from_spec(spec);spec.loader.exec_module(ui14)
prior=ui14.prior
SUITES=ui14.SUITES|{'RuntimeYaml15Test','RuntimeStartup15Test'}
def digest(path):return hashlib.sha256(Path(path).read_bytes()).hexdigest()
def git_bytes(path):return subprocess.check_output(['git','show',BASE+':'+path])
def record():prior.record()
def verify():
    contract=json.loads((OUT/'ui15-contract.json').read_text())
    allowed={ROOT+'RuntimeCompatibility14.java',ROOT+'RuntimeYaml15.java',TEST+'RuntimeYaml15Test.java',TEST+'RuntimeStartup15Test.java','android-app/app/build.gradle.kts'}
    assert set(contract)==allowed,('wrong scope',set(contract)^allowed)
    for path,sha in contract.items():assert digest(path)==sha,path
    protected=[];tests=[]
    for path in subprocess.check_output(['git','ls-tree','-r','--name-only',BASE]).decode().splitlines():
        if path in allowed:continue
        if path.startswith('android-app/app/src/main/') or path.startswith('native/'):
            assert Path(path).read_bytes()==git_bytes(path),path;protected.append(path)
        elif path.startswith('android-app/app/src/test/'):
            assert Path(path).read_bytes()==git_bytes(path),path;tests.append(path)
    gradle=Path('android-app/app/build.gradle.kts').read_text()
    assert gradle.replace('versionCode = 1016','versionCode = 1015').replace(VERSION,'0.4.0-ui92-r146.14').encode()==git_bytes('android-app/app/build.gradle.kts')
    proof=json.loads((OUT/'local-reproduction.json').read_text())
    for path,sha in proof['compiled_production_sources'].items():assert digest(path)==sha,('local tested code differs',path)
    assert proof['original_production_generator_failure_reproduced'] and proof['ui15_production_parser_pass']
    assert proof['source_bytes']==58379 and proof['collection_aliases']==88 and proof['production_generator_cases']==4
    assert proof['private_source_unchanged'] and not proof['core_or_device_tested']
    suites=json.loads((OUT/'test-suites.json').read_text())
    assert {s['name'].rsplit('.',1)[-1] for s in suites}==SUITES
    totals={k:sum(int(s[k]) for s in suites) for k in ['tests','failures','errors','skipped']}
    assert totals==dict(tests=277,failures=0,errors=0,skipped=0),totals
    expected=json.load(open('UI92_RUNTIME146_INPUTS.json'))['original146_payload']
    with zipfile.ZipFile(APK) as z:
        assert z.testzip() is None
        for name,sha in expected.items():assert hashlib.sha256(z.read(name)).hexdigest()==sha,name
    identity=(OUT/'apk-identity.txt').read_text()
    assert all(v in identity for v in ["name='io.github.xgl34222220.hetu'","versionCode='1016'","versionName='0.4.0-ui92-r146.15'"])
    certificate=prior.signer((OUT/'apk-signature.txt').read_text())
    assert certificate==prior.signer((OUT/'ui14-signature.txt').read_text())==(OUT/'intended-signer.sha256').read_text().strip()
    graph=(OUT/'dependencies.txt').read_text()
    versions={m:prior.resolved_versions(graph,m) for m in ['org.yaml:snakeyaml','androidx.compose.material3:material3-android','androidx.compose.foundation:foundation-android','androidx.compose.ui:ui-android']}
    assert versions['org.yaml:snakeyaml']==['2.3'] and versions['androidx.compose.material3:material3-android']==['1.5.0-alpha22'],versions
    result=dict(version=VERSION,versionCode=1016,package='io.github.xgl34222220.hetu',size=APK.stat().st_size,sha256=digest(APK),
        certificate_sha256=certificate,same_signer_as_ui14=True,tests=totals,suites=len(suites),base=BASE,
        runtime_assets=len(expected),protected_files=len(protected),unchanged_test_files=len(tests),ui_source_unchanged=True,
        startup_regression_reproduced_on_exact_private_source=True,private_source_uploaded=False,
        source_bytes=proof['source_bytes'],source_sha256=proof['source_sha256'],collection_aliases=88,old_alias_limit=50,new_alias_limit=4096,
        resolved_dependencies=versions,real_device_validated=False,wechat_delay_fixed=False)
    (OUT/'result.json').write_text(json.dumps(result,ensure_ascii=False,indent=2))
    (OUT/'protected-files.json').write_text(json.dumps(protected,indent=2))
    subprocess.run(['git','diff','--check'],check=True)
    (OUT/'reviewed.patch').write_bytes(subprocess.check_output(['git','diff','--','android-app']))
    print(json.dumps(result,ensure_ascii=False,indent=2))
def notes():
    r=json.loads((OUT/'result.json').read_text())
    text='''# 河图 UI15：修复 UI14 对正常 YAML 模板的启动误拦

## 已复现的根因
用户最新诊断是 UI14 在配置阶段约32ms失败，而不是当前微信连接的运行中抓包。报告时核心已停止，控制器连接被拒绝；历史运行日志另有真实 hetu-adblock REJECT 命中，不能把旧 healthy 字段或旧日志当作当前运行状态。
已从用户此前保存文件中取得与报告58379字节、SHA256完全一致的原配置，仅在本地私有目录处理。该文件99次别名事件中88次指向集合模板，UI14新增 RuntimeCompatibility14.tree 将集合别名上限设置为50，导致生产 MihomoStartupConfig.generate 在核心启动前直接失败。原配置不是这次错误的责任方；此次回归属于UI14新增解析检查。
使用原UI14生产Java源码与同版SnakeYAML2.3在本地复现相同限额异常。改后同一份私有原配置通过生产解析、指纹兼容转换，以及TPROXY/TUN/Redirect/Enhance四种启动副本生成；提供商、策略组与节点段原始文本均不变。私有源文件SHA前后不变，原配置/订阅/密钥没有上传GitHub。

## 修复范围
- 启动兼容检查仍采用 compose() 构建不展开的节点图，集合别名限额改为有界4096；保留4 Mi代码点与50层语法嵌套限制。不把这些选项用于load()对象展开，不通过忽略异常或删除模板来启动。
- 合并继承查找改为按对象身份去重的迭代遍历，保留显式值优先、merge序列先项优先，避免深别名链/重复图/循环在Java递归中溢出。
- 解析限额、语法/结构、空配置、多文档分开报错；支持的语法异常显示行列号，不附带源行、URL、节点名、凭据或原始异常cause。诊断复用这个安全原因，不再只输出泛化的“请检查YAML”。
- 仅替换RuntimeCompatibility14的解析/继承入口与诊断错误文字，增加RuntimeYaml15。MihomoStartupConfig、RootProxyManager、去广告逻辑、代理核心二进制、所有UI文件及依赖版本不改，最终核心预检没有绕过。

## 检查与边界
新增27项覆盖旧50次限制失败、88模板完整启动副本、4096边界、体积与嵌套限额、凭据不泄露、合并优先级、长引用链/循环/DAG、Unicode偏移、内联节点、显式指纹、rule/blacklist过滤、原文件不变。已有测试文件完全保留。
本地初测7项因为合成样例使用行内rules而命中原有去广告注入限制；样例改用本次真实配置采用的块状rules列表，并额外保留“行内rules依然明确拒绝而非悄悄放过”的检查。本补丁不宣称新增行内rules的去广告注入支持。
原文件复现发生在本地JVM，不是设备实测。云端仅运行合成fixture，没有用户原始配置。保留本地无敏感数据的复现摘要与源码哈希，区分本地结果和Actions结果。

'''
    text+=f"## 构建与交付\nActions {os.environ['RUN_ID']}；{r['suites']}组{r['tests']['tests']}项，失败0、错误0、跳过0。{r['runtime_assets']}项原146运行资源哈希一致；{r['protected_files']}个范围外生产/原生文件及{r['unchanged_test_files']}个原测试文件逐字节不变。\n"
    text+=f"版本{VERSION}，versionCode1016，包名{r['package']}。APK {r['size']}字节，SHA256 {r['sha256']}。证书{r['certificate_sha256']}，与UI10至UI14同签名；没有生成或导出新私钥。\n"
    text+='''
## 使用
覆盖安装后保留原配置，核心已停止的直接点启动；仍在运行的先保存工作再主动重启代理。不要卸载或清数据，不要为适配应用去重写/展开YAML锚点。
本包是启动回归热修复，不等于微信延迟、所有软件联网或具体广告页面已真机验收。Material3沿用1.5.0-alpha22，仍为Debug测试版；签名缓存丢失则构建失败，不静默换身份。
'''
    Path('docs/UI92_RUNTIME146_UI15.md').write_text(text)
    agents=Path('AGENTS.md').read_text().replace('## 当前已交付：UI14','## UI14历史交付',1)
    Path('AGENTS.md').write_text(f'''## 当前已交付：UI15

{VERSION}/1016，针对用户提供的启动诊断修复UI14集合别名50次上限误拦；已在哈希完全匹配的私有原配置复现旧错误及改后配置生成。界面、Root脚本、核心与依赖不改，不能重放旧UI方案；也不声称微信延迟已修复。详情docs/UI92_RUNTIME146_UI15.md。Actions {os.environ['RUN_ID']}，{r['tests']['tests']}项通过。同UI10至UI14签名，缺失时失败关闭。\n\n'''+agents)
def commit():
    paths=list(json.loads((OUT/'ui15-contract.json').read_text()))+['docs/UI92_RUNTIME146_UI15.md','AGENTS.md']
    subprocess.run(['git','add','--']+paths,check=True)
    subprocess.run(['git','-c','user.name=github-actions[bot]','-c','user.email=41898282+github-actions[bot]@users.noreply.github.com','commit','-m','fix: deliver UI15 YAML template startup hotfix with same signer [skip ci]'],check=True)
    sha=subprocess.check_output(['git','rev-parse','HEAD']).decode().strip()
    subprocess.run(['git','push','origin','HEAD:main'],check=True)
    (OUT/'app-commit.txt').write_text(sha)
def export():
    out=Path('delivery');out.mkdir(exist_ok=True)
    denied={'.jks','.keystore','.key','.p12','.pfx','.ttf','.otf','.woff','.woff2'}
    if (OUT/'app-commit.txt').exists():
        (out/'Hetu-UI92-Runtime146-UI15.apk').write_bytes(APK.read_bytes())
        r=json.loads((OUT/'result.json').read_text());r['app_commit']=(OUT/'app-commit.txt').read_text().strip()
        (OUT/'result.json').write_text(json.dumps(r,ensure_ascii=False,indent=2));(out/'result.json').write_text(json.dumps(r,ensure_ascii=False,indent=2))
        with zipfile.ZipFile(out/'Hetu-UI92-Runtime146-UI15-source.zip','w',zipfile.ZIP_DEFLATED) as z:
            for path in subprocess.check_output(['git','ls-files','-z']).decode().split('\0'):
                p=Path(path)
                if path and p.is_file() and p.suffix.lower() not in denied:z.write(p,path)
    with zipfile.ZipFile(out/'Hetu-UI92-Runtime146-UI15-verification.zip','w',zipfile.ZIP_DEFLATED) as z:
        for folder in ['integration-evidence','android-app/app/build/test-results/testDebugUnitTest','android-app/app/build/reports/tests/testDebugUnitTest']:
            for p in Path(folder).rglob('*'):
                if p.is_file() and p.suffix.lower() not in denied:z.write(p,str(p))
if __name__=='__main__':{'record':record,'verify':verify,'notes':notes,'commit':commit,'export':export}[sys.argv[1]]()
