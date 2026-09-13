# 辟尘 Bichen · 0.3.0-beta.1

Root 去广告模块。该 ZIP 是系统无损 hosts 引擎：开机加载已校验规则，无常驻拦截进程。兼容分支包含 Magisk 26+、KernelSU / ReSukiSU 和 APatch；**这是测试版，未完成 HyperOS、Android 16 的真机安装和运行验证。**

## 安装与升级

管理 App 内置同版本模块安装包，可以导出后交给当前 Root 管理器安装。也可以直接安装独立模块 ZIP。通过当前 Root 管理器刷入后重启，再给 App Root 权限，检查实际挂载和诊断。不要在 recovery 安装，不需要更换 Root 方案。

安装和升级保留已有订阅、黑白名单及暂停状态。模块包含离线规则，安装期间不会联网下载。遇到其他 hosts 模块会报告冲突，不会删除其他模块或覆盖它们的挂载。

## 这版模块的变化

- 新增可选 HaGeZi Light 轻量增强：内置 35,280 个精确域名，默认关闭；与默认两源去重合并后为 40,664 个有效域名。域名数不代表实际广告拦截率。
- 配置恢复把白名单、黑名单、全部订阅开关放入一次事务，任一文件或挂载失败时整批回退。
- 旧版规则结构升级为 schema 2，原有名单、订阅开关、暂停状态、规则更新时间和上一份规则备份全部保留；新来源不会被静默开启。
- 增加只读规则健康检查，区分规则损坏、挂载失败、其他 hosts 冲突，以及正常暂停。
- 安装时校验必要文件、版本与离线规则 SHA-256；已有数据指针异常时停止并保留数据，避免误初始化覆盖。
- 开机只校验和恢复已保存规则，不联网、不重编译，无定时恢复或常驻轮询；早期限制 8 秒，稍后阶段限制 30 秒。

## 日常使用

AdAway 与秋风纯广告默认开启，秋风隐私增强和 HaGeZi Light 默认关闭。原三源已重新核验，内容与上一版相同，不虚报更新增量。白名单优先于黑名单和订阅，均精确匹配完整域名。整批订阅更新一次提交，任何文件无效时保留整批旧规则；更新失败时恢复之前挂载。

疑似误拦先暂停并重开异常 App 对比；知道域名后用域名排查查看命中规则，再加白名单。暂停会跨重启保留，需要手动恢复。规则发生变化后可回滚上一份规则；中间暂停或恢复不会消耗这份备份。

首页规则数是规则域名数，不是拦截次数；操作日志不是网络请求日志。状态中的挂载验证不代表所有应用实际采用系统 hosts。用户配置只有 Root 可访问。

## 应用放行与支持范围

**本 hosts 引擎对所有采用系统 hosts 的 App 生效，无法按包名单独放行。** App 的按应用模式必须由独立网络引擎实现，同时暂停全局 hosts，不能只保存一个应用列表就声称放行成功。模块通过 `export-domains` 提供去重后的有效规则，已经应用白名单优先结果。

独立广告域名可以通过 hosts 拦截；同域业务和广告、内置素材、占位元素、直连 IP、自带加密 DNS 或远端代理解析无法保证处理。规则不会自动匹配全部子域名。拦截广告不会自动获得激励奖励。

模块不修改私人 DNS、VPN、TPROXY、eBPF、iptables 或系统应用启停。使用其他网络工具时需要自行验证其解析路径。完全停用请在 Root 管理器禁用模块后重启；卸载只卸载自身挂载，用户配置保留以便重装。

## CLI

安装后的入口：`/data/adb/modules/bichen/bin/bichen`。由 Root 调用，自动使用所属框架 BusyBox 并进入 init 全局挂载命名空间；JSON 输出写 stdout，返回码非零表示失败。

- 状态及排查：`status`、`health`、`diagnose`、`logs`、`check-domain DOMAIN`；`preflight` 校验安装包解压后的必要文件和离线快照。
- 保护状态：`apply`、`enable`、`pause`、`rollback`。
- 订阅：`set-source ID 0|1`、`import-source ID /FILE`、`import-batch ID /FILE [ID /FILE ...]`，ID 为 `adaway`、`china`、`tracking` 或 `hagezi`。
- 名单：`list-allow`、`list-block`、`add-allow DOMAIN`、`remove-allow DOMAIN`、`add-block DOMAIN`、`remove-block DOMAIN`。
- 导出：`export-config` 返回 `{ok,schema,version,configRevision,enabled,allow,block,sources:[{id,enabled}]}`；`export-domains` 返回 `{ok,configRevision,domains}`，不触发联网或流量监视。
- 完整配置：`import-settings /ALLOW_FILE /BLOCK_FILE ADAWAY CHINA TRACKING [HAGEZI]`，开关均为 `0` 或 `1`，省略新开关时保持其现状；导入不改变当前启用或暂停状态。
- 批量名单：`import-user-lists /ALLOW_FILE /BLOCK_FILE` 原子替换两份名单，支持空文件清空；每份最大 8 MiB，必须使用绝对路径。

调用方必须逐参数转义，不可把外部输入直接拼接到 shell。导出的域名集合最大 500000 条，调用方需要设置足够且有界的输出上限。订阅下载由 App 完成，模块只接收本地私有快照并校验、事务式提交。

## 安装失败时

如果管理器只显示 `specified file not found in archive`，且没有出现“已进入辟尘安装脚本”，错误发生在管理器读取压缩包的阶段。KernelSU 会先按精确路径读取 ZIP 根目录的 `module.prop`；仅凭截图无法证明具体下载文件缺失了哪个条目。

请使用 App 导出的 `Bichen-0.3.0-beta.1-module.zip` 或同名独立模块包，不能选择源码 ZIP 或 APK。不要自行加一层文件夹再压缩，也无需为了这个错误添加 Recovery 安装脚本。App 内置导出会检查模块包结构与摘要。若仍失败，保存 Root 管理器完整日志及出错 ZIP，才能核对实际文件。脚本已经启动后的预检失败会明确说明必要文件或规则哈希异常。

## 验证

源码中的 `python3 tests/module_engine_test.py` 使用 `/tmp/bichen-module-*` 隔离目录与模拟挂载，覆盖无效整批更新、挂载失败回退、名单优先、重启暂停状态、回滚、导出、注入输入、清单截断、冲突挂载和卸载保留配置。不会触碰测试主机的 hosts、iptables 或 Android 路径。

这类测试不能代替设备上的 BusyBox、SELinux、挂载命名空间、Magisk/KSU/APatch 和系统省电策略验证。原创代码采用 GPL-3.0-or-later，第三方规则来源与许可见 `THIRD_PARTY_NOTICES.md`。

框架实现依据：[Magisk 开发文档](https://topjohnwu.github.io/Magisk/guides.html)、[KernelSU 模块指南](https://kernelsu.org/guide/module.html)、[APatch 模块开发指南](https://apatch.dev/apm-guide.html)。
