## 当前续接：V20.76 安全运行记录修复与过滤导出

继续 `test/v20.75-theme-compat` 的已交付 V20.75（分支原 HEAD 069a7ed），不创建重复分支，不合并主分支，不发布或部署。追加 `updates/v2076-session-filter/runtime.patch`，versionCode 2076 / 0.12.6-v20，Root runtime 151；21 个原 native/core 与 service.d 载荷、固定签名保持。最新用户禁止操作手机或修改其网络、安全设置，测试仅限已有授权的隔离 shell/JVM/AOSP 环境。

旧 r149 session 仅多出末尾 GOOGLE_FIREWALL_CLEAN 行时，现有不可变 baseline checksum、PID、规则、路由、监听与守护身份全部核对后，允许用户在工具中显式修复运行记录。保留原始 baseline；不重建缺失/损坏 baseline，不将现网快照登记为健康，不强制重启核心，不自动把已应用 runtime 149 改为 151。新版独立检查器用于旧部署的诊断，读检查不得使 ProxyControlEpoch 的观察票据失效。任何观察不完整均失败关闭，Google 可达独立报告。

过滤导出迁至 noBackup 稳定目录；按不可变 generation 成对发布、hash 校验、保留旧交接文件。实际旧/new 导出器的缓存清理故障注入、真实 Android 导出、第二 provider 写入失败、共享主题重建与 Monet 切换回归均纳入本轮 CI。预期选中 Android 单测 277 项；实际结果须核对最终 Actions，不引用旧 262/旧 Android 15/16 作为本轮通过。详见 docs/V20.76_SESSION_FILTER.md。禁止访问用户设备、扩大 KVM/Root/SELinux 等权限；本轮不得永久删除用户数据。

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
