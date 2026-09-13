# Mihomo integration: first Android VPN preview

基于 test.6，在独立 feature/mihomo-vpn 分支集成。未合并 main，未发布正式版。

## 当前实现

- 固定 MetaCubeX/mihomo Go 内核 ac017cdd246ce8bd547653d927e7bf77d7ee73d5；Java/JNI 接口不开放外部控制端口。
- 应用私有目录保存完整 YAML / HTTPS 兼容订阅，保留上一份配置；原导入文件不原地改写。不提供节点，不发送到订阅转换服务。
- Android VpnService 建立 IPv4/IPv6 全路由，Mihomo gVisor 使用实际 TUN 文件描述符处理流量。自身 UID 排除，内核出站不回环。旧 DNS VPN 不同时运行。
- 有效广告域名作为精确匹配规则集合注入；手动白名单从辟尘集合扣除，不插入 DIRECT。原配置自身的过滤规则不受这份白名单覆盖。
- 节点组读取、选择、延迟测试，以及内核活跃连接快照。不把活跃连接冒充全部历史或每条 DNS 拦截事件。
- 可选联动已安装 hosts：启动前同步、暂停，停止后按原意图恢复；不重新启用管理器停用/待卸载的模块。

## 范围与限制

本轮只有普通 Android VPN 接管。Root TPROXY/eBPF 不接通；自定义 listeners 明确拒绝，不静默移除。原 TPROXY 端口、tun 和监听字段仅在运行时副本适配，不改原文件；DNS/STUN 和用户分流保留。仅支持 mode: rule，以免广告规则被全局模式绕过。

没有内置服务器，没有私人订阅，没有未授权自动重启。规则修改在下一次连接时加载。节点类型由固定上游内核支持范围决定。跨设备、真实付费订阅、私人 DNS、WebRTC、UDP 游戏、断网/休眠、Root 管理器尚需真机验收，不宣称零泄漏。

目前代理界面展示活跃连接；旧“请求活动”仍属于原 DNS 引擎。完整历史、拒绝事件归档、更多应用策略、Root 入站均未称已完成。

## 构建与测试

native/bridge 原创接线使用 Mihomo GPL-3.0 代码。固定上游提交、go.mod/go.sum、NDK 和完整桥接源码可复现内核；APK 含上游 LICENSE 与 commit。

`.github/workflows/mihomo-native.yml` 编译 arm64-v8a/x86_64 共享库，使用 16KiB ELF 页对齐。`.github/workflows/mihomo-preview.yml` 校验其哈希后打包。产物为私有签名中转，不是可刷写 ZIP。

`tests/proxy` 在模拟器安装第二个独立 UID 测试应用，HTTP 请求真正经过 VPN 到 CI 上的受控 HTTP CONNECT 节点，并验证广告域名不进入该节点；不是 App 自己绕过 VPN 的请求，也不是合成成功状态。以实际 CI 结果为准。

正式向用户交付时须沿用既有私有测试签名并提升版本码；CI临时证书仅作模拟器验证，私钥不上传仓库。
