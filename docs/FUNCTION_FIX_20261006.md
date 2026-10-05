# V20.86 功能修复（进行中，未验收）

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
