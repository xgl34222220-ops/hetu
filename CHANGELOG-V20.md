## V20.41 · 设置 / 工具 / 底栏对版

- 设置首页按参考重排：基础代理配置、其他代理配置、语言、主题设置、备份恢复、开机自启、加速下载、通知、通知详细设置、底栏面板入口与默认面板页。
- 基础代理配置页改为核心 / 模式 / IPv6 / 自动覆写 / 启动配置 / 当前配置的紧凑结构；高级页更名为“其他代理配置”。
- 通知详细设置新增真实通知标题模板，标题和正文均支持状态变量。
- 工具页进一步收紧卡片、字号、图标和留白，匹配参考的紧凑比例。
- 底栏减高、减白、减阴影，缩小图标文字与选中玻璃 lens；面板入口支持真实隐藏。

# V20.40 — 文件管理工作台对版

- 文件管理：加入可点击面包屑路径，子目录常驻搜索框，列表加入 `..` 返回上级。
- 更多菜单按参考整理为：导入、下载、新建文件、新建文件夹。
- 下载：支持 HTTPS URL 直接下载到当前目录，自动推断文件名，可手动指定。
- 文件打开：先识别是否为可编辑 UTF-8 文本；二进制/压缩文件停留在文件管理并提示不支持编辑。
- 文件编辑器：重做为返回 + 居中文件名 + 勾选保存 + 更多菜单；搜索、跳转、换行、校验、复制收进更多菜单。
- 编辑器底部保留紧凑撤销/重做/搜索/跳转/换行/校验工具条和行列信息，更接近参考工作流。

## V20.38 · 全局精致化设计系统

- 一级页与二级页统一到同一套淡紫画布、乳白卡片、轻阴影与更细的字体层级。
- 全局收紧标题、正文、图标、行高、卡片圆角与页面留白，减少“字体太重 / 白块太大”的观感。
- 顶栏玻璃降低白雾与分割线存在感，底栏缩高并减轻选中胶囊实体感。
- 首页 Bento、公共列表卡、原生二级页 Surface 与 Bottom Sheet 同步精修。

## V20.37 · 二级页面参考样式统一

- 全部 HetuTheme 二级页统一浅紫画布、乳白卡片、淡紫控件与更紧凑的圆角/间距。
- 共用返回顶栏改为透明极简动作，缩小工具栏高度、标题、副标题和操作图标。
- 订阅管理、应用管理、文件管理、日志、网络匹配、高级代理、广告过滤、核心管理等页面继续单独收紧。
- 共用底部详情弹层统一 26dp 圆角、18dp 内容边距与 48dp 主按钮。

## V20.36 · 去除分页白带 + 工具页材质校准

- 面板分页栏展开时彻底透明：Haze 只在折叠后渐入，修复“概览/策略/订阅…”整行白色横带。
- 面板背景、分页胶囊改为更接近参考的淡紫与半透材质，缩小分页高度和间距。
- 工具页使用独立淡紫画布与微紫卡片，不再纯白；行高、图标、标题和副标题继续收紧。
- 底部 Dock 减轻白色外壳，选中项改为双层高光玻璃 lens，降低灰色实体感。

## V20.31 · 面板策略菜单 + 卡片统一

- 策略页右上改为参考界面的原生级联“排序与布局”菜单；排序、倒序、节点列数/密度、策略列数/密度、名称显示全部直接驱动当前页面，不再是摆设。
- 修复此前“节点列数”设置实际上没有控制节点网格的问题；策略网格与节点网格现在分别读取并实时应用自己的列数和密度。
- 订阅卡片按参考布局重排：名称/百分比/刷新、到期/更新时间、上传/下载/剩余、进度条、已用/总计。
- 连接、规则、规则集、日志改为独立圆角信息卡，统一间距、阴影、层级与点击反馈；保留真实更新、断开、复制、展开等动作。
- 规则集“全部更新”和订阅“全部更新”继续放在顶栏，列表本身不再额外塞一条重复工具栏。

## V20.27

- 面板顶栏精简：移除各分页的解释性副标题，只保留“面板”标题、横向分页和必要操作。
- 面板标题区压缩高度，分页更贴近标题，视觉结构对齐参考视频。
- 连接页移除“左滑/点按/长按”等教学说明，功能保留但不再占用界面。


## V20.25
- 首页移除右上角设置按钮。
- WAN 卡整卡切换 WAN/LAN；公网详情独立展示 IP/地区/ISP/ASN。
- WAN 地区改用国家名优先，补充 city/region 详情，避免把省州误当国家。
- 网速卡支持 API / 本地两种数据源。
- 订阅卡直达面板订阅页；资源占用卡打开核心 CPU/内存详情。
# V20.24 Home parity

- Keep the current Hetu running-status hero, but rebuild the rest of Home around the cleaner reference-video layout.
- Remove the duplicate `配置与订阅` and ad-block quick cards from Home.
- Add two lightweight launch tiles: WebUI and Logs.
- Refine the latency card with dedicated settings and refresh actions.
- Keep the compact WAN / speed / subscription-usage / resource-usage bento blocks.
- Remove Home trend and outbound-node blocks to reduce duplication with Panel.


## V20.22 — Panel defaults & strategy cleanup

- Removed the redundant global “全部测速” chip and the instructional “点按展开节点/长按测速” copy from strategy.
- Restored larger strategy-group artwork so the compact cards keep the visual weight of the reference UI.
- Added a “默认面板页面” single-choice setting (概览 / 策略 / 连接 / 订阅 / 规则 / 规则集).
- “启动后进入面板” now honors that default page.
- Home subscription/config entry points now open 面板 → 订阅 directly.

## V20.21 — strategy back in Panel + core-parallel latency test
- Restores the four-item dock: 首页 / 面板 / 工具 / 设置. 策略 returns to 面板 as a swipeable tab.
- Strategy-group latency taps now use Mihomo `/group/<name>/delay` instead of serial per-node waves.
- Group testing has a 5s core deadline / 7s socket ceiling, so the spinner ends with the actual group probe instead of waiting on hidden stragglers.
- Refines strategy tiles: softer 16dp corners, lighter typography, tighter vertical rhythm, slightly smaller free-standing artwork and animated latency colors.
## V20.15 · 去重复入口 + 新界面继续替换旧页（0.6.5-v20 / 2015）

去掉重复（每个功能只保留一个入口，独有能力合并进保留的那一个）：
- 「订阅工作台」与「配置与订阅」功能重叠 → 只保留「配置与订阅」；工作台独有的 YAML 语法大纲并入原生编辑器（顶栏大纲按钮，点条目直接跳到对应行）。
- 配置与订阅里的「订阅流量与更新」与面板「订阅」相同 → 移除；首页订阅卡直接跳到面板「订阅」。
- 「Sub-Store」只在工具页；「策略图标」只在代理页的排序与显示里；「测速与 API」只在代理页右上角。
- 网络与分流页里的应用范围 / 应用名单、中国 IP 直连、绕过网段、接管热点共享、网络匹配、高级运行控制，与工具页重复 → 移除，页底注明去处；保留核心、模式、DNS、IPv6、TCP / UDP / QUIC、Kill Switch、端口细则。
- 旧「高级运行控制」大部分项与网络页重复 → 换成原生「诊断与维护」，只保留独有的：运行预检、查看启动配置、消息与网络诊断、恢复网络。设置页的「恢复网络」「工具箱」「关于河图」重复行移除（关于由顶部卡片进入）。
- 「状态通知」开关与「通知与快捷控制」页里的开关重复 → 合为一个原生「状态通知」页。
- 「更多主题选项」与界面组重复 → 移除，强调色（8 色圆点，选中放大打勾）与「纯黑深色」直接放进设置 · 界面。
- 两个内核管理页（新旧）→ 统一为原生内核管理。
- 工具页「日志查看」改名「日志文件」，与面板实时「日志」区分。

新原生页面：诊断与维护、状态通知（权限申请、刷新频率 / 点击目标 / 按钮动作下拉、模板与按钮文字编辑、✓ 保存）。
修复：旧通知页在 Android 13+ 打开开关不申请通知权限。

## V20.14 · 旧工具页改为新界面 + 问题修复（0.6.4-v20 / 2014）

新界面（应用内卡片式切换；旧入口/通知等打开的独立页面也换成同一套界面与底部提示）：
- 文件管理：基于 RuntimeFilesRepository 重写。文件夹层级左右滑动切换、系统返回逐级向上；点文件直接进入编辑器；长按弹出就近菜单（编辑 / 重命名 / 导出 / 删除）；右上 + 新建文件、新建文件夹、导入文件；下拉刷新、骨架屏、空状态与错误重试。（原来只能只读预览）
- 共享网络：总开关；自动读取的共享接口逐个开关“直连/接管”；检测到的下游设备逐个开关直连；MAC 列表逐行编辑并校验（格式、全 0/广播、最多 64 个）。
- 绕过规则：CIDR 与禁用接口两个列表同页编辑，✓ 统一校验保存，未保存离开会确认。
- CNIP 设置、运行核心：新卡片样式，选择项带勾选动画。
- 日志查看：选择日志文件改为就近下拉菜单；级别分段（全部/错误/警告/信息/调试）、搜索、自动刷新、最新在前；每行按级别着色，点按展开、长按复制；清空前确认。
- 网络匹配：当前环境卡片、总开关、移动数据；匹配成功 / 失配动作改为下拉；SSID、BSSID 改为逐行列表编辑并校验 BSSID。

修复：
- CNIP 开关、旧绕过规则保存后没有标记“需重启生效”，首页/网络页不提示重启 —— 已修复。
- 旧绕过规则保存时不校验 CIDR / 接口名 —— 现在会校验并提示具体错误项。
- 网络匹配开启时只做了评估而没有启动监听服务（权限已授予时）—— 现在开启即启动服务。
- 日志清空失败时吞掉协程取消 —— 已按取消语义处理。
- 导入文件时文件名取自 URI 片段，常出现乱码名 —— 改为读取系统显示名。

## V20.13 · 面板 / 工具 结构与卡片式导航（0.6.3-v20 / 2013）

- 底栏改为四项：首页 · 面板 · 工具 · 设置（图标：主页 / 链接 / 宫格 / 设置），液态指示器保留。
- 「面板」：一个大标题 + 胶囊标签页，左右滑动在 策略 · 订阅 · 连接 · 排行 · 规则 · 规则集 · 日志 之间切换；每个分区的顶栏按钮（搜索、排序、断开全部、刷新…）、浮动按钮和副标题随当前分区自动切换；上滑折叠大标题、标签吸顶；日志只在当前分区可见时轮询。首页“网速”、出站节点等入口会直接跳到对应分区。
- 「工具」：文件管理、脚本、日志查看；应用管理、网络匹配、共享网络、绕过网段、绕过规则、CNIP；配置与订阅、订阅工作台、Sub-Store、广告过滤、内核管理、Web 面板、策略图标；测速与 API、高级运行控制。设置页相应去掉重复入口，并加「工具箱」跳转。
- 绕过网段改为原生列表编辑页：每条一个填充输入框 + 删除键，底部「添加一项」，顶栏 ✓ 校验并保存，未保存离开会确认。
- 页面切换改为卡片堆叠：新页面从右侧整页推入，旧页面向左轻移、微缩并变暗；返回时反向。
- 列表行加高、标题加粗放大，图标与间距更舒展。

## V20.12 · 设置类页面与弹层精修（0.6.2-v20 / 2012）

- 下拉菜单：核心、透明代理模式、DNS 劫持、IPv6、应用范围、主题、延迟自动刷新等选择项，改为从所点行旁边弹出的浮动菜单（背景变暗、从行的位置缩放展开、选中项打勾，收起动画结束后才生效）；行尾改为「⌃⌄」下拉标识。找不到定位点时仍用底部弹窗。
- 分组卡片：分组标题移入卡片内部（粗体），行与行之间去掉分割线，靠留白区分（深色模式保留极淡细线）。
- 顶栏：滚动后出现的小标题改为正中，返回键和操作按钮分列两侧。
- 配置列表：长按配置或点「⋯」弹出就近菜单（设为当前 / 编辑 YAML / 删除）。
- 输入框：弹窗与测速/API 设置里的输入框改为浅色填充样式，聚焦时出现强调色描边；搜索框未聚焦时无边框。
- 对话框：底部改为「取消 / 确定」两枚等宽胶囊按钮，危险操作确认按钮为红色，圆角 28。
- 关于页：顶部渐变大图头部，图标弹入并轻微悬浮；上滑时视差上移并淡出，交给顶栏标题。

## V20.11 · 参考 BoxProxy 的视觉与交互重做（0.6.1-v20 / 2011）

- 配色：默认强调色由青玉改为宝石蓝（新旧页面统一替换），画布改为淡雾紫灰，卡片纯白无描边、阴影更轻，圆角加大；主题设置里仍可选回青玉等其它颜色。
- 延迟统一为蓝色胶囊（超时红、过慢橙），首页站点延迟同样配色。
- 首页改为“便当格”布局：
  - 状态主卡：淡蓝渐变底 + 右侧超大状态图标（切换时弹跳），运行状态 / 已运行时长 / 核心·模式 / 配置；底部一排「重载 · 停止 · 重启」（未运行时为「启动代理」），各自带加载态。
  - 配置与订阅、广告过滤两个快捷卡；延迟卡（三站点，点按测速，刷新图标旋转）；出口卡（点按复制）；网速卡；订阅卡（已用/总量/剩余百分比，长按更新全部）；资源占用卡（内存、CPU 带进度条，点按看核心详情）；近期趋势曲线卡；当前出站节点卡。
- 活动页、规则页改为「大标题 + 胶囊标签」分页：左右滑动切换（连接/排行/日志、规则集/订阅/规则），标签白底随手指平滑滑动；上滑时大标题折叠、顶栏出现居中小标题、标签吸顶。
- 订阅改为大卡片：百分比标签、到期与更新时间、上传 / 下载 / 剩余（蓝色）三栏数字、进度条、已用与总计，右上角刷新状态动画。
- 连接、规则、规则集列表改为独立圆角卡片，间距更舒展。
- 左滑断开 / 移除改为只在向左拖动时接管手势，不再挡住分页左右滑动。
- 提示改为底部居中胶囊（位于底栏上方），带状态图标，可点按或下滑关闭。

## V20.10 · 代理页双列卡片面板（0.6.0-v20 / 2010）

- 代理页改为双列策略组卡片（大字体自动单列）：组名、类型、可用/总节点数、右上角大图标（优先 YAML `icon`，否则国旗/首字，自适应大小）、当前节点与延迟胶囊。
- 点按卡片：该行下方全宽展开节点面板，节点同样以双列卡片显示（名称、UDP/订阅、协议、延迟）；选中节点描边高亮，切换带振动与加载指示；再次点按卡片收起。
- 展开时右下角浮出「定位」圆钮（跳到当前节点）与「⌄ 组名」收起胶囊；卡片在屏幕下半部时自动滚到可见位置。
- 长按策略组卡片测速本组，长按节点卡片单独测速；面板顶部有「测速本组」，页头保留「全部测速」。
- 延迟胶囊改为圆角胶囊、“79 ms”样式；列表展开/收起、卡片位移全部带动画。
- 页面「回到顶部」按钮在面板展开时让位给面板浮钮。

## V20.9 · 全面动效与交互精修（0.5.9-v20 / 2009）

> 基于 V20.8 源码，保持其克制、扁平、单色图标的方向，不删除任何功能。未本地编译（环境无 Android SDK），
> 已做 Kotlin 语法解析与导入审计；请用 `hetu-build.yml` 编译，有报错贴日志即可逐条修。

**全局组件**
- 页头：大标题随滚动连续淡出、轻微缩小下沉，与顶栏小标题衔接，不再“突然切换”；长列表滚深后右下角出现“回到顶部”玻璃按钮。
- 底栏：选中胶囊改为“液态”拉伸滑动（前沿先到、后沿追上）；向下阅读时底栏自动收起，向上滑或切换 Tab 立即回来；选中项文字加粗。
- 分段控件：同款液态指示器 + 轻阴影，按压缩放，选中项加粗，禁用态平滑变淡。
- 底部弹窗：新拖拽把手；弹窗内操作（选择项、断开连接、切换配置等）先滑出再执行，不再“一帧消失”；单选弹窗选中后对勾弹入再收起。
- 表单弹窗：输入不合法时整体左右抖动 + 拒绝振动；提交成功有确认振动。
- 顶部提示：按内容自动识别成功/警告/失败并配图标，失败带振动；可点按或上滑关闭；长文本停留更久。
- 按钮、标签、横幅、顶栏图标：颜色/尺寸变化全部带过渡；顶栏图标切换（搜索⇄关闭、空闲⇄加载）缩放转场；空状态淡入并配圆形图标底座；骨架屏改为与分组卡片同形。
- 分组卡片内容增减时高度平滑变化；列表行支持长按（带振动）。
- 新增通用左滑操作（越过阈值变实色并震动，松手执行，失败自动复位）。

**首页**
- 状态区：呼吸状态点、状态文字上下滚动切换、操作中显示实时进度文字；运行中点状态区打开「核心运行详情」（此前不可达）。
- 连接/断开按钮内容缩放转场；设置待重启、核心提示以可展开横幅出现，横幅内可直接“重启”。
- 出口 IP：国旗 + 地区，点按/长按复制；实时网络未连接时给出提示文案，曲线淡化；显示本次累计流量；“连接”小标签可跳转活动页。
- 当前出站节点：节点名切换动画，右侧改为可点按测速的延迟胶囊。
- 新增「常用」四宫格：配置与订阅、广告过滤状态、站点延迟（点按测速，显示最快值）、更新订阅（长按进入订阅流量）。

**代理页**
- 搜索框出现即聚焦弹键盘；“全部测速”改为带图标胶囊并显示测速中。
- 策略组当前节点变化有滚动动画；节点弹窗打开时自动定位到当前节点，选中行底色与对勾动画过渡，切换成功后稍停再滑出。
- 延迟胶囊：颜色渐变、新结果弹入、按压反馈与振动。

**活动页**
- 连接行左滑即可断开；显示 TCP/UDP 标签；列表增删有位移动画。
- 连接详情改为分组卡片，长按任一行复制；按应用分组的展开箭头旋转动画。
- 日志：点按展开/收起长日志，长按复制整条；排行前三名带徽标底色。

**规则页**
- “全部更新”改为实色胶囊，更新中图标持续旋转；单项更新状态（更新中/成功/失败）缩放切换，失败原因展开显示。
- 规则策略以彩色标签显示（REJECT 红 / DIRECT 绿），长按复制整条规则。

**设置与二级页**
- 设置顶部新增应用身份卡（版本、核心、运行状态，点按进入关于）。
- 找回两个此前写好却没有入口的功能：「测速与 API」与「恢复网络」（紧急回滚 iptables/路由）。
- 配置列表单选改为带对勾的动画圆环，当前配置加粗；配置菜单改为分组卡片并带收起动画。
- YAML 编辑器：新增撤销/重做键；未保存改为弹入的小圆点；错误横幅可展开/关闭；符号键按压反馈与振动。
- 广告过滤：保护中盾牌外圈呼吸；运行链验证改为 ✓/! 图标动画；黑白名单支持左滑移除。
- 应用名单：已选行浅色高亮、图标淡入、勾选振动；内核管理加载骨架屏、下载进度带转圈；关于页图标弹入并轻微悬浮。

## V20.3 · 导航去重 + 双列策略组 + 视觉精修

- 首页收回为纯状态仪表盘：保留运行状态、流量、网络，不再重复放置配置、广告过滤、日志、WebUI、运行文件等管理入口。
- 设置去掉与“网络与分流”重复的“运行核心 / 应用名单”顶级入口；“代理面板”改为“代理行为”，仅保留代理页自身没有的偏好。
- 策略组默认双列（大字体自动单列）；点任一组后，其节点在该行下方全宽展开，兼顾密度和可读性。
- 策略组卡片重排为图标 / 名称 / 当前节点 / 类型 / 延迟 / 测速的紧凑层级，展开组使用轻强调底色。
- 全局卡片圆角、分组间距、图标底座、列表行高与阴影进一步收敛，减少“功能清单”和白块感。

# V20 · 界面与交互整体重构

版本 `0.5.2-v20` / versionCode 2002，包名、签名身份不变，可直接覆盖安装 V19。

> 本版在无 Android SDK 的环境中编写，**未本地编译**。已做：Kotlin 语法解析检查、
> 旧 UI 依赖闭包审计（删除的 91 个文件没有被保留代码引用）、广告过滤配置生成的实跑断言。
> 请用 `.github/workflows/hetu-build.yml` 编译；若有编译错误，把日志贴回即可逐条修。

## 结构（0.5.1：功能零删除）
- 新的主界面：`HetuActivity` + Compose 导航（首页 / 代理 / 活动 / 规则 / 设置），代码在 `app/` 目录。
- **原有全部源码、功能页面、测试、工具脚本与工作流均保留**（与 V19.3 逐文件核对：旧文件只改了配色常量和“返回主界面”指向）。
  旧的功能页面（订阅工作台、Sub-Store、脚本、日志管理、文件管理、策略图标/显示、Web 面板、本地 WebUI、网络匹配、共享网络、
  绕过规则、国内地址分流、运行核心、高级代理配置、通知与快捷控制、主题与界面、开源库、赞助等）全部可从新设置页进入，
  并统一换成新配色（青玉强调色、中性灰画布）。
- 通知栏点击目标（首页 / 面板弹窗 / 策略弹窗）、快捷磁贴、开机恢复等入口行为与原来一致。

## 设计
- 统一设计令牌（`HxTheme.kt`）：中性灰画布 + 白色卡片 + 单一青玉色强调色，深色模式完整适配，可选壁纸取色。
- 所有页面同一种页头：左上大标题随内容滚动，滚出后同一标题**居中**出现在顶栏（不再出现“偏左”的中间态）。
- 动效统一：页面前进/返回为水平共享轴滑动，Tab 切换为淡入+轻微缩放，卡片按压 0.975 缩放，列表增删有位移动画，
  数字使用等宽数字避免跳动。

## 0.5.2 · 视觉、动效与交互精修
- 底栏改为悬浮毛玻璃胶囊：选中底块弹簧滑动，图标选中时轻弹；内容从底栏下方滚过并被模糊。再次点当前页 → 平滑滚回顶部。
- 顶栏：大标题滚出后，毛玻璃顶栏淡入，居中小标题带轻微缩放上浮；状态栏区域同一层玻璃。
- 预测性返回：子页面跟手缩小、圆角、向滑动边缘偏移，松手完成返回，取消则弹回。
- 页面转场：前进/返回带层级缩放；Tab 切换按方向轻微横移。
- 首页状态卡：运行时背景有缓慢流动的青玉光晕；电源键外圈在启停过程中旋转、运行中呼吸；网速数字逐位滚动；
  流量走势改为上传/下载双曲线（平滑曲线 + 渐变填充 + 端点），纵轴缩放平滑过渡。
- 节点：延迟胶囊按好/中/差着色，数字滚动；选中打勾弹簧弹出，切换中显示转圈。
- 首次进入页面卡片依次上浮淡入（只播一次）；加载中使用骨架屏闪光代替转圈；空状态图标轻微漂浮。
- 卡片在浅色模式带柔和投影，分段控件滑块改为弹簧；触感反馈贯穿开关、分段、底栏、电源键、节点选择。
- 「模糊效果」开关同时控制新界面的毛玻璃；关闭后退回实色。

## 页面
- 首页：运行状态卡（电源键、规则/全局/直连切换、重载、重启、待重启提示）、实时网速 + 走势图、流量/连接/内存/CPU、
  出口 IP 与地区、站点延迟、配置与订阅流量、广告过滤状态、日志/诊断/运行配置。
- 代理：策略组可展开，节点单/双列卡片，点选切换（等核心确认后才打勾），单节点/整组/全部测速，搜索；显示与排序（倒序、紧凑、按订阅分组、展开时收起其它组、显示隐藏组）；测速与 API（自定义测速 URL、外部 Clash API、历史采集）。
- 首页状态卡点按查看核心运行详情；新增本地 WebUI、运行文件入口；延迟自动刷新恢复；触感反馈遵循原开关。
- 活动：连接（全部/代理/直连、按应用分组、搜索、详情、断开）、流量排行（域名/应用/代理链，实时或 24 小时历史）、日志（等级筛选、搜索、正序/倒序、3 秒自动刷新）。
- 规则：规则集（**新增「全部更新」**，逐个显示进度/成功/失败）、订阅（全部更新 + 流量进度）、规则（搜索）。
- 设置：网络与分流（核心、模式、DNS、IPv6、TCP/UDP、QUIC、应用范围、CN IP、绕过网段、共享网络、Kill Switch）、
  应用名单、配置与订阅（文件导入、**链接导入**、订阅增删改、YAML 编辑器含校验）、广告过滤、核心管理（下载/更新/导入/删除）、
  开机自启、状态通知、主题、Web 面板（metacubexd）、测速站点、设置导入导出、恢复网络、关于。

## 广告过滤修复
- 根因：很多应用用自带 DoH/DoT、缓存或直连 IP 发起连接，Mihomo 只看到 IP，`RULE-SET,hetu-adblock` 这类**域名规则根本匹配不到**。
- 修复：开启广告过滤且源配置没有 `sniffer:` 时，运行副本自动加入嗅探（TLS SNI / HTTP Host / QUIC，`parse-pure-ip`，
  不改写目标地址），域名规则因此能命中。源配置里用户自己写的 sniffer 保持原样。
- 开关改为即时热重载生效（原来需要手动重启）。
- 广告页直接显示：是否生效、当前是否处于全局/直连模式（该模式下规则不生效，一键切回规则）、加载失败原因、
  最近拦截的域名（可一键加入白名单）、拦截强度（轻量/均衡/加强）、各规则源状态与更新、黑白名单。
- 新增单元测试 `AdblockSnifferTest`。
## V20.17 / 0.6.7-v20 — Secondary surface polish

- Secondary routes use a compact pinned frosted header instead of repeating oversized titles.
- Top bar supports Gaussian and progressive blur styles.
- Added Hx Theme Lab: theme, Monet, pure black, accent, blur style, floating/liquid dock, predictive back and global 80–120% UI scaling.
- Predictive back toggles are wired to the root navigation behavior; follow-edge can be disabled independently.
- Connection, rule, rule-set and log rows use continuous grouped surfaces instead of one rounded card per row.
- File manager, CNIP, network match, log viewer, app list, core manager, config/providers and ad-block pages share the same compact secondary scaffold.

## V20.18 / 0.6.8-v20 — Strategy card visual parity

- Standalone Strategy cards now reuse the same compact visual component as Panel > Strategy, matching the first reference screenshot instead of the oversized white-card variant.
- Card geometry is fixed to the compact 1.50:1 two-column ratio (single-column fallback keeps the same height), with 13dp corners and 10dp grid gaps.
- Strategy metadata is restored to the useful raw type/count form (`URLTest 36/81`, `Selector 36/81`, `Fallback 8/17`) instead of generic “自动测速/手动选择” copy.
- Group icons use the smaller 26dp visual well and 19dp artwork, so flags/app logos stop overpowering names and node text.
- Current-node text gets the full lower-row width; latency returns to a small wrap-content pill rather than a large fixed button.
- Press feedback keeps the reference spring scale; long-press group testing and the existing node accordion remain available on the standalone Strategy page.



## V20.19 / 0.6.9-v20 — Strategy screenshot parity

- Rebuilt the standalone strategy tiles against the first reference screenshot instead of merely sharing the previous card component name.
- Two-column strategy cards move from 1.50:1 to ~2.02:1, removing the oversized vertical dead space while preserving type/count/current-node/latency metadata.
- Group artwork loses the tinted icon well and duplicate/fallback bullets; explicit flags are rendered once and plain groups remain plain text.
- Expanded node grid no longer inserts a large secondary header; it grows directly below the tapped strategy row inside one light surface.
- Node cards are fixed to a compact 62dp height with 12dp corners, 7dp gaps and three clear lines: name, UDP/TCP, protocol + compact latency. Provider noise is removed from the card face.
- Selected node uses the reference lavender fill, thin muted-purple outline and a small top-right check rather than the thick blue focus border.
- Expanded floating controls collapse to the single compact blue group pill from the reference; the oversized locate FAB is removed from the visual path.


## V20.20 strategy direct-delay testing
- Strategy-card latency pill is now an independent action target: tapping it tests every node in that group without opening the group.
- Group cards still open/expand from the card body; delay taps are consumed by the latency pill.
- Both the Panel strategy grid and standalone strategy page now share the same behavior.
- While a group test is running, the pill morphs into a compact progress state; completed latency values slide/fade into place.
- Per-node delay results update progressively during the group test, so reachable counts and latency badges feel live instead of jumping at the end.


## V20.26 / 0.7.6-v20 — Panel deep-link routing fix

- Fixed Home subscription card opening Panel but landing on the previously restored tab instead of Subscriptions.
- HxTabbedPage now ignores the Pager's stale first restored-page emission so an external section request wins deterministically.
- Home -> Subscriptions now lands directly on the Subscriptions tab while normal user swipes and chip taps continue to sync back to panelSection.
## V20.28
- 重做面板 7 栏 Tab：更矮的圆角矩形、轻描边未选中态、干净实底选中态。
- 收紧标签间距与内边距，降低胶囊感，保留液态滑动 Morph、自动居中和触感反馈。


## V20.44 — 2026-09-30
- Rebalanced tool-page proportions against the reference screenshot: larger title, 64dp rows, 26dp icons, 18sp titles and 13.5sp summaries.
- Restored the reference pale blue-violet canvas/card colors and 12dp group spacing.
- Slightly enlarged dock icon/label scale without returning to the oversized V20.42 shell.


## V20.45 root chrome parity
- Unify Home/Panel/Tools/Settings large-title geometry and canvas.
- Settings root now uses the same Hx root-title scaffold while keeping Miuix preference cards.
- Restore real Miuix drawBackdrop liquid dock with runtime blur/refraction/highlight.

## V20.47 · settings parity hotfix
- Basic proxy settings now match the reference hierarchy: core, mode, IPv6, auto-overwrite, startup config, config selection only.
- Long-press config actions are real: edit, export, rename, delete. YAML editor explicitly enables editing and save/validate.
- Other proxy settings rebuilt to the reference structure: performance, QUIC, Mihomo DNS forwarding, TCP/UDP, DNS TCP/UDP/strategy, resource limits and vendor firewall startup cleanup.
- DNS protocol switches, CPU affinity, memory limit, I/O priority and performance mode are applied by the Root runtime instead of being UI-only.
- Removed duplicate advanced-settings entries from core/reference pages.
