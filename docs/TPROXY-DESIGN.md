# 河图 Root TPROXY 模式

## 目标

TPROXY 是河图代理模式的 Root 主路径。它不占用 Android VPN 槽位，并保留用户导入配置里的 `listeners`、`tproxy-port`、eBPF、`interface-name`、`routing-mark` 等 Root/Mihomo 配置。

## 运行边界

- Mihomo 以 Root 进程运行，而不是普通 App UID。
- App 只负责配置、启动/停止、状态和日志。
- TPROXY 防火墙链使用独立 `HETU_*` 名称，停止/异常恢复时只删除自己的链和策略路由。
- 不修改用户原 YAML；运行时复制到 `/data/adb/hetu/config.yaml`。
- 默认绕过回环、局域网、链路本地、组播和保留地址，避免把本地网络导入代理环路。
- Root UID 0 的 OUTPUT 流量不再次透明代理，避免 Mihomo 自身出站回环。
- 如果设备存在 IPv6 默认路由但 `ip6tables` 不支持 TPROXY，启动应失败而不是静默产生 IPv6 泄漏。

## 预检

启动前必须确认：

1. Root UID 0 可用；
2. `ip`、`iptables` 可用；
3. IPv4 `TPROXY` target 可用；
4. 配置存在一个可识别的 TPROXY 监听端口（顶层 `tproxy-port` 或 `listeners` 中 `type: tproxy`）；
5. Root Mihomo 可执行文件已安装并能返回版本；
6. 如果存在 IPv6 默认路由，必须同时确认 `ip6tables` TPROXY。

失败时不残留策略路由和防火墙链。
