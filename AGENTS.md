## 当前接续：V20.85 实际PDF130页与认证证据

原仓库 xgl34222220-ops/hetu / test/v20.76-new-ui，从492a9c继续；没有重复实现或取消CI。最新完整验证应用270f559424f3ef6e3503d2431ea24f81d295d3d7 /run37317099530四job success，13:56:02UTC完成；479/49XML零失败错误跳过，API35/36各64=59+5、21palette、Root0、loopback401→200/Bearer和隔离资源清理通过。APK134783735 bytes/SHA58c4c2726622ba30537b53f287fe85e5960e14e21c762afd126a96b510ffeb95。证据docs/qa/20261005-v2085-270-installation-success.json。

本条追加候选修正工具标题位置和图标色、细分隔线透明度、YAML13.5/18sp、读取失败图标、设置说明字重/自启原句/备份按钮/关于说明，文件夹原稿轮廓及文件表单字重颜色，并将现有应用菜单/广告状态组件接入旧二级工具入口。共享PDF顶栏/表单/紧凑提示均为默认关闭或空值opt-in，首页/panel默认路径精确冻结。候选尚待自己的新CI，不能挪用270结果。追加v2085同层38修改、334冻结，422原基线和479总数保留；核心/权限/签名与历史补丁冻结。

PDF03A49、03B48、0433均实际读并130当前截图有入口映射；字面像素1:1和130完整功能验收未全部通过。197434实际为Google帐号认证错误而非CAPTCHA；用户明确Google Play商店打不开，其他代理正常。实际重新读取197152仅显示Mihomo控制器401，与Play认证不同。历史GMS两条分流链、独立DNS重定向与河图自身UID直测已证据对照，但缺当前故障时DNS/认证HTTP/TLS及另一代理同刻链路，Google根因未知/真机修复未验证。新增25条Google元数据配额及8分类用例，只改证据保留、不改DNS/链路/权限；AOSP控制器认证不是Google账号验收。

AVD/按钮禁用/旧间距断言失败证据和V20.83 a93交付记录全部保留。用户已授权本测试分支代码/UI/tests/CI/docs提交与隔离验收；不操作手机/帐号/网络安全设置、不合并main、不发布部署、无hook。详细过程见docs/V20.85_PDF_CONTINUATION.md；入口/captureSHA见docs/qa/20261005-tools-settings-pdf-map.json。

## 当前续接：V20.84 控制接口鉴权与面板缺测状态（构建通过，安装续验待核）

继续 `test/v20.76-new-ui`，以已实际编译的 `a93a756d8578cf3afa60ee090405535ad097e49e` 为底座；其完整 Actions `37240041766` 现四 job 均 success，保留下面交付时记录的原状态。本轮 versionCode 2084 / `0.12.14-v20-auth`，独立追加 `updates/v2084-controller-auth/runtime.patch`，原 V20.82/83 清单及补丁字节不变。

截图实际为 401 Unauthorized，不能仅凭截图确定手机 API 来源或活动密钥。修复请求偏好快照、防止旧迁移覆盖本机密钥、实际请求模式鉴权提示、失败读取数据保留、原生面板重试/API 设置与错误图标；不猜测或自动替换当前密钥，不重启用户核心。新增 5 类 30 项单测，须实际核验原 392/42 XML 与新增 30/5 XML，合计 422。保留原安装 59 项及 21 配色，每 API 新增 5 项原生 401→200 隔离夹具；仅模拟运行提示，不注入健康 Root 状态。实际构建及 API35/36 安装未验证前不得描述为通过。

首轮 `7536b695` / Actions `37243939715`：应用编译与载荷/ABI/图标通过；新增 ToastFeedbackTest 显式导入 `assertExists` 编译失败，单测未执行，lint 与本轮 API35/36 安装 skipped。已仅移除错误导入并保留实际渲染断言，独立补丁重新生成；原失败证据保留于 `docs/ci/V20.84_FIRST_FAILURE.json`，修正后的完整重跑尚未验证。

第二轮 `934bc32` / Actions `37244396784`：实际 47 XML / 422 tests / 3 failures / 0 errors/skipped，lint/最终 APK signer 与双 API 安装 skipped。保留 `docs/ci/V20.84_SECOND_FAILURE.json`。新测试显式清空静态历史夹具并等待实际读取闸门后验证取消；旧 HistoryRecord 的 pause/pending 增加 volatile，原安全断言仅增加失败详情。原 392 与新增 30 数量不变；第三轮仍须完整验证，字段可见性不是已确认的生产根因。

第三轮 `6666bd8c` / Actions `37269048184`：实际 422/1 failure/0 error/skipped，新 30 全过；仅原 stop/history latch 失败，诊断显示 panelReady=true/readFailed=false/historyPaused=true，volatile 未解决。证据保留 `docs/ci/V20.84_THIRD_FAILURE.json`。两套 Shadow 针对同一个 Kotlin history 单例存在夹具绑定冲突疑点；第四轮统一复用原可暂停 HistoryRecord，仅补只读采样审计并显式核对实际绑定，不改生产历史保护或原断言，仍待完整重跑。

第四轮实际编译 `121e0ab99904e7ea862b1dcb350a59169aeab0cd` / Actions `37270231154`：build `111635473569` 与旧崩溃复现 `111635473860` success；47 XML / 422 tests（原 392 +新增 30）/ 0 failures/errors/skipped，lint 0 Error/Fatal、298 Warning/7 Hint，23 载荷、687 ABI 引用零缺失与固定签名通过。整体仍为 failure：API35 `111638870907`、API36 `111638870864` 在新增夹具目标检查因 console AVD 名称返回空而拒绝，尚未执行新增鉴权 5 项；两 artifact 无 results.json，不推算原 59/21 或新 64 通过。保留 `docs/ci/V20.84_FOURTH_FAILURE.json`。

仅修测试监督进程与目标识别，不改 Android 编译输入：固定 APK `Hetu-0.12.14-v20-auth.apk`，134727773 bytes，SHA256 `33d60ce01b63f40c6b6ccd8c63c5dd1ea38b85d4d25e37c32f48130c4207ff50`；其 Android 源码树 `2954ad5c83131050acd3ffd52eb63d8689a8de33`。待严格固定原 build/job、源码树、产物 ZIP 摘要、实际 422 XML、载荷/ABI/证书的双 API 安装续测，原 59 +新增 5 项与 21 配色全部保留。续测提交不作为重新编译提交，原第四轮 full-run failure 不改写为 success。

首次固定 APK 安装续测 `c4e58f7fe2dfcb7ecef717641c6c883e64682f32` / Actions `37273953593` 仍失败：API35 `111646751627`、API36 `111646751764` 各实际 72 项 host 全过，但新复用守卫误设 manifest ZIP 只有 manifest.json，实际固定 ZIP 还有 apk-metadata.txt / apk-signature.txt，故在模拟器启动与安装前拒绝。保留 `docs/ci/V20.84_FIRST_INSTALLATION_FAILURE.json`，安装通过计数保持未执行；仅修正精确三成员结构与身份文本校验，新增回归后守卫 19 项通过，固定 ZIP 摘要与真实 aapt/apksigner 检查不放宽。原编译及 APK 不变，第二次安装续测仍待真实验收。

原 23 载荷、Root runtime 151、核心/native/service.d、签名、Manifest/权限与依赖不变。无 hook、不操作用户设备/网络/安全设置、不永久删除数据、不合并主分支或正式发布。手机实际鉴权恢复、K80/OEM/KernelSU、真机 Root 与长期网络仍未验证。详细范围见 `docs/V20.84_CONTROLLER_AUTH.md`。

## 上次交付：V20.83 工具、设置与首页面板交互（构建及独立安装验收通过）

继续原 `test/v20.76-new-ui`；实际编译提交 `a93a756d8578cf3afa60ee090405535ad097e49e`。首页/面板沿用已有 UI，仅补实际下拉刷新、动效、配置图标读取与节点展开尺寸；完成工具、配置、日志、诊断与设置页面，保留真实功能回调及编辑器返回状态。有效运行源码仍以 V20.81 `37e963d4` 为基底，UI 基底 `aa599a53`；生成源码与 checkout 一致，68 项 UI 输入补丁 SHA256 `a466e4fce0e2e4ec9564760a1089b250f24d90032b123faea90fcee4c241e514`，受保护运行源码不变。

Actions `37240041766` 的 build `111546766975` 与旧 V20.74/API36 闪退复现 job `111546767044` 均 success；实际 42 XML / 392 tests / 0 failures/errors/skipped，lint 0 Error/Fatal、298 Warning，13 项实际滚动边界 host 回归、687 ABI 构造引用零缺失、23 载荷与固定签名通过。该 full run 的重复安装 job 在记录时仍运行，不能称其全量 success。独立固定 APK 安装续测 `37240041754` 已整体 success：API35 `111546766773`、API36 `111546766922` 各 59 项检查（各 21 配色）通过，原诊断/存储/历史/拒绝 Root/原生 WebView/HTTP503 断言保留，5 个原生 WebView 状态各不相同，Root mutation 0。续测严格固定原构建、源码树、两类 job、artifact ZIP 摘要及 APK；原应用编译 `7d082aca` 的相同 APK 又由当前 a93 原 workflow 独立重建并重跑 392 项，不把续测本身描述成重新编译。完整证据见 `docs/ci/V20.83_RESULT.json`。

交付 `Hetu-0.12.13-v20-ui.apk`：134721428 bytes，SHA256 `24f96438c1ebe1e648e23352e1191aa91ab472ee938cc62e1eba39dd2abe7c57`；versionCode 2083，Root runtime 151，证书 SHA256 `701bbb0aaa5709cf2bebd96ff85ebd64c06cf6c3a2211ec21a9c51e534a5faad`。分块与完整 APK、23 项载荷逐项回读通过，14 张已审阅截图在后续构建中字节一致。全部六轮失败及修复记录保留于 `docs/ci/V20.83_*_FAILURE.json`，不覆盖失败结论。本记录之后的提交仅为文档，不作为已编译提交。

原核心/native/service.d、Root、签名、Manifest/权限及依赖边界保持；无 hook，不合并主分支或正式发布，不操作用户设备/安全网络设置、不永久删除数据。截图清单不等于像素一比一验收；安装仅 AOSP 隔离夹具，K80/OEM/KernelSU、真机 Root 自启、长期 Google/微信网络、GPU 模糊与帧率仍未验证。

## 此前续接：V20.81 合法栈位置（全量CI已核实通过）

沿原test/v20.75-theme-compat，应用37e963d4 / Actions37171744564 attempt1四job全部success；实际348单测0fail/error/skipped，JournalReliability21执行，6host+60shell、过滤ENOENT、lint0error、687ABI/23载荷/固定签名通过；API35/36各56（各21配色），Root mutation0。符号夹具旧捕获/复制三处redacted，新保留Kotlin短横线及init/clinit，5类恶意符号仍拒绝；安装报告位置/UUID/脱敏/满额旧段保留通过。实际下载APK133353242 bytes，SHA2561319ef860d4aedcfcb4e57f029df0a45e285d67ad7dcd3415a7d8c0cb1dbe425，23载荷逐项回读匹配。详见docs/ci/V20.81_RESULT.json、docs/V20.81_TRACE_SYMBOLS.md。

保留ed326756/37163668343及V20.79、V20.80首轮失败和最终证据；不把旧262/旧Android15-16当本轮结果。Root151、核心/service.d、原UI方向、固定签名、Manifest/权限保持，无hook、不操作用户设备/安全网络设置、不永久删除用户数据、不合并主分支或正式发布部署。真机KernelSU/OEM、长期Google微信、长期IPv6/TUN/eBPF仍未验证；服务/状态重建仅隔离夹具通过，真实强杀/掉电后日志完整性及异步计数持久化未测。间歇断网未确认解决。

## 当前续接：V20.80 日志限额与脱敏（全量CI已核实通过）

原应用b49c6d18，测试导入修正52ad5701，Actions37169862397四jobsuccess；首轮37169355897测试编译失败、单测未执行/安装skipped证据保留。实际344单测0fail/error/skipped、6host+60shell、过滤ENOENT、lint0error、687ABI/23载荷/固定签名通过；API35/36各56（各21配色），Root mutation0。CI3000写入旧501000→新8016≤8192预算/2952拒绝；故障200190→217字节，脱敏/截断/最新UUID/复制实际函数/超限旧段校验保留均通过。见docs/ci/V20.80_RESULT.json及docs/V20.80_JOURNAL_BOUNDS.md。

内容总限额2MiB满额暂停，所有旧段保留；生成ID不等于落盘，缺口可见。Root151/core/service.d/签名/Manifest/原UI方向/权限保持；无hook、不操作用户设备/安全网络设置、不永久删除用户数据、不合并主分支或正式发布部署。真机KernelSU、Google/微信长期断连、长期IPv6/TUN/eBPF未验证，间歇断网未确认。下一批为已复现的Kotlin/JVM合法符号被误过滤，须独立完整CI，不能拿本轮结果替代。

## 当前续接：V20.79 恢复背压（全量 CI 已核实通过）

原分支 `test/v20.75-theme-compat`，应用 `82be531f`，Actions `37166956898` 四个job success。实际324单测无失败/错误/跳过，6host+60shell、生产过滤ENOENT、lint0error、687ABI/23载荷/固定签名通过；API35/36各52安装检查（各21配色），Root mutation0。积压夹具旧512→新1、取消计时器旧4096→新0，服务重建与迟到结果门禁13项通过。保留ed326756/37163668343上一轮及全部失败证据。详见docs/ci/V20.79_RESULT.json与docs/V20.79_RECOVERY_BACKPRESSURE.md。

Root151/core/service.d/签名/Manifest/原UI方向不变。未知core在wanted+autoStart开启时继续有界间隔等待，最多一个重试；既已进入Root事务不强制取消。用户设备、KernelSU/OEM、长期Google微信/IPv6/TUN/eBPF未验证，不能宣称间歇断网解决。日志存储边界与脱敏为下一批；禁止永久删除历史、扩权、合并主分支或正式发布部署。

## 当前续接：V20.78 网络诊断（全量 CI 已核实通过）

最终应用提交 `47609a6925eb411def74ea6781eaa77b88a6b7da`，定位脚本/CI 修正提交 `95b6e171f0f078a0477d200cdb4c391629b62234`，Actions `37163668343` 四个 job 全部 success。本轮实际 XML 311 tests / 0 failures/errors/skipped；6 项 host、60 项 shell、生产过滤 ENOENT 前后验证通过；lint 零 error（278 条 warning 保留），687 个配色构造引用零缺失，23 项运行载荷与固定签名通过。API35/36 各 52 项安装检查通过，各含 21 种配色组合的实际脚本页交互。五种 WebView 画面各自不同，去除状态/导航栏后仍不同；已人工核对连接列表与节点弹窗。Root mutation 为 0。APK 133347510 bytes，SHA256 `33c48db99d98c19b8e91bb44c5441e8c32250e083dc459d97c2403f19c697966`。完整证据见 `docs/ci/V20.78_RESULT.json` 和 `docs/V20.78_NETWORK_TRACES.md`。

V20.77 与 V20.78 首轮失败记录保留；最终 V20.78 重跑覆盖两批应用改动，不将旧失败运行改为成功。API36 日志另含 shell UID2000 的 uiautomator 进程3960错误，应用进程2375与之不同；不把日志描述为“所有进程无异常”。调用方 blocked 回调只反映河图 UID，不能据此判断 GMS 或整台手机被禁网。真机间歇断网、KernelSU/OEM切网/开机及长期 IPv6/TUN/eBPF 未验收。继续原分支与 UI/核心/签名约束，无 hook、不扩权、不操作用户设备、不合并主分支或正式发布部署。

### 首轮与重跑历史（不覆盖失败证据）

V20.78 首轮 47609a69 / 37162276140：实际311单测、60 shell、lint/ABI/23载荷/签名均通过，但两API安装因脚本把同一WebView的嵌套无障碍文档计为第二个容器而失败。已据实际失败树保留无文本拓扑fixture，增加6项host回归，本地全部通过；只选择唯一顶层容器，独立双容器仍拒绝。应用代码不变，测试/CI修正后全量重跑；最终安装结果仍须核实，不能标为已通过。

仍使用 `test/v20.75-theme-compat`，从 V20.76 成功有效源码接续，不重放 UI 迁移，不开重复分支/实现。V20.77 应用 6a94d872 首轮测试夹具隐藏 API 编译失败；34ba4778 修正后实际 292 单测、lint、载荷/签名/ABI 通过，Actions 37160754265 的 API35/36 均在原生 WebView 截图重复门禁失败，不能称完整通过。所有证据保留在 docs/V20.77_NETWORK_RECOVERY.md。

V20.78 在相同补丁链追加 `updates/v2078-network-traces/runtime.patch`；2078 / 0.12.8-v20，Root 151、原 native/core/service.d、固定证书不变。先修安装测试同步（CDP 只读定位 + 实际 AOSP 原生点击 + 原生 UI 快照后截图），保留五截图唯一性和原门禁。新增网络事件 noBackup 追加分段、错误 UUID/网络代次/原因类型、每固定出口目标结果及 stale-result、独立无 Root 报告入口；旧分段不自动删除。写盘/队列失败报告缺口，不能改变健康或 wanted。完整 CI 预计311选中单测、60 shell、全部原载荷/ABI/lint/签名与API35/36各52安装检查，结果须待当轮核实，旧76/75结果不计为本轮通过。

参考源码、文档和许可证已核对，见 docs/V20.78_NETWORK_TRACES.md；只借鉴设计，沿用原 UI，不引入 hook 或新安全敏感权限。Google 可达独立于 session baseline、规则、DNS 与守护完整性。用户手机间歇断网仍未确认；禁止操作用户设备/网络/安全设置、扩大 Root/KVM/SELinux 权限、合并主分支、正式发布部署或永久删除用户数据。继续现有失败优先，缺相应真机的项继续明确列为未验证。

## 当前测试交付：V20.76 安全运行记录修复与过滤导出

继续 `test/v20.75-theme-compat` 的已交付 V20.75（分支原 HEAD 069a7ed），不创建重复分支，不合并主分支，不发布或部署。追加 `updates/v2076-session-filter/runtime.patch`，versionCode 2076 / 0.12.6-v20，Root runtime 151；21 个原 native/core 与 service.d 载荷、固定签名保持。最新用户禁止操作手机或修改其网络、安全设置，测试仅限已有授权的隔离 shell/JVM/AOSP 环境。

旧 r149 session 仅多出末尾 GOOGLE_FIREWALL_CLEAN 行时，现有不可变 baseline checksum、PID、规则、路由、监听与守护身份全部核对后，允许用户在工具中显式修复运行记录。保留原始 baseline；不重建缺失/损坏 baseline，不将现网快照登记为健康，不强制重启核心，不自动把已应用 runtime 149 改为 151。新版独立检查器用于旧部署的诊断，读检查不得使 ProxyControlEpoch 的观察票据失效。任何观察不完整均失败关闭，Google 可达独立报告。

过滤导出迁至 noBackup 稳定目录；按不可变 generation 成对发布、hash 校验、保留旧交接文件。实际旧/new 导出器的缓存清理故障注入、真实 Android 导出、第二 provider 写入失败、共享主题重建与 Monet 切换回归均纳入本轮 CI。最终应用提交 a48ece29289a34b1812c67fa0f82e0252362efc3，Actions 37157127775 全部 success；278 项单测、lint、687 个配色构造引用零缺失、23 项载荷与固定签名验证通过。API35/36 各 50 项安装检查通过，各含 21 种配色与修复入口/拒绝授权报告。旧2074/API36闪退本轮再次精确复现。不引用旧 262/旧 Android 15/16 作为本轮通过。APK SHA256 d3c481f34a08134107ab9c7f9b5ae397164f446988ea41428420f7faa17726d0；未做真机 Root 重启、长期切网或间歇断网验收。详见 docs/V20.76_SESSION_FILTER.md。禁止访问用户设备、扩大 KVM/Root/SELinux 等权限；本轮不得永久删除用户数据。

## 当前交付：V20.75 自定义配色兼容修复

从已交付 V20.74 源码继续，追加 `updates/v2075-theme-compat/runtime.patch`，不重放历史 UI 迁移。versionCode 2075，versionName 0.12.5-v20；Root runtime 保持 150，原核心/native 载荷、Root 脚本与 service.d 自启保持已验证 V20.74 字节与固定签名。

用户最新真机诊断：当前 App 2074，但已部署 Root 149；`upgrade-required/session-manifest-missing` 仍来自旧运行基线，须显式重启代理部署 150，不能隐藏警告、无条件重新快照现网或自动强制重启。Google 服务已有双向流量，清理 checked=6/removed=0/failed=0，不声称已确认 Google 防火墙断网。

历史 2072 的 `ProxyScriptsActivity` 闪退记录为 `NoSuchMethodError` / MaterialKolor DynamicScheme。已检查 2074 实际 APK DEX：MaterialKolor 2.0.0 引用的 Scheme 构造方法在实际 MCU 5.0.1 中不存在。MiuiX 0.9.4 引入 MCU 5.0.1，主题依赖也统一严格锁到 5.0.1，保留全部 8 种配色、系统 Monet、强调色与原配色算法。默认蓝色/TonalSpot 分支不会进入有缺陷代码，之前默认配色 smoke 因此漏报。按 `.github/workflows/hetu-build.yml` 构建，补真实共享主题回归、APK 构造方法存在性校验、API35/36 自定义配色实际脚本页交互和 2074/Android16 旧包预期闪退复现。结果须以最终 Actions 与 artifact 为准；AOSP 仍不等于用户手机 Root 重启验收。

应用提交 494ef31c70a29e7f18a67ad617493c11bea133ba，Actions 37132069914 attempt 2 全部 success，262 项单测与 lint 通过，API35/36 各 45 项实际安装检查通过（各 18 项自定义配色脚本页），APK ABI 构造方法缺失 0，23 项 runtime 打包 hash 通过。APK SHA-256 27f34ad301f42f0828a9c9b5110d04c8de18dfee22d0489cec14174799c2dc49，固定签名 701bbb0aaa5709cf2bebd96ff85ebd64c06cf6c3a2211ec21a9c51e534a5faad。首轮列表等待 1 项失败及原提交不改断言重跑通过记录保留在 docs/V20.75_THEME_COMPAT.md，失败原因未确定。旧2074/API36闪退已精确复现；不声称真机Root重启或Google间歇问题全部验收。

## 当前交付：V20.74 开机运行健康基线修复

沿用已交付 V20.73 底座与固定签名，追加 `updates/v2074-boot-health/runtime.patch`，构建用 `.github/workflows/hetu-build.yml`。versionCode 2074，versionName 0.12.4-v20，Root runtime 150。全部界面与功能、21 个原始 native/core 载荷、service.d 自启实现保持。

确认并复现启动顺序错误：`health_record` 复制 session 后才追加 `GOOGLE_FIREWALL_CLEAN`，导致完整性快照始终不匹配、状态返回 `upgrade-required/session-manifest-missing`，并跳过仅处理 degraded 的自动修复。先完成 session 再保存快照；写入失败停止启动。不能用核心 PID 或远程测速结果替代健康检查，不能清空系统防火墙。Google 超时本身不等于已确认 OEM 防火墙拦截。

应用提交 `ae544982c501c61af2d8687169194bb604b9eb31`，Actions `37122739246` 全部 success。本地/CI 实际 shell 11 项健康/修复、17 项开机、17 项 Google 清理夹具通过；259 项 Android 单测、lint、23 项 runtime 打包 hash、固定签名验证通过。API35/36 各 27 项实际安装交互通过，无闪退/ANR。APK SHA-256 `ea8569b8d3c1aea7ddd969eb9e1f8a6d721dd653ebf554147ae3bbaa6c5d303f`。首轮错误测试预期的失败与完整重建结果保留在 `docs/V20.74_BOOT_HEALTH.md`。

覆盖安装后须显式重启一次代理更新 Root 脚本和基线；版本号不自动强制重启已有核心。AOSP 安装与 shell 夹具不等于用户手机 Root 重启验收，Google 超时的真机根因仍未确认。

## 当前交付：V20.73 脚本与开机自启修复

以已验证 V20.71 有效源码、V20.72 补丁及 `updates/v2073-scripts-boot/runtime.patch` 为底座，使用 `.github/workflows/hetu-build.yml`；不重放历史 UI 覆盖。应用提交 `1321b2d6fddf8abae82c9bf98483ba58c33748d4`，versionCode 2073，versionName 0.12.3-v20，Root runtime 149。用户本轮要求修复运行问题，Root 开机脚本与运行层修复见 `docs/V20.73_SCRIPTS_BOOT.md`，不沿用下文历史“后端未改”作为本版描述。

保留全部界面/功能，21 个原始 native/core 载荷不变。沿用固定签名 SHA-256 `701bbb0aaa5709cf2bebd96ff85ebd64c06cf6c3a2211ec21a9c51e534a5faad`；缓存/证书缺失失败关闭。开机自启须实际安装 Root service.d 脚本并校验成功部署配置才保存为开启。脚本文件列表保留真实文件名；导入后台限量读取，错误结果明确显示诊断。Google 防火墙回归保留。

259 个 Android 单测和 lint 通过；Android 15/16 各 27 个实际安装交互检查通过，最终 CI 37118971993 attempt 2 成功；Root 重启只验证受控夹具，没有用户手机/Magisk/KernelSU/APatch 真机验收。用户所述脚本点击闪退未在旧包 AOSP 上复现，不声称设备根因已经确认。APK 与安装测试身份、后续交付证据见本版文档。

## 当前交付：V20 整体重构（以此为准）

用户要求“按你的审美完全重构，包括功能”。V20 用单一 `HetuActivity` + `app/` 下的新 Compose 界面取代全部旧 UI；用户明确要求**不删除任何功能**：旧 UI 文件、功能页、测试、脚本、工作流全部保留，旧页面只换配色并由新设置页进入；下文 UI10–UI15 的首页/面板布局约束对新主界面不再适用。后端（Root、Mihomo、规则、DNS VPN、服务）未改，除：`MihomoStartupConfig` 在广告过滤开启且源配置无 sniffer 时注入嗅探。构建用 `.github/workflows/hetu-build.yml`。详情 `CHANGELOG-V20.md`。

## 当前已交付：UI15

0.4.0-ui92-r146.15/1016，针对用户提供的启动诊断修复UI14集合别名50次上限误拦；已在哈希完全匹配的私有原配置复现旧错误及改后配置生成。界面、Root脚本、核心与依赖不改，不能重放旧UI方案；也不声称微信延迟已修复。详情docs/UI92_RUNTIME146_UI15.md。Actions 36456797711，277项通过。同UI10至UI14签名，缺失时失败关闭。

## UI14历史交付

0.4.0-ui92-r146.14/1015；继续原UI13，用户最新需求恢复独立避让FAB、安静测速和更小节点抽屉。明确授权排查运行层，本轮仅做docs/UI92_RUNTIME146_UI14.md所列配置、过滤快照和诊断修改，原146二进制不换。微信根因尚未实机确认；不声称联网问题全部修复。按此源码继续，不重放旧迁移。签名沿用UI10—UI13且失败关闭。Actions 36449592454，250项通过。

## UI13历史交付

版本0.4.0-ui92-r146.13 / 1014；Actions 36440475069；18组219检查通过。最新用户文字覆盖旧手风琴/FAB文稿：六个静态Tab共用，节点外层双列卡片，点卡片进入独立原生大尺寸抽屉，移除FAB与互斥展开选项。首页、UI12运动与146运行层不变。详情见docs/UI92_RUNTIME146_UI13.md；签名复用UI10/UI11/UI12，SHA256 `48c90fb7921cd50842129cfbc034473a91f0a7269476d7c0851466f16e03f29b`。后续从已交付源码继续，不重放旧迁移。
# 河图开发基线：UI92持续精修 + 原146功能

用户明确要求“我只要这个版本的所有UI界面，其它的用146版本的”，并授权直接推送GitHub、编译测试APK、在合并版本上继续精修原生UI。新的Markdown/Vue稿是视觉与交互要求，不授权引入模拟服务、改名BoxProxy或切回旧分支。

## UI12历史交付

版本0.4.0-ui92-r146.12 / 1013；Actions 36433913372；17套203项全部通过。APK SHA256 `3810da2eaf66b673c26e8e6aaae5423111b9129e7dffd6ec33e96ea05a7caeb8`。发布目标`ui92-r146.12-test`，与UI10/UI11同证书；以最终上传完成的附件为准，详见docs/UI92_RUNTIME146_UI12.md和result.json。

只精修动效与材质：MotionMaterials12.kt统一公开运动主题、实际原生偏移驱动的缩放/模糊、弹性Popup和底部回弹。日志先hide再移除；首页和策略布局不推翻。Material3明确严格锁定实际已测试的1.5.0-alpha22，不能误报为最低请求alpha16或正式稳定依赖；解析图在证据包。甩动沿用原生路径，旧参数1100dp/s改动已撤销。未观看两个视频，不声称真机帧率和Root联网验收；不加入阅读器和整页侧滑。

后续直接编译当前源码，不重放UI11或更早迁移。沿用原签名路径/缓存/固定指纹，缺失失败；解析新版V2 Signer证书格式，不绕过核验。UI12-last-attempt若存在仅是历史失败，不等同最终发布状态。

## UI11历史交付（UI12在此基础上继续）

- 已验证应用提交：`c445d9c1c27e751d81654c602a259591b9ebeb55`，已推入main。本说明提交不改变发布APK对应的应用代码。
- 版本`0.4.0-ui92-r146.11`，versionCode `1012`，包名`io.github.xgl34222220.hetu`，不带`.preview`。
- 已发布预发行`ui92-r146.11-test`。APK `Hetu-UI92-Runtime146-UI11.apk`、源码ZIP、验证ZIP和result.json均已核实uploaded。
- APK127996890字节，SHA-256 `0c8c05dcc1e5e7a0bd910ca49968bfa94debe4f39b7410f81cd8a48cfe241e62`，与GitHub发布附件digest一致。
- 成功Actions运行`36424616625`，job`108935410774`：编译、15组181项测试、APK身份/签名/资源校验、实际源码写回与发布全部完成。181通过，0失败、0错误、0跳过。
- 22项原146运行资产SHA-256一致；190个本轮范围外生产/原生文件与UI10逐字节一致。ReferenceProxyActivity仅新增策略页路由和工具中的网络诊断入口，其余部分精确比对不变。
- 本轮精确基线UI10及说明提交：`331a0b19ad4ada535c8102e0eeb3657be7e24116`；UI10应用提交`10aa22a551c45d29a501114a1b1e1089f6dfd183`。
- UI11与已发布UI10已核实同包名、相同证书、更高versionCode；并非又一次不同签名测试包。但尚未在用户设备实际覆盖安装，不等于设备安装已验收。
- 本轮完整交付说明见`docs/UI92_RUNTIME146_UI11.md`；UI10及更早记录仍在docs，不重写历史。

## 最新用户需求：主页减按钮 + 面板节点页改造

2026-09-28T12:17:41Z用户提供首页截图178045.jpg和`粘贴的 markdown (1)。md(9)`，明确说首页右上角两个按钮可以去掉，第二份针对面板节点页面。两者必须区分：不能将面板四按钮加回首页，也不能把本次说成全应用所有页面重新设计。只有首页静态图和完整文字/代码稿，不宣称看过文稿提到的两个视频。

### 首页保留与变化

- 首页右上角“刷新状态”“更多首页功能”已从实际组合树移除，不是隐藏图标留下命中区。
- 保留固定居中20sp标题、真实下拉刷新。为TalkBack等提供“刷新首页状态”自定义无障碍动作；无定时器假刷新。
- 原菜单功能保留可用入口：网络诊断新增到工具；广告过滤仍在工具；连接仍在面板→连接及网速入口；代理设置仍在设置。
- 首页状态主卡、WebUI/日志双磁贴、网络延迟及独立四宫格沿用UI10，不再次把WAN/网速合为双联面板，不换色、不改系统字族、不调整本轮无关卡片比例。
- UI10基础约束继续有效：20dp数据卡连续圆角、12dp内边距、10dp网格间距；IP14sp，正常IPv4完整可见且不换行，按实际字体测量空间，不足时标签放本卡上方。超长IPv6/极端大字体可横滑。
- 数字15sp、小单位11sp、延迟18sp；6dp流量/CPU底轨及真实比例、异常小胶囊保留。主卡26dp圆角、76dp徽标、真实生命周期、胶囊操作和至少48dp触摸范围保留。
- 页面背景#F4F6FB；Dock实际测量高度+10dp独立避让；顶部通知独立布局槽仍位于安全区下8dp，出现时让位、消失收回，不覆盖停止键。
- 不能因过去默认字体393×852和360×800通过就宣称此次用户自定义字体/显示缩放真机截图中的卡尾已修好。本轮没有重新调整首屏高度算法；极小屏/大字体/长配置保留正常滚动，不承诺所有设备零滚动。

### 面板策略与节点

- `RefPanel`在Groups标签进入`PanelStrategyRoute11`，后者使用真实原146 `ProxyDashboardRepository`，不是一个未接入的组件演示。其余概览、订阅、连接、规则、规则集继续原有实现。
- 本策略页固定顶栏包含搜索、筛选、布局、API设置四个48dp操作区。搜索栏平滑展开，按实际组/节点/提供商/协议过滤；无模拟组或随机延迟。
- 筛选锚定浮层中的隐藏策略、按当前模式显示GLOBAL、提供商分组、展开时折叠上一组都写入已有proxySelector偏好，改变真实投影。
- 排序config/name/latency、倒序、单/双列和紧凑/舒适密度都实际生效；配置顺序不受旧倒序值影响，异常/未知延迟不伪装成最快。大fontScale时节点单列可访问，不强制縮小用户字体。
- 原位展开为一条LazyColumn里的组头、操作区、提供商头和节点行；不用全屏“切换落地节点”抽屉打断本页，不嵌套无限高网格。上千节点惰性按行组合，保留稳定key、列表位置和推移动效。
- 节点选中态为淡蓝填充、细蓝边、蓝色对勾。点击后等待原146选择请求和回读确认，才更新选中态；失败/取消保留旧选择并释放pending，不能只在内存里改currentNode冒充切换。
- 单节点测速点击与父卡选中隔离；全测速对不同节点四个一批调用真实repo.delay，不调用会清除自动组固定选择的group-delay。API传输失败保留旧测量值；只有核心确认的DelayFailure变为超时/失败。
- 节点卡支持0.97按压弹簧回弹、长按展示完整名称和协议；UDP来自实际metadata，不把UDP false擅自标记为TCP。配置策略图标沿用ConfiguredGroupIcon；子策略箭头原位跳转，不自动改选择。
- 右下角“节点选择”胶囊定位并展开当前可见主策略，清除搜索以定位，尊重筛选条件，避让实际Dock；最后节点可正常滚到悬浮键上方。
- API与测速底部抽屉使用原生可拖动ModalBottomSheet，输入写入原146客户端实际读取的proxyCustomDelayUrl、proxyCustomApiHost/Port/Secret和proxyApiHistoryEnabled等。校验HTTP(S)URL/端口/Secret换行；修改后用于真实请求，不擅自改Root监听或重启。
- 历史采集对应既有本地流量与连接SQLite历史；不是新开发了逐节点延迟/带宽采集。关闭停止新增记录，不删除旧记录。
- API抽屉开启时在硬件、SDK31+、开启模糊的条件下模糊本策略页背景，关闭模糊或不支持时正常降级；不得把自动化截图当作真机GPU毛玻璃验证。

## 签名：UI11复用已验证UI10

- UI10/UI11共同证书SHA-256：`701bbb0aaa5709cf2bebd96ff85ebd64c06cf6c3a2211ec21a9c51e534a5faad`。
- 显式环境`HETU_TEST_KEYSTORE=/home/runner/.android/hetu-ui10-debug.jks`；缓存key `hetu-ui10-debug-signing-v1`，完全相同的绝对路径保存/恢复。本轮在编译前与APK生成后均检查固定公开指纹，没有生成/轮换密钥。
- UI11工作流缓存未命中直接失败，不静默新建Debug身份。缓存不是生产签名托管，也不保证永久保留；正式签名安全保管另行处理。
- UI9成功运行36413534221日志实际报Path Validation Error：错误的~/.android/debug.keystore路径不存在，未保存缓存。UI9私钥未找到，不能从APK公钥证书还原。UI10与UI9不同；UI11同UI10不代表能覆盖UI9/原146/早期不同签名包。
- 签名冲突时保留旧应用和配置，不能要求用户为测试卸载/清数据，也不能用改包名掩盖冲突。不得将密钥库、私钥提交或放入源码/报告/发布附件。

## 回归与真实失败记录

- 原UI10十二套145项继续执行，仅按“首页移除两个操作键”的新要求更新相关断言：按钮应不存在；原工具栏刷新断言改为调用实际无障碍刷新动作。物理下拉和点击空白无隐藏按钮的检查也保留/新增。
- 新增HomeUi11Test3项、PanelModel11Test12项、PanelUi11Test21项，共15组181项。
- 覆盖：实际RefPanel路由、四工具、即时搜索、筛选偏好生效、列数/密度几何、排序/提供商、单开/多开、等待确认/失败不换勾、延迟点击隔离、全测速真实回调、传输错误保留结果、长按全文、FAB跳转、1000/2000节点惰性行、320dp大字体、API校验与保存。
- 真实MihomoControllerClient连接受控MockWebServer测试已覆盖自定义主机/端口/鉴权、实际PUT节点选择和测速URL读取。ProxyApiHistoryStore用真实本地数据库验证历史开关，非假空回调。
- 36423666791：生产编译因RefPanelTab属性名title应为label、误用私有背景类型和homeMotionAvailable可见性失败，未运行测试。
- 36424117790：生产编译完成，测试编译因CustomActions被误传给performSemanticsAction失败，未运行测试。改为Compose专用performCustomAccessibilityActionWithLabel。
- 36424616625：编译、181/181、身份/签名/保护文件校验、源码提交、附件发布全部成功。没有删/跳测试，没有将假数据写入产品以通过。
- docs/ci/UI11-last-attempt.json保留的是最近失败记录，并不覆盖成功交付状态；成功结论依据docs/UI92_RUNTIME146_UI11.md、发布result.json和对应Actions。原失败运行未删除。
- 自动化通过不等于用户手机安装、Root联网、微信消息、GPU毛玻璃、帧率或长期稳定性验收。

## 精确来源及后续约束

- 最初UI92-ui.1提交`84d14dc1f673f557bffbb67d09a7c0ee69321e04`。
- 原146APK SHA-256 `9f276e8562d79011fffa4f57c1469063b3b4a2ff0208be249e0cfcb478ce508c`。
- 原146实际源码Hetu-test145-UI-refined-source.zip SHA-256 `6ef8803c63003b28ffe79247cc107b9d348e194ca37cb72f66543e74ba8d6975`。
- 首次合并提交`3ea502122e70d22813b127f0b16a1cf139acc5c8`，UI92_RUNTIME146_INPUTS.json记录22项资产哈希。本轮以已验证UI10 APK恢复原146资产，不升级二进制。
- 从当前main已验证应用代码继续。tools/apply_*、tools/fix_*、旧synchronize/updates是一次性迁移/验证设施，不是产品运行层，不可重放旧迁移覆盖新版。UI11迁移只用于精确UI10输入，已发布UI11后应直接编译当前代码。
- 不整体合并134、146 UI polish或旧BoxProxy分支，不改Root/DNS/路由/配置存储来处理纯UI问题。保留面板各标签、工具、设置以及设置→更多功能设置中的原146能力。
- 不引入Vue空回调、console.log、setTimeout假启停/测速、硬编码IP/节点/CPU/流量/ISP。未知值继续未知。
- 分开报告迁移脚本、实际应用提交、编译、测试、发布和真机结果。用户要求回复不放图片/图片链接，交付真实APK、源码、报告和文字即可。
- 旧test.92自动写回已停用，不重写Git历史。之前批量清理旧分支/标签/产物被安全检查拦截、未完成，本轮未执行历史清理，不授权绕过。
