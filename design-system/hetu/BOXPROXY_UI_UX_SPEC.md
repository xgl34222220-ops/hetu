# BoxProxy 客户端前端 UI/UX 架构与工程规格

> 本文档是河图代理工作区后续 UI/UX 的主基准。实现时优先保证真实功能、真实状态与可点击性，禁止用占位数字伪装已实现功能。

## 1. Design System Tokens

### 色彩

| Token | Light | Dark / OLED |
|---|---|---|
| `--bg-base` | `#F4F6F9` | `#121212` / `#000000` |
| `--bg-surface` | `#FFFFFF` | `#1E1E1E` |
| `--bg-hero-card` | `#EDF4FF` | `#172338` |
| `--primary` | `#2563EB` | `#3B82F6` |
| `--primary-container` | `#DBEAFE` | `#1E3A8A` |
| `--success` | `#10B981` | `#34D399` |
| `--warning` | `#F59E0B` | `#FBBF24` |
| `--danger` | `#EF4444` | `#F87171` |
| `--text-primary` | `#0F172A` | `#F8FAFC` |
| `--text-secondary` | `#64748B` | `#94A3B8` |
| `--text-muted` | `#94A3B8` | `#64748B` |

### 形状与质感

- Hero / Bottom Sheet：24–28dp。
- 标准卡片：16–20dp。
- 胶囊：999dp。
- 顶栏 / 悬浮底栏：20px 等效模糊，浅色约 82% 透明白，内描边 `1px rgba(255,255,255,.4)`。
- 普通数据卡禁止堆叠厚阴影；强调层级主要使用背景、间距、字体和轻描边。

## 2. App Shell

- Edge-to-edge，所有页面严格避让状态栏/导航栏 Safe Area。
- 顶栏向上滚动时从大标题过渡到标准标题与半透明磨砂背景。
- 底部导航：`首页 / 面板 / 工具 / 设置`。
- 面板 Tab 可在主题设置中隐藏。
- 悬浮底栏左右 16dp、底部 12dp；选中项使用主色文字/图标 + 横向滑动的淡蓝胶囊。

## 3. 首页

- Hero：运行状态、运行时长、核心、模式、配置；右侧实心主色圆形勾选徽标；底部三等分 `重载 / 停止 / 重启`。
- WebUI 与日志为 1:1 对称快捷卡片。
- 网络延迟：Baidu / Cloudflare / Google，支持重新测试。
- 2×2 状态区：
  - LAN/WAN 点击翻转（LAN IP + 接口；WAN IP + 国家/地区）。
  - 实时上/下行速率。
  - 订阅已用 / 总量 / 剩余百分比。
  - 内存 + CPU。

## 4. 面板

分段 Tab：`节点 / 概览 / 订阅 / 连接 / 规则 / 规则集`。

### 节点

- 双列策略组卡片。
- 显示组名、策略类型、可用节点数、场景/配置 Icon、当前出口、延迟胶囊。
- 延迟颜色：`<100ms` 绿色、`100–300ms` 橙色、`>300ms / 超时` 红色。
- 点击策略组打开 Bottom Sheet，节点行显示协议与可单独测速入口。

### 概览

- 上行 / 下行两张大速率卡。
- 累计上传 / 下载。
- 真实最近 60 秒双曲线速率图。

### 订阅

- 名称、剩余天数、进度、同步按钮。
- 上传 / 下载 / 剩余三列指标。
- 到期时间与最近更新时间。

### 连接

- 活跃 / 已关闭过滤。
- 一键终止全部活跃连接。
- 目标、元信息、路由链、上传/下载累计和单项终止。

### 规则 / 规则集

- 规则：索引、匹配类型/表达式、目标策略胶囊。
- 规则集：名称、规则数、格式、来源类型、更新时间、远端更新入口。

## 5. 工具

采用 iOS 设置式分组列表：脚本、日志、应用管理、网络匹配、共享网络、绕过规则、订阅管理、CNIP、更新 WebUI、更新核心。所有可见入口必须绑定真实行为；未实现功能不得做假按钮。

## 6. 设置与主题引擎

- 核心：Mihomo / Sing-Box。
- 模式：TPROXY / TUN / REDIRECT。
- IPv6：启用 / 绕过 / 系统禁用。
- 主题设置：
  - 风格：Miuix / Material。
  - 模式：系统 / 浅色 / 深色。
  - OLED 纯黑。
  - Monet 动态取色。
  - Palette：Tonal Spot / Neutral / Vibrant / Expressive / Rainbow / Fruit Salad / Monochrome / Fidelity。
  - Material 3 2021 / Material 3 Expressive 2025。
  - 强调色预设。
  - 全局模糊、顶栏模糊算法。
  - 悬浮底栏、液态玻璃。
  - Predictive Back。
  - UI 缩放 80%–120%。

## 7. Bottom Sheets

- Core Selector：架构、版本、commit、构建时间、本地状态；底部 `取消 / 确定`。
- Dashboard Picker：本地面板、Zashboard、MetaCubeXD、Sing-Box Dashboard；显示 URL 与选中勾。

## 8. 工程原则

- 状态均来自真实后端；无法获取的数据展示 `—` 或明确说明，不伪造。
- 任何按钮必须有可执行路径、明确 loading、成功或失败反馈。
- 高频实时指标以低成本轮询/流式更新；网络请求设超时并缓存，避免阻塞主线程。
- Compose 组件拆小、状态上提、Lazy 列表稳定 key、避免深层 Surface 嵌套造成 OEM 白块。
- Android 触控目标至少 48dp。
