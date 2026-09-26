# test.134：广告过滤执行顺序与热应用

确认的失效路径：旧生成器在没有识别到源广告规则时把河图过滤放在最后的 MATCH 前。有源广告规则时也无条件沿用其位置。更早的 China / 其他普通 RULE-SET、GEOSITE、GEOIP 分流可先匹配，使本地过滤永远没有机会执行。Controller 能看到 provider 只说明文件被加载，不代表流量经过过滤。

本次仅改变河图私有运行副本。原 YAML 不修改，所有原规则之间的相对次序不变。河图过滤放到第一个普通路由之前；保留列表开头的明确 REJECT / REJECT-DROP 安全规则，以及精确域名、域名后缀、域名通配符、进程 DIRECT 例外。名称含白名单或独立词段 whitelist / allowlist / white-list / allow-list 的 DIRECT provider 也保留优先级；China、Bank、Direct 等一般路由名称不会被猜成白名单。用户自己的河图白名单仍是过滤条件的排除项，不强制 DIRECT。

广告开关现在调用配置热重载，不重启 Root 传输进程；仅在 Controller 返回对应 REJECT 规则和两个已加载 providers 后提交应用状态。失败显示未确认并重新读取实际链。规则内容未变化时，手动更新仍会重试运行 provider，以修复此前下载成功但热更新失败的情况。

运行状态还检查 Controller 的实际流量模式。global / direct 不执行普通 rules 时，不再显示广告过滤已经生效，并提供用户主动切回 rule 模式的入口。实际拦截数仍来自 Controller 或运行日志，不使用规则数量冒充拦截次数。

验证：tools/test_adblock_inspection.py 的解析/快照测试、38 项检查覆盖普通路由遮蔽、明确域名和 provider 白名单、拒绝规则、quoted/indentless YAML、空列表、Controller 的真实 Logic.Payload 序列化、禁用规则和运行模式。tests/ProxyContinuityTest.java 生成的真实运行 YAML 新增提前命中的 broad-routing DIRECT provider；native/bridge/root_filter_test.go 用打包所用 Mihomo 解析并验证域名仍被拒绝、白名单沿原路由放行、清空及恢复 provider 无需重启。原先功能只做域名过滤，未新增页面元素隐藏或 HTTPS 内容解密能力。

规则次序依据：[Mihomo 官方路由规则文档](https://wiki.metacubex.one/config/rules/)。
