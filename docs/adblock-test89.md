# DNS 过滤修复与离线规则（test.89）

原解析器会把 `||domain^$badfilter`、`$dnstype`、`$denyallow`、`$dnsrewrite` 等所有修饰符直接丢弃，使取消规则、条件规则变成整域拦截或整域放行。test.89 只将可表示为域名后缀的规则投影到 Mihomo；`badfilter` 按原始规则文本取消同一订阅中的对应规则，且不受顺序影响。条件修饰符、优先级修饰符、路径与复杂通配模式不再错误降级为整域规则。普通 hosts / 域名列表仍使用河图现有的后缀匹配策略。

依据：[AdGuard DNS 规则语法](https://adguard-dns.io/kb/general/dns-filtering-syntax/)。河图并非完整的 AdGuard 规则执行引擎；无法表达的规则不会被冒充为已完整支持。

## 离线快照来源

- 上游项目与作者：[AdGuard Team / AdGuardSDNSFilter](https://github.com/AdguardTeam/AdGuardSDNSFilter)。
- 原始下载地址：https://adguardteam.github.io/AdGuardSDNSFilter/Filters/filter.txt
- 下载时间：2026-09-19T23:14:51.057708+00:00
- 上游 Last modified：2026-09-19T22:12:16.337Z。
- SHA-256：`6f7ce8ac19c5c34cd910cd8d3b8e3430b0eee5121a3a5f63bbb84927ed09a0df`。
- 原始大小：4,390,977 字节，182,579 行；解析后 180,025 个唯一规则（包含例外）。
- 原文未修改，保留了头部及各子列表来源；随 APK 分发 `assets/rules/adguard.txt`。
- 许可：[GPL-3.0](https://github.com/AdguardTeam/AdGuardSDNSFilter/blob/master/LICENSE)，完整许可随源码根目录 `LICENSE` 与 APK `assets/ADGUARD-LICENSE` 分发。过滤表包含的子列表归属保留在原始文件中。

`master/Filters/filter.txt` 并不存在；该文件由上游构建到 `gh-pages`。订阅主镜像与 GitHub Raw 回退已改为 `gh-pages`，GitHub Pages 官方地址仍保留。

## 已安装用户的迁移

旧缓存只有展平后的域名，无法通过重读旧文件恢复被丢失的修饰符。首次加载时，河图仅对旧解析格式的内置 AdGuard 来源，用随包原始快照重新解析，并原子提交一个新规则代次。个人黑白名单、来源启用状态、其余来源及用户 YAML 保留。新代次使 Mihomo 导出缓存自动失效；`adguardParserVersion = 2` 随代次保存，后续加载不重复迁移。

已使用新格式、外部模块快照、以及记录的成功下载时间晚于本快照下载时间的来源均不替换。最后一种情况下，保留较新来源，并在用户下次常规更新时由新解析器处理。迁移本身不联网，不要求开启自动更新。

## 验证

`tools/test_adblock_inspection.py` 执行真正的 RuleStore 解析器、并发导出快照及 Mihomo 过滤链识别测试。覆盖取消规则的先后顺序、条件放行与拦截不扩大范围、hosts 独立条目保留、UTF-8/体积/中断限制，以及迁移跳过较新或已升级来源。随包 AdGuard 原文也是解析测试输入。

ASCII 域名热路径移除了逐域 IDN 转换与逐标签正则编译；保留国际域名转换及域名合法性校验。开发机上完整 AdGuard 原文解析约 0.30 秒（180,025 条），只代表主机测量，不代表 Android 实机启动时间。
