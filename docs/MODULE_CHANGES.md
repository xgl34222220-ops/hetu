# 模块变更与核验：0.3.0-beta.1

## 实际落实

本版新增可选 HaGeZi Light，默认关闭，保留 AdAway、秋风纯广告、秋风隐私增强的来源和默认开关。没有把不同来源偷换到旧 ID。根据离线种子经过实际解析、精确域名去重后的结果：

| 来源 | 精确域名 | 默认 | 原始文件字节 |
| --- | ---: | --- | ---: |
| AdAway | 6,540 | 开启 | 243,454 |
| 秋风纯广告 | 626 | 开启 | 33,099 |
| 秋风隐私增强 | 840 | 关闭 | 44,253 |
| HaGeZi Light | 35,280 | 关闭 | 675,282 |

默认合并 7,081 个域名；用户启用 Light 后合并 40,664 个，净增加 33,583。前面三源本次再次从官方地址取回，与上一版字节相同；本版没有宣称这些源出现新内容。实际抓取时间、哈希、行数和解析条数记录在 `module/rules/snapshot.json`。

HaGeZi 官方把 Light 定位为较轻的通用保护；Normal 同次抓取为 180,141 个域名、3,410,151 字节。考虑移动设备体积及误拦恢复成本，只内置 Light。任何列表仍可能误拦，用户可以关闭这一来源、域名放行或回滚。上游目前提供 `wildcard/light-onlydomains.txt`；旧 `hosts/light.txt`、`hosts/multi.txt` 已返回 404，不能继续使用旧地址。[HaGeZi 官方说明](https://github.com/hagezi/dns-blocklists)、[许可证](https://github.com/hagezi/dns-blocklists/blob/main/LICENSE)。

模块和现有 DNS 引擎仅使用精确域名，父域不会自动匹配子域。不能把域名数量换算成广告拦截率；这不处理同域广告、应用内置素材、页面占位或自带 DoH。秋风纯广告与隐私分开使用户可以按需选择，而不是静默启用全部类别。[秋风官方变更](https://github.com/TG-Twilight/AWAvenue-Ads-Rule/releases/tag/1.7.6-release)。

## 旧配置与事务

- schema 1 的完整性清单仍按旧 14 项严格核验；schema 2 为 16 项，加上新源域名及开关，不能通过截断清单漏验。
- 安装只在新建的私有副本中添加默认关闭的 Light，原名单、三源设置、启用/暂停、hosts、更新时间、旧备份指针保持不变。原已发布快照不被原地修改，安装阶段不改变当前挂载；重启再应用新快照。
- 旧 schema 1 备份仍可回滚，回滚时在私有事务中补齐新源，仍保持当前启用/暂停状态。
- 新命令 `import-settings ALLOW BLOCK ADAWAY CHINA TRACKING [HAGEZI]` 一次提交名单及订阅开关。旧五参数调用保留现有 Light 开关。解析、配置校验或挂载失败均不会半提交。
- 非空但无效的旧规则指针会报告错误并保留文件，不再当成首次安装而覆盖用户状态。

## 安装与健康检查

`preflight` 检查解压后的模块必要文件、身份/版本、四条来源、规则文件哈希；安装流程会调用它。此检查不联网，不在每次开机额外运行。`health` 只读返回结构化检查结果，区分快照完整性、全局命名空间、实际挂载、冲突、未完成事务及内置快照。正常暂停显示正常；通过不表示所有应用都采用系统 hosts。

KernelSU 官方实现会先从 ZIP 精确读取 `module.prop` 才解压执行模块脚本。之前截图中的错误能说明管理器的归档读取没有完成，但截图没有指出具体失败条目，也无法证明用户选中的 ZIP 与构建产物一致。应核对下载包与完整日志，不能根据该截图假定缺少 META-INF 或要求用户更换 Root 方案。[KernelSU 安装实现](https://github.com/tiann/KernelSU/blob/main/userspace/ksud/src/module.rs)。

框架路径保持官方 BusyBox：Magisk `/data/adb/magisk/busybox`（支持 `magisk --path` 回退），KernelSU `/data/adb/ksu/bin/busybox`，APatch `/data/adb/ap/bin/busybox`。安装时检查所需 applets。启动使用有界恢复，无联网和常驻轮询。本版不添加定时恢复，以免旧暂停计时在用户切换 DNS VPN 后重新挂载全局 hosts，破坏应用放行；手动暂停继续跨重启保持。[Magisk 文档](https://topjohnwu.github.io/Magisk/guides.html)、[KernelSU 文档](https://kernelsu.org/guide/module.html)、[APatch 文档](https://apatch.dev/apm-guide.html)。

## 回归范围

`python3 tests/module_engine_test.py` 覆盖旧代升级/旧备份回滚、默认关闭与可逆启用、完整配置原子导入（无效文件/开关/挂载失败）、暂停时导入不恢复 hosts、健康检查区分损坏和冲突、无效旧指针不覆盖，以及既有事务、名单优先、重启恢复、卸载保护和输入校验。

测试使用隔离目录及模拟挂载；它不证明 HyperOS / Android 16 的 SELinux、挂载命名空间、设备管理器刷入或长期运行通过。Android 真机仍需验证安装、暂停/恢复、规则切换、VPN 互斥及重启稳定性。
