#!/usr/bin/env python3
"""UI14 verification, explicit runtime delta, and same-identity delivery."""
from pathlib import Path
import hashlib,importlib.util,json,os,re,subprocess,sys,zipfile
BASE='2150e2dcae0921d418e8bd5700e39dae535c5370'
ROOT='android-app/app/src/main/java/io/github/xgl34222220/hetu/'
TEST='android-app/app/src/test/java/io/github/xgl34222220/hetu/'
OUT=Path('integration-evidence');OUT.mkdir(exist_ok=True)
VERSION='0.4.0-ui92-r146.14'
APK=Path('android-app/app/build/outputs/apk/debug/app-debug.apk')
spec=importlib.util.spec_from_file_location('prior_tools','tools/finish_ui13.py')
prior=importlib.util.module_from_spec(spec);spec.loader.exec_module(prior)
SUITES=prior.SUITES|{'RuntimeCompatibility14Test','PanelPolish14Test'}

def git_bytes(path):return subprocess.check_output(['git','show',BASE+':'+path])
def record():prior.record()
def verify():
    changes=json.loads((OUT/'ui14-source-changes.json').read_text())
    contract=json.loads((OUT/'ui14-contract.json').read_text())
    expected_changes={ROOT+x for x in ['PanelPolish14.kt','RuntimeCompatibility14.java','PanelStrategy11.kt',
        'PanelNavigation13.kt','MihomoStartupConfig.java','ProxyAdblockRules.java','RootProxyManager.java']}
    expected_changes|={TEST+x for x in ['PanelUi11Test.kt','PanelNavigation13Test.kt','RuntimeCompatibility14Test.kt','PanelPolish14Test.kt']}
    expected_changes.add('android-app/app/build.gradle.kts')
    assert set(contract)==expected_changes,('unexpected write scope',set(contract)^expected_changes)
    for path,digest in contract.items():assert hashlib.sha256(Path(path).read_bytes()).hexdigest()==digest,path
    protected=[];tests=[]
    for path in subprocess.check_output(['git','ls-tree','-r','--name-only',BASE]).decode().splitlines():
        if path in contract:continue
        if path.startswith('android-app/app/src/main/') or path.startswith('native/'):
            assert Path(path).read_bytes()==git_bytes(path),path;protected.append(path)
        elif path.startswith('android-app/app/src/test/'):
            assert Path(path).read_bytes()==git_bytes(path),path;tests.append(path)
    gradle=Path('android-app/app/build.gradle.kts').read_text()
    assert gradle.replace('versionCode = 1015','versionCode = 1014').replace(VERSION,'0.4.0-ui92-r146.13').encode()==git_bytes('android-app/app/build.gradle.kts')
    adapter=lambda s:s[s.index('internal fun PanelStrategyRoute11('):s.index('/** All UI and real callbacks')]
    assert adapter(Path(ROOT+'PanelStrategy11.kt').read_text())==adapter(git_bytes(ROOT+'PanelStrategy11.kt').decode())
    panel=Path(ROOT+'PanelNavigation13.kt').read_text()
    assert panel[panel.index('internal val PanelTabs13'):panel.index('/** Presentation-only back stack')]==git_bytes(ROOT+'PanelNavigation13.kt').decode().split('internal val PanelTabs13',1)[1].split('/** Presentation-only back stack',1)[0].join(['internal val PanelTabs13',''])
    label=Path(ROOT+'PanelStrategy11.kt').read_text().split('internal fun PanelDelayLabel11(',1)[1]
    assert 'NativeSpinner' not in label and 'QuietDelay14' in label
    quiet=Path(ROOT+'PanelPolish14.kt').read_text().split('internal fun QuietDelay14(',1)[1].split('@OptIn',1)[0]
    assert 'NativeSpinner' not in quiet and 'rememberInfiniteTransition' not in quiet
    assert '.fillMaxHeight(.76f)' in panel and 'PanelShortcut14' in panel
    suites=json.loads((OUT/'test-suites.json').read_text())
    assert {s['name'].rsplit('.',1)[-1] for s in suites}==SUITES
    totals={k:sum(int(s[k]) for s in suites) for k in ['tests','failures','errors','skipped']}
    assert totals['tests']>=250 and totals['failures']==totals['errors']==totals['skipped']==0,totals
    expected=json.load(open('UI92_RUNTIME146_INPUTS.json'))['original146_payload']
    with zipfile.ZipFile(APK) as z:
        assert z.testzip() is None
        for path,digest in expected.items():assert hashlib.sha256(z.read(path)).hexdigest()==digest,path
    identity=(OUT/'apk-identity.txt').read_text()
    assert all(x in identity for x in ["name='io.github.xgl34222220.hetu'","versionCode='1015'","versionName='0.4.0-ui92-r146.14'"])
    certificate=prior.signer((OUT/'apk-signature.txt').read_text())
    assert certificate==prior.signer((OUT/'ui13-signature.txt').read_text())==(OUT/'intended-signer.sha256').read_text().strip()
    graph=(OUT/'dependencies.txt').read_text()
    versions={module:prior.resolved_versions(graph,module) for module in ['androidx.compose.material3:material3-android','androidx.compose.foundation:foundation-android','androidx.compose.ui:ui-android']}
    assert versions['androidx.compose.material3:material3-android']==['1.5.0-alpha22'],versions
    result=dict(version=VERSION,versionCode=1015,package='io.github.xgl34222220.hetu',size=APK.stat().st_size,
        sha256=hashlib.sha256(APK.read_bytes()).hexdigest(),certificate_sha256=certificate,same_signer_as_ui13=True,
        tests=totals,suites=len(suites),base=BASE,runtime_assets=len(expected),protected_files=len(protected),
        unchanged_test_files=len(tests),runtime_source_changes=['MihomoStartupConfig.java','ProxyAdblockRules.java','RootProxyManager.java','RuntimeCompatibility14.java'],
        resolved_ui_dependencies=versions,wechat_root_cause_confirmed=False,real_device_validated=False)
    (OUT/'result.json').write_text(json.dumps(result,ensure_ascii=False,indent=2))
    (OUT/'protected-files.json').write_text(json.dumps(protected,indent=2))
    subprocess.run(['git','diff','--check'],check=True)
    (OUT/'reviewed.patch').write_bytes(subprocess.check_output(['git','diff','--','android-app']))
    print(json.dumps(result,ensure_ascii=False,indent=2))

def notes():
    r=json.loads((OUT/'result.json').read_text())
    text='''# 河图 UI14：安静测速、节点抽屉与运行兼容性修正

## 本轮范围与事实
按用户2026-09-28T15:35:22Z要求，优先检查微信延迟、部分应用没网和去广告效果，同时继续精修UI13。列表仍是Jetpack Compose LazyColumn，未换RecyclerView或Vue。当前没有取得用户故障发生时的运行配置、日志和明确受影响应用名单，也未获得178180/178179原视频逐帧比较，不能宣称已定位微信根因或所有网络问题已修复。

## 有代码与回归覆盖的功能修改
- 旧global-client-fingerprint已不被当前核心读取：启动副本将其迁移到本地proxies及inline provider payload中缺少client-fingerprint的TLS代理，保留节点明确值与YAML合并继承值，再移除废弃顶层键。源文件、节点原名及密钥不改。远程provider不强加override，避免覆盖其显式节点指纹；依赖旧全局值的远程订阅仍需提供节点级值。
- fake-ip-filter-mode: rule不再按普通域名列表清洗，保留含空格提供商名的真实过滤表达式；非rule模式的非法域名清洗保留。是否与本次微信症状有关需实际配置确认。
- 原生TUN选择“由核心规则分流”时不再把以前保存的应用名单当作exclude-package；仍保留控制进程自身排除及用户明确的黑白名单。本项不影响TPROXY脚本。
- 去广告本地与运行目录快照检查实际block/allow文件SHA256，不再仅凭同revision复用损坏、截断或旧文件；不修改用户源规则。
- 热更新后回读核心实际mode。Global/Direct、空规则分别提示未参与过滤或有效规则为空，不再用下载/更新成功冒充正在拦截；不擅自改用户分流模式。
- 网络诊断新增安全DNS/TLS/保活标量对照、私人DNS模式、Doze/节能/微信电池豁免线索，以及真实核心过滤规则、provider计数。仅诊断元数据，不采集消息内容/不输出订阅或凭据，不把这些线索当作已证实病因。

## 本轮界面
- 固定六Tab与外层双列组不变；节点页仍是独立原生ModalBottomSheet，不做原位手风琴。内容高度系数从0.88降至0.76，保留外层上下文、原生下拖与返回。
- 批量测速每个节点保留上次数字并淡化，回填200ms显现，不再逐节点旋转；头部只显示已处理/总数，实际原146单节点接口四个一批。选择等待的单个反馈仍保留，不伪造成功勾。
- 按最新明确需求恢复“节点选择”胶囊。它在Dock上方独立56dp底部空间内，不覆盖网格和底栏；只在运行且有可见分组时出现，点击打开真实主策略。
- 子列表搜索使用紧凑圆形胶囊输入；18dp连续圆角、轻阴影和淡色延迟标签。长名中明确协议/线路/倍率标签单独展示，保留剩余识别文字与完整原始路由ID；不猜国家、不虚构序号，长按可查看全文。
- 首页四宫格、系统字体、已删顶栏双按钮、安全区与UI12动效不推翻；无依赖升级。

## 使用与边界
与UI10至UI13同签名。安装后需要用户主动重启一次代理，才会重新生成运行配置并校验部署规则；应用安装本身不会暗中重启或切断连接。重启期间短暂断网，先保存当前工作。不同签名旧包不要卸载/清数据来强装。
这是Debug测试版，Material3沿用严格1.5.0-alpha22。保留原146二进制，不等于运行源码未改：本轮明确修改了上述配置生成/快照/诊断路径。未做用户手机Root联网、微信接收时延、实际广告页面、GPU模糊或60/120fps验收。DNS/域名过滤不能保证去除同域广告和HTTPS页面内元素。缓存签名缺失则构建失败，不创建替代身份。

'''
    text+=f"## 自动化与交付\nActions {os.environ['RUN_ID']}；{r['suites']}组{r['tests']['tests']}项通过，失败0、错误0、跳过0。\n"
    text+=f"{r['runtime_assets']}项原146运行资源哈希一致；{r['protected_files']}个范围外生产/原生文件与UI13逐字节不变；{r['unchanged_test_files']}个原测试文件未改。原FAB不存在的预期依最新需求改为存在，原六页导航、二级返回、失败保留和真实请求检查继续运行。\n"
    text+=f"版本{VERSION}，内部号1015，包名{r['package']}；APK {r['size']}字节，SHA256 {r['sha256']}。\n证书SHA256 {r['certificate_sha256']}；未生成或导出签名文件。\n"
    Path('docs/UI92_RUNTIME146_UI14.md').write_text(text)
    agents=Path('AGENTS.md').read_text().replace('## 当前已交付：UI13','## UI13历史交付',1)
    Path('AGENTS.md').write_text(f'''## 当前已交付：UI14

{VERSION}/1015；继续原UI13，用户最新需求恢复独立避让FAB、安静测速和更小节点抽屉。明确授权排查运行层，本轮仅做docs/UI92_RUNTIME146_UI14.md所列配置、过滤快照和诊断修改，原146二进制不换。微信根因尚未实机确认；不声称联网问题全部修复。按此源码继续，不重放旧迁移。签名沿用UI10—UI13且失败关闭。Actions {os.environ['RUN_ID']}，{r['tests']['tests']}项通过。\n\n'''+agents)

def commit():
    paths=list(json.loads((OUT/'ui14-contract.json').read_text()))+['docs/UI92_RUNTIME146_UI14.md','AGENTS.md']
    subprocess.run(['git','add','--']+paths,check=True)
    subprocess.run(['git','-c','user.name=github-actions[bot]','-c','user.email=41898282+github-actions[bot]@users.noreply.github.com','commit','-m','feat: deliver tested UI14 quiet nodes and runtime compatibility [skip ci]'],check=True)
    sha=subprocess.check_output(['git','rev-parse','HEAD']).decode().strip()
    subprocess.run(['git','push','origin','HEAD:main'],check=True)
    (OUT/'app-commit.txt').write_text(sha)

def export():
    out=Path('delivery');out.mkdir(exist_ok=True)
    if (OUT/'app-commit.txt').exists():
        (out/'Hetu-UI92-Runtime146-UI14.apk').write_bytes(APK.read_bytes())
        r=json.loads((OUT/'result.json').read_text());r['app_commit']=(OUT/'app-commit.txt').read_text().strip()
        (OUT/'result.json').write_text(json.dumps(r,ensure_ascii=False,indent=2))
        (out/'result.json').write_text(json.dumps(r,ensure_ascii=False,indent=2))
        with zipfile.ZipFile(out/'Hetu-UI92-Runtime146-UI14-source.zip','w',zipfile.ZIP_DEFLATED) as z:
            for path in subprocess.check_output(['git','ls-files','-z']).decode().split('\0'):
                p=Path(path)
                if path and p.is_file() and p.suffix.lower() not in {'.jks','.keystore','.key','.p12','.pfx','.ttf','.otf','.woff','.woff2'}:z.write(p,path)
    with zipfile.ZipFile(out/'Hetu-UI92-Runtime146-UI14-verification.zip','w',zipfile.ZIP_DEFLATED) as z:
        for folder in ['integration-evidence','android-app/app/build/test-results/testDebugUnitTest','android-app/app/build/reports/tests/testDebugUnitTest','android-app/app/build/outputs/ui14']:
            for p in Path(folder).rglob('*'):
                if p.is_file() and p.suffix.lower() not in {'.jks','.keystore','.key','.p12','.pfx','.ttf','.otf'}:z.write(p,str(p))

if __name__=='__main__':
    {'record':record,'verify':verify,'notes':notes,'commit':commit,'export':export}[sys.argv[1]]()
