# V20.86 功能修复（已完成隔离验收）

## 已完成：c7096ea 独立 CI、545项及双 API 验收

本轮受测应用 c7096ea25cf1e150a1297859f8ec53d137327c47 / Actions37381538358 attempt1 整轮 completed/success，四个自己的job全部success；build112004483283、历史崩溃对照112004483856、当前API35安装112009969762、API36安装112009969868。严格对应本次源，7b8/3b68原失败及274/ec旧成功均保留，不替代本次验收。

实际56份原XML545项、0失败/错误/跳过。原51XML487项的全部用例身份逐个与真实ec成功ZIP核对一致；新增58项为策略展开4、代理探测13、订阅12、fake-IP13、请求归属16。完整180项交付核验PASS，另限定UI/源码170项独立复核PASS（两者重合，不相加）。CI实际386有效输入重建一致，16变更/370冻结、六历史层原字节不变；本地382与四个原native缺失边界仍明确。687构造ABI零缺失、lint Error/Fatal0、299Warning/7Hint、23载荷及固定签名通过；原22项载荷清单完整保留，其中Root脚本按本轮修复替换，其余21项与自启字节保持。

API35/36各实际64=59导航+5控制器认证及21配色，Root mutation0。401先读、重试入口在200前可用、恢复200、Bearer六路径实HTTP200、原偏好/forward/reverse/KVM/模拟器PID/session清理和隔离安全门禁均通过。前台轮询存在，不将200独占归因于重试按钮；控制器认证不等于Google帐号认证。

真实APK六ZIP、六part及整包逐SHA核验，双方独立重组一致：134858259字节，SHA256 91001c677ca1974c9c8833acc183cf7f7e278ff13da5d6ad4ebd867b443ca835。交付文件 Hetu-v20.86-function-c7096ea.apk 已准备并保存，versionCode2086/versionName0.12.16-v20-fix。自己的build日志直接记录apksigner验证及固定证书701bbb0aaa5709cf2bebd96ff85ebd64c06cf6c3a2211ec21a9c51e534a5faad，并绑定同一整包SHA；离线Python不冒称重新执行apksigner。10实际下载ZIP逐provider digest、run/head绑定均通过；94MB完整源归档未在本地下载/重哈希，CI386证明及Git源/实际APK载荷分别核验，不伪称本地完整源归档验收。

覆盖安装后点击一次代理「重启」应用部署revision152及新Root脚本；未强停用户核心、未操作用户设备/帐号或清数据、无hook、未合并main或正式发布。此轮确认的源码缺陷及隔离回归已完成；真实GooglePlay账号认证、真机Root/OEM切网、长期稳定性与未知缺陷不存在仍未验证，不能把截图的唯一根因或手机修复写成已确定。

本次完整交付报告 docs/qa/20261006-v2086-c7096ea-delivery-independent.json SHA256 15fb9c8b0e6d538e1fd7de6094243b44ad3c1096f50fbd4f498ece06a5096cac；真实provider绑定 docs/qa/20261006-v2086-c7096ea-provider-binding.json；独立UI报告 docs/qa/20261006-v2086-c7096ea-ui-independent.json。以下是本轮首轮、失败和续验历史，历史未执行陈述不代表上述最终完成状态。

用户于2026-10-06提供Google Play身份验证错误与切换BoxProxy后正常加载的截图，并明确要求修复功能缺陷及策略组展开上跳。继续原仓库 `xgl34222220-ops/hetu`、原分支 `test/v20.76-new-ui`，本轮基底 `1dbe40cc4e312c230524092dfe32a2c6cfb5f25d`，Android树与PDF候选274aa0d相同。原PDF运行37366522056保留，不取消、不重复实现其四处呈现改动。原工具接入ec211b9的487项成功只属于其自身源码。

本轮授权是功能修复，策略交互与网络运行层可以修复；保留当前视觉设计、功能入口、原核心载荷、权限、签名和历史补丁。不引入hook、不操作用户设备或账号、不清数据、不合并main或正式发布。

## 已确认的源码缺陷与目标

- 实际launcher `HetuActivity → panel.NewUiPanel → PanelRoute → PanelScreen` 每次展开后无条件滚动到组头顶端；改为依据稳定行key和点击前offset保留位置，显式节点定位继续可滚动。
- Root默认局域网IPv6 `fc00::/7`、部分IPv4私网RETURN会把位于这些网段的fake-IP地址直连绕过核心；依据实际私有启动YAML的有效fake-IP网段，从默认局域网旁路网段中减去它们，保留用户显式绕过策略。
- IPv6 DNS共享网络路径漏传已配置的MAC旁路名单，与IPv4不一致。
- 首页网站延迟由河图自身UID直接探测且接受HTTP401/403等状态，不能代表正在运行的核心代理路径；运行时经已有本地HTTP探测监听，拒绝错误响应，不静默回落直连。
- 测速批次的一项传输异常使同批成功结果被丢弃；共享节点测速没有请求所有权，迟到结果与busy释放会互相覆盖；1.5秒节点缓存没有API/配置/运行身份，切换后可能使用旧节点元数据。
- 订阅添加默认假定 `BaseProvider` 锚点存在；删除只处理行内 `use`，遗漏块状引用。编辑须保持有效配置和无关字节，并对无法安全修改的结构明确失败、保留原件。

以上是有源码证据的可复现缺陷。截图只建立河图与正常代理的行为差异，未给出河图故障时实际配置、DNS答案、Play/GMS UID分流及认证响应，因此不能将任何一项提前写成用户Google认证故障的唯一根因或真机已修复。

## 验收要求

追加独立 `updates/v2086-functionfix` 层，固定完整前层输入与历史补丁；原487项/51XML继续执行，新增专用测试精确计数。必要Runtime revision升级仅更新既有版本契约断言的精确版本值，不减少任何测试。新提交须自己的编译、全量单测、lint、签名、23项载荷和API35/36隔离安装证据；原有失败及其他运行证据保留。

本地已实际通过订阅生产文件路径的12项Java主机回归（Java 8目标）、fake-IP helper的13项Java主机回归、新16项隔离shell、原health26/autostart17/Google firewall17及shell语法；相同3项关键路由回归旧脚本3/3失败、新脚本3/3通过。主机测试不等于Android/Robolectric或真机。默认私网减去fake网段只影响自动LAN绕过，用户明确CIDR/UID/GID/接口/MAC旁路策略保留。

本轮Root脚本SHA256 bdee8678922f3c4cdb12ebab532175e67816dce429b64fa632c6641c4fb2d45e；原自启SHA3a1215f4e9093ae2298803020d697da0c14c35ee5cf28f84f2a81608093783f9保持。原start/autostart参数协议与dispatcher不改，私有YAML固定末5条注释携带CIDR差集；有效尾块只信末尾，合法YAML scalar中的相同文字不误识别，旧无尾块配置兼容。现有session checksum继续验证，没有新增boot checksum机制。覆盖安装后原运行核心不会自动部署新脚本，须点一次代理「重启」；重复start发现旧部署会给出明确提示。

所有生产源码已停止编辑。新增五类测试：策略展开4、代理探测10、订阅完整性12、fake-IP网段13、请求归属16，共55；原487/51保留，目标542/56XML。独立patch含16条路径，370冻结，完整386输入；本地仅382（原4native未恢复），不称本地完整有效源。patch SHA256 2a394afc8381d8390d34e0c29c150a5850d11299fb28b3bc41ba3c70135f6b62。

提交前22个命令全部通过，其中Python单测实际196项；包括20新增源码/结果/工作流回归、47原源码边界、11原结果校验、42原交互/控制器/WebView夹具及76原Root+新fake-IP shell。另Node概念/语法、三项当前Java网络恢复/日志stress、Python/YAML/shell语法及diff检查通过。完整逐命令、日志摘要、源码数量与未执行项见docs/qa/20261006-v2086-function-source-host-proof.json；旧归档的历史before/after运行未在本地重做，CI仍保留原复现步骤。

尚未执行本轮Android验收，也未生成本轮测试APK。真实Root、OEM切网与Google Play账号认证仍未验证。

## 首轮 7b8de49 原始失败与测试尺寸类型修正

7b8de498dc3c4cd7d044bdbee0e7efe2233d7953 / Actions37376676669 attempt1：生产APK编译、23载荷和687构造ABI零缺失通过，但Runtime unit tests在compileDebugUnitTestKotlin失败。新增PanelExpansionAnchorTest的101/151两处误用DpRect未导入的height/width扩展；实际原始XML为0，单测未执行，lint及当前APK双API安装未执行/跳过，不能记542通过或Root mutation0。历史V20.74对照独立success，未取消任何CI。原日志与实际失败UI artifact逐SHA归档到本轮fixture-compilation-candidate.json；已保留原source-host-proof196属于首轮输入。

续验仅把这两个同一边界/同一阈值的断言改用DpRect四边的.value计算，原4测试、运行时源码及55新增总数均不变；新patch5bb2610455a61a8d934058664c3b3ec39e5efb193fcda275f244b4839e693f6f精确重建本地382/完整386，新20与原五source47再次通过。原2a394patch及c131inputs属于首轮，当前新输入摘要详见候选JSON；新的Android编译、542/56和双API须候选自己的新CI证明，未通过前不交付。

## 第二轮 3b68da9 真实单测失败与缓存失效续验

3b68da93082e43263e2cfae8011c05fa7fdcff14 / Actions37377929368 attempt1 的实际原始56份XML为542项、1失败、0错误、0跳过；541项通过。原487项及策略展开4、代理探测10、订阅12、fake-IP13全部通过，请求归属15/16通过。唯一失败为普通轮询会话测试：上一测试把自定义shadow静态PID改为88，而prepare只把RuntimeSample设为77，轮询正确识别会话变化并清空busy。测试现显式调用RootStatus.reset和Sites.reset，补PID77前后断言，原16测试和busy/延迟结果断言均保留；生产VM不改。真实失败日志、原XML与56套件计数保留于docs/qa/20261006-v2086-3b68da9-*。本轮lint、当前双API安装未执行/跳过，不记Root0。

另已沿实际策略组→testNode→repo.delay路径确认：成功选择新节点及更新订阅后，1.5秒元数据缓存仍可能指向旧组当前节点或旧provider节点列表。成功PUT立即推进mutation epoch，旧缓存和迟到读取/探测一并失效；失败PUT不推进、不丢有效缓存，不等待缓存Mutex。新增两个真实HTTP回归保留原10项，代理探测类共12项。因此下一候选新增57项（4+12+12+13+16），总目标544/56XML，原487项继续完整执行。

本次临时工作目录被运行环境清空后，从同一远端分支20315ad真实恢复；该提交Android树与3b68完全相同。两处缓存源码和测试从原实现重放，测试夹具显式reset恢复，其他任务原文档与六历史层保留。前次未提交epoch层/1235报告属于丢失的临时文件，仅作为历史记录，不能当作本次实物核验。独立交付检查器重新写入Git源码，并从原ec211b9成功产物恢复真实487测试身份基线。新候选仍必须自己的完整新CI与真实APK验收，不能借用第二轮541通过或274/ec旧成功。

本轮恢复后实物源独立重建完成：patch8c8281ce4248610259696b03d1d224ee8b2b6bb677227fe552a225723958687a，inputs5acab6aa5b44ba2bb62cd17b1d884412a1a75f7e18183f0c55a0b3735d7c3164；20新增与47原源码host全部通过，层逐字节复现。报告 docs/qa/20261006-v2086-select-epoch-reset-source-host-proof.json SHA256 a980694b26c1fb954917b0bc5ce16ca3f605820e897b8972a283113f100f14b5。此时Android544/56、lint和当前双API仍待新提交自己的CI。

## 整波迟到结果的最后检查

恢复后沿真实调用链复审确认批量缺口：快节点已取得结果、慢节点仍阻塞时成功切换节点或刷新订阅，慢节点的旧身份异常被当作无测量保留，整波却能返回先前快节点结果；VM的运行身份没有私有mutation epoch，会接纳该旧map并刷新时间戳。因此在measureSnapshot与非Selector groupDelay的整波返回前各追加一次现有身份检查。身份未变时保留同批成功及明确超时/失败；身份改变时整波拒绝发布。新增一个真实HTTP快/慢阻塞用例分别验证global与非Selector group，原12测试完整保留，Probe共13。下一最终目标原487+58=545项/56XML；上文544与a980报告是该追加修复前的待验候选，仅保留其历史实物host事实，不能验证新增批量检查。

最终batch候选67host已全部通过且层逐字节复现：patch ad70b0c670e70b65a4dfa4b571c9c9dda4144dee1c899ca082c03c93e235d65a、inputs 796e5f8f7039ae934b8fb5f00e4656a30d5ad533a17aa66d7b3ee2e7b7e92a22；545/56，local382/required386。独立报告 docs/qa/20261006-v2086-batch-epoch-source-host-proof.json SHA49f1cc67cf4deb944141f4735f98c37c4cbc5d4a3012118f187a1e0c704aad27。此时新Android验收仍未执行。

本次API独立追加复核75/75通过：docs/qa/20261006-v2086-c7096ea-api-independent.json SHA24e237656113b21f380ca78ba32451353bf3abda4b2ebb1c0b76deb7028da0d4。每API34请求（15×401/19×200），200六路径configs/proxies/providers-proxies/connections/rules/version计数3/3/8/3/1/1，首retry后仍有4×401；恢复与fixture切换/后续retry、前台poll的阶段分别记录，不写独占恢复。各63实PNG/394×852核验；启动前AVD副本与live preflight config的不同阶段SHA、既有host privileged supervisor与guest non-root、偏好/reverse清理receipt边界不冒称离线活体复测。本报告与其他复核重合，不相加，真实Google验证仍false。
