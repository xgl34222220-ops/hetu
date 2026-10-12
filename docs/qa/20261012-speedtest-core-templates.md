# 测速「已到时限」与非 Mihomo 内核内置模板（2026-10-12，分支 hark/speedtest-cores-2026-10）

## 测速超时根因（源码诊断，未真机复现）
- 全部节点测速 / 未固定自动组的叶子测速共用 **固定 20 s 总预算**（`LatencyProbeBudget.DEFAULT_TIMEOUT_MS`），与节点数无关。
- 每轮最多 6 个请求在途（V20.95 调度契约冻结测试固定为 6），死节点在核心侧要占满 5 s（`?timeout=5000`）。
  20 s 只够 ~22 个死节点（6 × 20 / 5 = 24，减去 /proxies + /providers/proxies 快照读取），订阅一大或死节点一多，
  后面的节点永远轮不到，整轮落入 `IncompleteLatencyProbe` →「本轮测速已到时限」。
- 控制接口回复丢失（读超时 6.5 s / 连接被核心关闭）既不是节点结果也不重试，节点只是被跳过。
- 提示不区分「单节点超时」（-1）与「整轮已到时限」，用户看到的只有后者。

## 改动
- `LatencyProbeBudget.waveTimeoutMs(n)`：4 s 元数据 + ⌈n/6⌉ × 6.5 s 首轮 + 一半重试余量，下限 20 s（小规模与冻结测试一致），上限 150 s。
  `globalDelay(targetHint)`（首页/面板「全部测速」传入节点数）与 `groupDelay(group, targets)` 使用该预算。
- 首轮结束后，对「回复丢失」（SocketTimeout / SocketException / EOF，含核心未回任何字节就关连接）的节点重试一次；
  核心确认的 504 超时 / 503 失败 / 401 / 运行时已变化 / 总预算到期均不重试（冻结测试 `requestCount == 10` 固定 504 只测一次）。
- 只有首轮未跑完才报「本轮测速已到总时限：已完成 X / N 个节点，部分结果未返回，其余保留上次读数」；重试轮被截断保留首轮结果。
- 全部测速完成提示附「k 个节点超时」，与整轮时限区分。
- 并发保持 6（`ProbeSchedulingPerformance95Test` 冻结断言 `peak <= 6`），未采纳 8–16 的建议。
- 测速地址不变（`https://www.gstatic.com/generate_204`，回退 `https://cp.cloudflare.com/generate_204`）。

## 非 Mihomo 内核
- sing-box / reF1nd：clash_api `/proxies/{n}/delay`、`/group/{g}/delay` 与 Mihomo 同路径，同一预算/重试逻辑生效。
- Xray / V2Fly / Hysteria：无 Clash 控制接口，测速入口显示「X 没有 Clash 兼容控制接口，无法逐个节点测速」卡片（既有逻辑，核对无误）。

## 内置模板（BundledCoreTemplates.java）
| 内核 | 文件名 | 本机核心自检 |
|---|---|---|
| sing-box / sing-box reF1nd | 河图内置-sing-box.json | `sing-box check` 1.14.3 (linux-arm64)：OK |
| Xray | 河图内置-Xray.json | `xray run -test` 26.3.27：Configuration OK |
| V2Fly | 河图内置-V2Fly.json | `v2ray test` 5.40.0：Configuration OK |
| Hysteria 2 | 河图内置-Hysteria.yaml | `hysteria client` 2.6.4：解析通过（server 换成 127.0.0.1 后无 invalid config） |

- 另将四个模板经 `ProxyCoreConfig.build`（TPROXY 与 Redirect 两种模式）实际生成启动配置，再用上述核心逐个检查：全部 OK。
- 入站端口 19898 / 19797 / 11053（`MihomoStartupConfig`，RootProxyManager 传给 hetu-root.sh），sing-box clash_api 127.0.0.1:29090；
  启动时河图仍会替换自己负责的入站与控制接口。
- 占位节点只用保留域 `example.invalid`、全零 UUID；无真实服务器或凭据。仍含占位节点启动时给出明确警告。
- 配置库为该内核列出模板（带「内置」标记，不可删除/重命名）。列出与启动都不会选中模板。
- 用户决定（2026-10-12，集成到 PR #33 时）：用户在「代理核心」里**主动选择**非 Mihomo 核心（sing-box/reF1nd/Xray/V2Fly/Hysteria）且该核心尚未选中自己的配置时，自动选中它的内置模板（`ProxyConfigLibrary.adoptTemplateOnCoreSelect`）；已选自己配置的核心与 Mihomo/Smart 用户不受影响；未重新选择核心的老用户启动时仍转换所选 Mihomo 配置。测试：`CoreTemplateAutoSelect104Test`、`test_core_templates93.py`。

## 未在真机验证
- 大订阅（数十至上百节点）下真机「全部测速」耗时与提示；sing-box clash_api 实机测速。
- 各模板填入真实节点后在 Root TPROXY/Redirect 下的实际连通（sing-box 远程 rule-set 经 PROXY 下载、Xray/V2Fly geo 数据随内核 zip 提供）。
