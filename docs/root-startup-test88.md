# test.88：启动与 BoxProxy 对照

test.86、test.87 未解决用户手机上的微信消息延迟。此前的保活修复不能视作本次问题的已确认根因。test.88 修复源码中可以复现的问题，并补充实机阶段耗时；未取得手机诊断前，不承诺微信延迟已解决或已经实现秒启动。

## 对照依据

对照 BoxProxy 原生组件提交 `c68d3471a5fc01494e965e436d69fd8c395cf7a4`：

- [启动事务](https://github.com/boxproxy/boxproxy/blob/c68d3471a5fc01494e965e436d69fd8c395cf7a4/boxctl/src/control.rs)：并行启动核心和安装规则；河图保留入站、DNS、控制接口就绪后再接管网络。
- [规则批量提交](https://github.com/boxproxy/boxproxy/blob/c68d3471a5fc01494e965e436d69fd8c395cf7a4/boxctl/src/rules/batch.rs)：通过 iptables-restore 提交；河图本次先消除不存在的规则清理和重复部署。
- [本机地址保护](https://github.com/boxproxy/boxproxy/blob/c68d3471a5fc01494e965e436d69fd8c395cf7a4/boxctl/src/rules/local_ip.rs)：避免本机连接回复再次进入透明代理，公网 IPv6 也受保护。
- [配置生成](https://github.com/boxproxy/boxproxy/blob/c68d3471a5fc01494e965e436d69fd8c395cf7a4/boxctl/src/core_config/mihomo.rs)：保留源 listeners，部分非 TUN 模式将 DNS 改为 redir-host。河图重建所选运行模式的入口并保留源 fake-ip 策略，因此相同源 YAML 不足以证明实际网络路径相同。

BoxProxy 原 shell 版本在 arm64 上选择 Android Mihomo，不能把两者差异简单归因于 Android 与 Linux 编译目标。

## 修复范围

- 核心身份通过实际可执行文件路径确认。命令行仅仅包含核心路径的 su、timeout、shell 不再被当作核心，也不能成为清理目标。
- 清理前读取一次各表的链列表，跳过不存在的私有链；读取失败仍走原逐条清理，不把探测失败当作“没有规则”。
- 合并启动时的进程与核心版本标识查询；配置及暂存脚本相同时跳过重复写盘。核心部署统一受事务锁保护。
- 共享网络的 TPROXY 链保护发往本机的回复；回环接口的应用接管和独立 DNS 劫持不受影响。
- 诊断保留实际启动的配置准备、部署、核心/网络、最终检查等耗时；重复启动请求不覆盖真实启动记录。

本次没有复制 BoxProxy 的并行接管或改成“核心进程存活即成功”。远程 provider 首次下载、手机 Root 执行开销等仍可能影响启动时间，应以新增实机计时为准。

## 实机复核

在延迟出现时打开“高级设置 → 消息与网络诊断”，复制结果。需要核对实际核心版本、运行模式、共享网络和 IPv6 状态、微信连接命中规则、最近恢复事件、各启动阶段耗时。诊断不采集聊天内容。

同时记录 BoxProxy 的版本、核心版本、运行模式和 DNS 模式，才能做有效对照。首次远程 provider 加载与已有缓存后的启动应分别计时。

DNS/WebRTC 源拒绝规则、DNS 接管及 UDP 防裸连保护继续保留；不新增整个微信应用的强制直连豁免。
