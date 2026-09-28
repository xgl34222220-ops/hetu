#!/usr/bin/env python3
"""Verify exact tested inputs/outputs and export UI12. No signing keys are read here."""
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

BASE = '621e4f22fce4e9c5f5c717eab1147fad20dab950'
ROOT = 'android-app/app/src/main/java/io/github/xgl34222220/hetu/'
VERSION = '0.4.0-ui92-r146.12'
MATERIAL3 = '1.5.0-alpha22'
FINGERPRINT = '701bbb0aaa5709cf2bebd96ff85ebd64c06cf6c3a2211ec21a9c51e534a5faad'
OUT = Path('integration-evidence')
APK = Path('android-app/app/build/outputs/apk/debug/app-debug.apk')
SUITES = {'CompactHomeDashboardTest', 'Runtime146ContractTest', 'Ui92IntegrationTest',
    'NodeSelectionContinuityTest', 'ProxyLatencyRegressionTest', 'NativeDockUi4Test',
    'HomeUi5RegressionTest', 'HomeUi6RegressionTest', 'HomeUi7InteractionTest',
    'HomeUi8ExperienceTest', 'HomeUi9BentoTest', 'HomeUi10GridTest', 'HomeUi11Test',
    'PanelModel11Test', 'PanelUi11Test', 'MotionModel12Test', 'MotionUi12Test'}


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
    allowed = {ROOT + name for name in ['NativeHomePolish.kt', 'HomeExperience8.kt',
        'CompactHomeDashboard.kt', 'PanelControls11.kt', 'PanelStrategy11.kt',
        'ReferenceProxyActivity.kt', 'ui/HetuGlassDock.kt']}
    protected = []
    protected_tests = []
    for path in subprocess.check_output(['git', 'ls-tree', '-r', '--name-only', BASE]).decode().splitlines():
        if (path.startswith('android-app/app/src/main/') or path.startswith('native/')) and path not in allowed:
            assert Path(path).read_bytes() == git_bytes(path), path
            protected.append(path)
        elif path.startswith('android-app/app/src/test/'):
            assert Path(path).read_bytes() == git_bytes(path), 'original test changed: ' + path
            protected_tests.append(path)
    old = git_bytes(ROOT + 'ReferenceProxyActivity.kt').decode()
    new = Path(ROOT + 'ReferenceProxyActivity.kt').read_text()
    start = 'internal fun RefInfoBottomSheet('
    end = '@Composable\nprivate fun RefSheetDragHandle()'
    assert new.split(start)[0] == old.split(start)[0], 'runtime or routing changed before log UI'
    assert new.split(end)[1] == old.split(end)[1], 'unexpected code after log UI'
    gradle = Path('android-app/app/build.gradle.kts').read_text()
    pinned = 'implementation("androidx.compose.material3:material3") { version { strictly("1.5.0-alpha22") } }'
    assert gradle.count(pinned) == 1, 'missing exact UI dependency lock'
    restored = gradle.replace('versionCode = 1013', 'versionCode = 1012').replace(VERSION,
        '0.4.0-ui92-r146.11').replace(pinned, 'implementation("androidx.compose.material3:material3")')
    assert restored.encode() == git_bytes('android-app/app/build.gradle.kts'), 'unreviewed build configuration change'
    helper = Path(ROOT + 'MotionMaterials12.kt').read_text()
    assert all(key not in helper for key in ['INVISIBLE_MEMBER', 'getDeclaredField', 'DismissVelocityDp'])
    graph = (OUT/'dependencies.txt').read_text()
    dependencies = {module: resolved_versions(graph, module) for module in [
        'androidx.compose.material3:material3-android',
        'androidx.compose.foundation:foundation-android', 'androidx.compose.ui:ui-android']}
    assert dependencies['androidx.compose.material3:material3-android'] == [MATERIAL3], dependencies
    suites = json.loads((OUT/'test-suites.json').read_text())
    assert {s['name'].rsplit('.', 1)[-1] for s in suites} == SUITES
    totals = {key: sum(int(s[key]) for s in suites) for key in ['tests', 'failures', 'errors', 'skipped']}
    assert totals == dict(tests=203, failures=0, errors=0, skipped=0), totals
    expected = json.load(open('UI92_RUNTIME146_INPUTS.json'))['original146_payload']
    with zipfile.ZipFile(APK) as archive:
        assert archive.testzip() is None
        for name, digest in expected.items():
            assert hashlib.sha256(archive.read(name)).hexdigest() == digest, name
    identity = (OUT/'apk-identity.txt').read_text()
    assert all(value in identity for value in ["name='io.github.xgl34222220.hetu'",
        "versionCode='1013'", "versionName='0.4.0-ui92-r146.12'"])
    certificate = signer((OUT/'apk-signature.txt').read_text())
    assert certificate == signer((OUT/'ui11-signature.txt').read_text())
    assert (OUT/'intended-signer.sha256').read_text().strip() == certificate
    result = dict(version=VERSION, versionCode=1013, package='io.github.xgl34222220.hetu',
        tests=totals, suites=len(suites), size=APK.stat().st_size,
        sha256=hashlib.sha256(APK.read_bytes()).hexdigest(), certificate_sha256=certificate,
        same_signer_as_ui11=True, runtime_assets=len(expected), protected_files=len(protected),
        unchanged_original_test_files=len(protected_tests), base=BASE, material3=MATERIAL3,
        resolved_ui_dependencies=dependencies,
        fling_policy='native AnchoredDraggableDefaults; no custom velocity threshold override')
    (OUT/'result.json').write_text(json.dumps(result, ensure_ascii=False, indent=2))
    (OUT/'protected-files.json').write_text(json.dumps(protected, indent=2))
    changes = OUT/'ui12-source-changes.json'
    if changes.exists():
        entries = json.loads(changes.read_text())
        for path, value in entries.items():
            value['after'] = Path(path).read_text()
        changes.write_text(json.dumps(entries, ensure_ascii=False))
    print(json.dumps(result, ensure_ascii=False, indent=2))


def notes():
    r = json.loads((OUT/'result.json').read_text())
    content = textwrap.dedent('''\
        # 河图 UI12：弹簧、材质与跟手交互

        ## 实际修改
        依据用户2026-09-28T13:07:09Z的文字诊断，不宣称看过两个视频。只精修UI11原生界面，不加入阅读器、章节或Vue模拟功能。
        - 网络详情、日志、API配置抽屉统一空间弹簧：阻尼0.84、刚度280；关闭采用临界阻尼。保留原生速度跟踪、内部滚动、返回和焦点，不反射私有API、不叠加抢占手势。
        - 背景模糊与0.96最小缩放由抽屉实际偏移和测量尺寸驱动，拖动中连续还原，关闭后释放。尺寸取自原生锚点视口，可跟随键盘/窗口变化。SDK31+且硬件加速、开启模糊时才启用背景RenderEffect；不满足条件则降级，抽屉文字不模糊。遮罩透明度仍采用原生独立动画，并非每一个材质参数都跟随拖动。
        - 日志右上角关闭先播放hide动画再移除弹窗，修正瞬间销毁。内容和操作区35/70ms错峰淡入，仅改变绘制，不延迟实际网络、保存或重启操作。
        - 筛选/布局浮层为原生焦点Popup、图标锚点定位和0.94到1弹簧过渡；退出保留内容直至动画完成。窄屏、RTL与长菜单保持可访问。浮层本体为轻半透材质，不虚称独立Popup窗口新增背景采样模糊。
        - 首页与策略列表仅在底部越界时增加单调有界阻尼和带松手速度的回弹，不替换正常列表惯性。顶部保留真实下拉刷新，可从正在回弹的位置重新接住内容。
        - Dock沿用Miuix/Haze真实材质，降低过强高光、折射及饱和度，指示胶囊采用0.84/300弹簧。卡片按压80ms响应、松开弹簧复位；布局高度和触摸范围不改。
        - 首页四宫格、系统字体、已删掉的两个顶栏按钮、IP完整显示、安全区与通知避让不推翻。节点仍原位展开、等待实际核心确认并真实测速；其他面板标签与原146运行层不改。

        ## 手势边界和失败记录
        新原生AnchoredDraggableDefaults的甩动边界为125dp/s，SheetState旧velocityThreshold参数不控制这条路径。无效的1100dp/s参数修改已撤销，HomeInteraction7.kt与UI11逐字节一致，不声称修改旧参数便改变了实际手感。
        - 36429557553：生产编译因稳定Material3隐藏Expressive API而失败，零测试、未发布。
        - 36430846940：202项中201通过；90px/600ms（150dp/s）被测试误称慢拖，实际上原生会甩动关闭。保留这条原始手势并明确验证关闭，增加90px/1200ms（75dp/s）慢拖回弹；另有90px/65ms快速关闭和拖住停顿后释放测试。未删掉原失败场景，未以直接调用回调替代真实触摸。
        - 36432162660：203项全部通过，源文件、运行资源和包信息校验通过；apksigner验证成功后，报告解析器未兼容新版“V2 Signer:”格式导致交付停止。实际两份证书指纹均为固定UI10/UI11身份。现同时解析新版和旧版证书输出，仍严格核对唯一签名和固定指纹，没有绕过签名验证。

        ## 界面依赖与风险
        Material3严格锁定1.5.0-alpha22，并核对实际解析出的Android变体。之前请求的最低版本alpha16曾被JetBrains Compose传递依赖提升为alpha22；不能把最低请求误报为实际使用版本。本轮锁定已经通过203项检查的实际版本。
        这是Alpha界面依赖，不称正式稳定版。其他显式依赖及Android SDK/工具链未升级，传递依赖实际图在验证包dependencies.txt；Compose UI/Foundation等最终版本另列于result.json。代理核心资源并未升级。

        ## 自动化与交付
        ''')
    content += f"Actions {os.environ['RUN_ID']}；{r['suites']}组{r['tests']['tests']}项，失败0、错误0、跳过0。\n"
    content += f"{r['runtime_assets']}项原146运行资产哈希一致；{r['protected_files']}个范围外生产/原生文件与UI11逐字节一致，{r['unchanged_original_test_files']}个原有测试文件未修改。ReferenceProxyActivity除日志弹窗函数外逐字节一致。\n"
    content += f"版本{r['version']}，内部号{r['versionCode']}，包名{r['package']}。APK {r['size']}字节；SHA256 {r['sha256']}。\n与发布UI10/UI11同签名：{r['certificate_sha256']}。未新建或导出私钥。\n"
    content += textwrap.dedent('''
        ## 验收边界
        本包仍为Debug测试版。自动化不代表用户手机Root联网、微信消息、GPU毛玻璃、60/120fps或长期稳定性实测；未完成两个视频逐帧对比。未新增整页侧滑返回或阅读器翻页；没有把全部页面/弹窗重新绘制。UI9、原146等不同签名旧包不能据此覆盖，不要卸载或清数据。签名缓存不是长期生产密钥托管，丢失时构建失败，不静默替换身份。
        ''')
    Path('docs/UI92_RUNTIME146_UI12.md').write_text(content)
    agents = Path('AGENTS.md').read_text().replace('## 当前已交付：UI11', '## UI11历史交付（UI12在此基础上继续）', 1)
    latest = f"## 当前已交付：UI12\n\n版本{VERSION} / 1013；Actions {os.environ['RUN_ID']}；17套203项全部通过。APK SHA256 `{r['sha256']}`。发布目标`ui92-r146.12-test`，与UI10/UI11同证书；以最终上传完成的附件为准，详见docs/UI92_RUNTIME146_UI12.md和result.json。\n\n只精修动效与材质：MotionMaterials12.kt统一公开运动主题、实际原生偏移驱动的缩放/模糊、弹性Popup和底部回弹。日志先hide再移除；首页和策略布局不推翻。Material3明确严格锁定实际已测试的1.5.0-alpha22，不能误报为最低请求alpha16或正式稳定依赖；解析图在证据包。甩动沿用原生路径，旧参数1100dp/s改动已撤销。未观看两个视频，不声称真机帧率和Root联网验收；不加入阅读器和整页侧滑。\n\n后续直接编译当前源码，不重放UI11或更早迁移。沿用原签名路径/缓存/固定指纹，缺失失败；解析新版V2 Signer证书格式，不绕过核验。UI12-last-attempt若存在仅是历史失败，不等同最终发布状态。\n\n"
    marker = '## UI11历史交付（UI12在此基础上继续）'
    if '## 当前已交付：UI12' not in agents:
        agents = agents.replace(marker, latest + marker, 1) if marker in agents else latest + agents
    Path('AGENTS.md').write_text(agents)


def export():
    delivery = Path('delivery')
    delivery.mkdir(exist_ok=True)
    if (OUT/'app-commit.txt').exists():
        (delivery/'Hetu-UI92-Runtime146-UI12.apk').write_bytes(APK.read_bytes())
        result = json.loads((OUT/'result.json').read_text())
        result['app_commit'] = (OUT/'app-commit.txt').read_text().strip()
        (delivery/'result.json').write_text(json.dumps(result, ensure_ascii=False, indent=2))
        tracked = subprocess.check_output(['git', 'ls-files', '-z']).decode().split('\0')
        with zipfile.ZipFile(delivery/'Hetu-UI92-Runtime146-UI12-source.zip', 'w', zipfile.ZIP_DEFLATED) as archive:
            for name in tracked:
                path = Path(name)
                if name and path.is_file() and path.suffix.lower() not in {'.jks', '.keystore', '.p12', '.pfx', '.key', '.ttf', '.otf', '.woff', '.woff2'}:
                    archive.write(path, name)
    with zipfile.ZipFile(delivery/'Hetu-UI92-Runtime146-UI12-verification.zip', 'w', zipfile.ZIP_DEFLATED) as archive:
        for directory in ['integration-evidence', 'android-app/app/build/test-results/testDebugUnitTest',
            'android-app/app/build/reports/tests/testDebugUnitTest']:
            for path in Path(directory).rglob('*'):
                if path.is_file():
                    archive.write(path, str(path))


if __name__ == '__main__':
    commands = {'record': record, 'verify': verify, 'notes': notes, 'export': export}
    if len(sys.argv) != 2 or sys.argv[1] not in commands:
        raise SystemExit('Usage: finish_ui12.py record|verify|notes|export')
    commands[sys.argv[1]]()
