# 河图代理 P0 加固说明（2026-09-16）

本轮针对 Root 透明代理数据面做 P0 加固，目标是避免 Android `netd` fwmark 冲突、核心异常后残留劫持规则、PID 误杀以及错误配置接管网络。

## 已修复

- 不再使用 `0x2333/0xffff` 覆盖 Android fwmark 的低 16 位 `netId`。
- TPROXY / Enhance 启动时从 Android 当前保留位区间中动态选择未冲突的单 bit mark，并使用同 mask 写入。
- 动态分配独立 policy-routing table 与 rule priority，避开已有规则。
- 新 mark/table/pref 写入运行时状态文件，stop/crash-recovery 只删除河图自己的路由对象。
- 清理旧版本 `0x2333` 规则时不再 `flush table 100`，避免误删系统/OEM 路由。
- 启动透明代理前执行 Mihomo `-t` 配置校验；失败时保持原网络不被接管。
- PID 停止前校验 `/proc/<pid>/cmdline`，降低 PID 复用误杀风险。
- status 检测到核心已退出但 BICHEN 链/IPv6 临时状态仍存在时，自动清理并恢复直连（默认 fail-open）。
- IPv6 临时禁用保留原接口状态，并在 stop/异常恢复时还原。

## 仍需继续

- 将 Root core 拆到专用 UID 或给核心主动拨号设置独立 BYPASS mark，替代现在较宽的 `uid-owner 0` 回环豁免。
- netfilter 规则改为 `iptables-restore --noflush` 原子事务。
- 增加 Kill Switch（显式 opt-in，默认关闭）。
- DNS / DoT / Private DNS / WebRTC 泄漏自检。
- 原生策略组展开层统一 Surface / Empty / Error 状态，彻底消除白块占位。
- `/connections` 从轮询迁移 WebSocket。

## 真机验收

- [ ] Android 14 / 15 / 16 启动 TPROXY 成功
- [ ] `ip rule` 中河图 mark mask 不覆盖低 16 位
- [ ] Wi-Fi → 5G → Wi-Fi 切换后不掉网
- [ ] 杀掉 Mihomo 后进入状态页可自动清除残留规则并恢复直连
- [ ] Stop 连续执行两次均正常
- [ ] 错误 YAML 启动失败时手机网络保持直连
- [ ] IPv6 enable / bypass / disable 三种模式均可恢复
- [ ] TPROXY TCP/UDP 均可工作
- [ ] Enhance：TCP Redirect + UDP TPROXY 均可工作
- [ ] Google / Chrome / QUIC / 游戏 UDP 无回环
