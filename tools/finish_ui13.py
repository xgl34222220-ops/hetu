#!/usr/bin/env python3
"""Verify exact tested inputs/outputs and export UI13. No signing keys are read here."""
from pathlib import Path
import hashlib
import json
import os
import re
import subprocess
import sys
import textwrap
import xml.etree.ElementTree as ET
import zipfile

BASE = '6e09ed721dd85f8cb96045d381518ae20a55cfff'
ROOT = 'android-app/app/src/main/java/io/github/xgl34222220/hetu/'
VERSION = '0.4.0-ui92-r146.13'
MATERIAL3 = '1.5.0-alpha22'
FINGERPRINT = '701bbb0aaa5709cf2bebd96ff85ebd64c06cf6c3a2211ec21a9c51e534a5faad'
OUT = Path('integration-evidence')
APK = Path('android-app/app/build/outputs/apk/debug/app-debug.apk')
SUITES = {'CompactHomeDashboardTest', 'Runtime146ContractTest', 'Ui92IntegrationTest',
    'NodeSelectionContinuityTest', 'ProxyLatencyRegressionTest', 'NativeDockUi4Test',
    'HomeUi5RegressionTest', 'HomeUi6RegressionTest', 'HomeUi7InteractionTest',
    'HomeUi8ExperienceTest', 'HomeUi9BentoTest', 'HomeUi10GridTest', 'HomeUi11Test',
    'PanelModel11Test', 'PanelUi11Test', 'MotionModel12Test', 'MotionUi12Test', 'PanelNavigation13Test'}


def git_bytes(path):
    return subprocess.check_output(['git', 'show', BASE + ':' + path])


def signer(text):
    # build-tools37 prints 'V2 Signer:'; older versions print 'Signer #1'.
    # Do not confuse the certificate digest with the public-key/source-stamp digest.
    values = {s.lower() for s in re.findall(
        r'(?mi)^(?:Signer #\d+|V\d+ Signer):? certificate SHA-256 digest:\s*([0-9a-f]{64})\s*$', text)}
    assert values == {FINGERPRINT}, ('unexpected or missing signing certificate', values)
    count = re.search(r'Number of signers:\s*(\d+)', text)
    if count:
        assert count[1] == '1', 'unexpected multiple signers'
    return next(iter(values))


def resolved_versions(graph, module):
    versions = set()
    prefix = module + ':'
    for line in graph.splitlines():
        if prefix not in line:
            continue
        match = re.match(r'(\S+)(?: -> (\S+))?', line.split(prefix, 1)[1])
        if match:
            versions.add(match[2] or match[1])
    return sorted(versions)


def record():
    OUT.mkdir(exist_ok=True)
    suites = []
    failures = []
    for path in Path('android-app/app/build/test-results/testDebugUnitTest').glob('TEST-*.xml'):
        root = ET.parse(path).getroot()
        suites.append({key: root.get(key) for key in ['name', 'tests', 'failures', 'errors', 'skipped']})
        for case in root.findall('testcase'):
            for error in list(case.findall('failure')) + list(case.findall('error')):
                failures.append(dict(suite=root.get('name'), test=case.get('name'),
                    message=error.get('message'), stack=(error.text or '')[:2200]))
    logs = '\n'.join(path.read_text(errors='replace') for path in
        [OUT/'migration.log', OUT/'build.log', OUT/'verification.log'] if path.exists())
    errors = [line for line in logs.splitlines() if line.startswith('e:') or
        'AssertionError' in line or 'What went wrong' in line or 'Traceback' in line]
    result = dict(run=os.environ.get('RUN_URL', ''), suites=suites, failures=failures,
        compile_or_verification_errors=errors,
        totals={key: sum(int(s[key]) for s in suites) for key in ['tests', 'failures', 'errors', 'skipped']})
    (OUT/'attempt.json').write_text(json.dumps(result, ensure_ascii=False, indent=2))
    (OUT/'test-suites.json').write_text(json.dumps(suites, indent=2))
    print(json.dumps(result, ensure_ascii=False, indent=2))


def verify():
    contract=json.loads(Path('tools/ui13_contract.json').read_text())
    allowed=set(contract['after'])
    protected=[];protected_tests=[]
    for path in subprocess.check_output(['git','ls-tree','-r','--name-only',BASE]).decode().splitlines():
        if (path.startswith('android-app/app/src/main/') or path.startswith('native/')) and path not in allowed:
            assert Path(path).read_bytes()==git_bytes(path), path
            protected.append(path)
        elif path.startswith('android-app/app/src/test/') and path not in allowed:
            assert Path(path).read_bytes()==git_bytes(path), path
            protected_tests.append(path)
    for path,digest in contract['after'].items():
        assert hashlib.sha256(Path(path).read_bytes()).hexdigest()==digest, ('unreviewed change',path)
    assert Path(ROOT+'PanelStrategyModel11.kt').read_bytes()==git_bytes(ROOT+'PanelStrategyModel11.kt')
    adapter=lambda s:s[s.index('internal fun PanelStrategyRoute11('):s.index('/** All UI and real callbacks')]
    assert adapter(Path(ROOT+'PanelStrategy11.kt').read_text())==adapter(git_bytes(ROOT+'PanelStrategy11.kt').decode())
    helper=Path(ROOT+'PanelNavigation13.kt').read_text()
    assert 'panel11-fab' not in helper and 'ExpandMore' not in helper
    assert 'path = listOf(group.name)' in helper and 'FixedPanelTabs13' in helper
    gradle=Path('android-app/app/build.gradle.kts').read_text()
    restored=gradle.replace('versionCode = 1014','versionCode = 1013').replace(VERSION,'0.4.0-ui92-r146.12')
    assert restored.encode()==git_bytes('android-app/app/build.gradle.kts'), 'dependency or build settings changed'
    graph = (OUT/'dependencies.txt').read_text()
    dependencies = {module: resolved_versions(graph, module) for module in [
        'androidx.compose.material3:material3-android',
        'androidx.compose.foundation:foundation-android', 'androidx.compose.ui:ui-android']}
    assert dependencies['androidx.compose.material3:material3-android'] == [MATERIAL3], dependencies
    suites = json.loads((OUT/'test-suites.json').read_text())
    assert {s['name'].rsplit('.', 1)[-1] for s in suites} == SUITES
    totals = {key: sum(int(s[key]) for s in suites) for key in ['tests', 'failures', 'errors', 'skipped']}
    assert totals == dict(tests=219, failures=0, errors=0, skipped=0), totals
    expected = json.load(open('UI92_RUNTIME146_INPUTS.json'))['original146_payload']
    with zipfile.ZipFile(APK) as archive:
        assert archive.testzip() is None
        for name, digest in expected.items():
            assert hashlib.sha256(archive.read(name)).hexdigest() == digest, name
    identity = (OUT/'apk-identity.txt').read_text()
    assert all(value in identity for value in ["name='io.github.xgl34222220.hetu'",
        "versionCode='1014'", "versionName='0.4.0-ui92-r146.13'"])
    certificate = signer((OUT/'apk-signature.txt').read_text())
    assert certificate == signer((OUT/'ui12-signature.txt').read_text())
    assert (OUT/'intended-signer.sha256').read_text().strip() == certificate
    result = dict(version=VERSION, versionCode=1014, package='io.github.xgl34222220.hetu',
        tests=totals, suites=len(suites), size=APK.stat().st_size,
        sha256=hashlib.sha256(APK.read_bytes()).hexdigest(), certificate_sha256=certificate,
        same_signer_as_ui12=True, runtime_assets=len(expected), protected_files=len(protected),
        unchanged_original_test_files=len(protected_tests), base=BASE, material3=MATERIAL3,
        resolved_ui_dependencies=dependencies,
        navigation='static shared primary tabs; two-column group grid; separate native group sheet')
    (OUT/'result.json').write_text(json.dumps(result, ensure_ascii=False, indent=2))
    (OUT/'protected-files.json').write_text(json.dumps(protected, indent=2))
    changes = OUT/'ui13-source-changes.json'
    if changes.exists():
        entries = json.loads(changes.read_text())
        for path, value in entries.items():
            value['after'] = Path(path).read_text()
        changes.write_text(json.dumps(entries, ensure_ascii=False))
    print(json.dumps(result, ensure_ascii=False, indent=2))


def notes():
    r=json.loads((OUT/'result.json').read_text())
    content="""# 河图 UI13：固定面板导航、双列分组与独立节点页

## 最新要求与范围
按照用户2026-09-28T14:29:48Z的新文字要求，替代UI11旧稿中的“原位手风琴”和“节点选择FAB”。未取得文中视频3与参考视频进行逐帧比较，不能宣称看过。
- 顶部统一静态导航：概览、节点、订阅、连接、规则、规则集。真实RefPanel六个路由都复用同一组件，不按页面重排或把节点改名策略。顶栏固定，搜索区位于标签下方，不推移Tab。
- 节点外层为两列策略卡，10dp间距、正常字号80dp起高。名称/彩色图标、真实类型与已测数量、当前节点与延迟各占一行。没有下箭头、没有展开节点插入外层网格。大字体可增高/单列，不缩小系统字体。
- 点整卡进入独立原生大尺寸节点抽屉（二级目的地，不是在父卡下展开）。固定策略名/类型/全部测速操作，内部按需渲染双列节点；保留本组搜索、提供商、排序、密度及单/双列设置。返回、外部空白和下拖关闭均沿用原生交互，返回外层保留网格位置。
- 右下角“节点选择”悬浮胶囊及其点击热区移除；无效的“展开时折叠上一组”菜单也移除，不删除旧偏好数据。
- 核心选择与测速继续原146真实接口。点击立即显示等待反馈，回读确认后才改成功勾和父卡当前节点；失败保留旧选择。全测速四个一批，不使用随机数；单独点延迟不触发父卡选中。
- 子策略在二级内维护独立返回栈，重复路径截断防循环；当前组被配置移除时关闭对应二级。搜索匹配一个节点不篡改外层真实节点总数。
- 配置图标/本地缓存保留，补充TikTok彩色矢量回退和名称明确带国旗时的显示；不推测服务器国家。没有外链加载假图标替代真实配置。
- 首页四宫格、字体、移除的两个顶栏按钮、Dock、安全区与UI12动效文件不改。UI12 Material3严格1.5.0-alpha22继续保留，不升级依赖或代理二进制。

## 失败记录与修正
首次运行36439433971生产编译成功，219项中215通过、4失败；未发布安装包。三项Tab文字断言误读未合并父节点，改为读取真实合并文本并保留六路由、顺序、坐标与选中态的严格检查；新增Tab角色断言。另一个子策略操作只有onClickLabel，现补齐独立无障碍名称，原真实触摸/返回栈场景继续执行。没有删除、跳过或改为空回调。

## 验证
"""
    content+=f"Actions {os.environ['RUN_ID']}；{r['suites']}套{r['tests']['tests']}项通过，失败0、错误0、跳过0。\n"
    content+=f"{r['runtime_assets']}项原146运行资源哈希一致；{r['protected_files']}个本轮范围外生产/原生文件与UI12逐字节一致，{r['unchanged_original_test_files']}个原测试文件未改。\n"
    content+="旧面板21项中的手风琴/FAB预期按最新需求改为独立目的地、返回与位置保留；原选择、失败、真实测速、API保存检查保留。旧边界回弹测试只把第29个单列索引改为定位真实第30组；原物理拖动断言保留。新增16项，含实际六路由循环切换几何、10分组首屏、千节点固定头部、等待确认/错误确认/父卡同步、二级返回/配置移除/搜索、图标与动效。\n"
    content+=f"版本{r['version']}，versionCode{r['versionCode']}，包名{r['package']}。APK {r['size']}字节，SHA256 {r['sha256']}。\nUI10/UI11/UI12同签名 {r['certificate_sha256']}，未生成或导出私钥。\n"
    content+="\n## 边界\nDebug测试版；未在用户手机验收安装、Root联网、微信消息、GPU模糊和60/120fps。二级选择采用用户允许的原生大抽屉方案，不是新增Activity推入。正常字号卡片约80dp，长名称单行省略，二级显示名称并可长按查看节点全文；极端大字体允许滚动，不承诺任意屏幕10组同时显示。继承的Alpha界面依赖与签名缓存风险仍在，缓存缺失直接失败，不换新身份。旧不同签名包勿卸载或清配置。\n"
    Path('docs/UI92_RUNTIME146_UI13.md').write_text(content)
    agents=Path('AGENTS.md').read_text().replace('## 当前已交付：UI12','## UI12历史交付',1)
    latest=f"## 当前已交付：UI13\n\n版本{VERSION} / 1014；Actions {os.environ['RUN_ID']}；18组219检查通过。最新用户文字覆盖旧手风琴/FAB文稿：六个静态Tab共用，节点外层双列卡片，点卡片进入独立原生大尺寸抽屉，移除FAB与互斥展开选项。首页、UI12运动与146运行层不变。详情见docs/UI92_RUNTIME146_UI13.md；签名复用UI10/UI11/UI12，SHA256 `{r['sha256']}`。后续从已交付源码继续，不重放旧迁移。\n"
    Path('AGENTS.md').write_text(latest+agents)


def export():
    delivery = Path('delivery')
    delivery.mkdir(exist_ok=True)
    if (OUT/'app-commit.txt').exists():
        (delivery/'Hetu-UI92-Runtime146-UI13.apk').write_bytes(APK.read_bytes())
        result = json.loads((OUT/'result.json').read_text())
        result['app_commit'] = (OUT/'app-commit.txt').read_text().strip()
        (delivery/'result.json').write_text(json.dumps(result, ensure_ascii=False, indent=2))
        tracked = subprocess.check_output(['git', 'ls-files', '-z']).decode().split('\0')
        with zipfile.ZipFile(delivery/'Hetu-UI92-Runtime146-UI13-source.zip', 'w', zipfile.ZIP_DEFLATED) as archive:
            for name in tracked:
                path = Path(name)
                if name and path.is_file() and path.suffix.lower() not in {'.jks', '.keystore', '.p12', '.pfx', '.key', '.ttf', '.otf', '.woff', '.woff2'}:
                    archive.write(path, name)
    with zipfile.ZipFile(delivery/'Hetu-UI92-Runtime146-UI13-verification.zip', 'w', zipfile.ZIP_DEFLATED) as archive:
        for directory in ['integration-evidence', 'android-app/app/build/test-results/testDebugUnitTest',
            'android-app/app/build/reports/tests/testDebugUnitTest','android-app/app/build/outputs/panel13','android-app/app/build/outputs/panel11']:
            for path in Path(directory).rglob('*'):
                if path.is_file():
                    archive.write(path, str(path))


if __name__ == '__main__':
    commands = {'record': record, 'verify': verify, 'notes': notes, 'export': export}
    if len(sys.argv) != 2 or sys.argv[1] not in commands:
        raise SystemExit('Usage: finish_ui13.py record|verify|notes|export')
    commands[sys.argv[1]]()
