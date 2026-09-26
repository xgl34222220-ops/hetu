# 河图 · Liquid Glass Design System

> 本文档同时承担两种职责：
> 1. **设计规范（Design Spec）**：约束河图长期视觉语言、材质、圆角、排版、动效与页面层级。
> 2. **Compose 实施清单（Implementation Checklist）**：直接指导 Android Jetpack Compose 重构，防止设计稿与代码落地分离。
>
> 设计原点：**当前悬浮液态底栏**。全 App 所有卡片、胶囊、输入槽、节点高亮、弹窗、设置组与交互动效，必须与底栏属于同一套材质和物理系统。

---

## 1. 核心方向

河图整体统一为：

**iOS / VisionOS 极简液态浮岛（Liquid Glassmorphism）**

关键词：

- 冷白空气感
- 低饱和环境光
- 半透明浮岛
- 微高光切面
- 大圆角
- 轻阴影
- 液态弹性
- 克制配色
- 数值稳定
- 统一物理反馈

目标不是“给 Android 页面加几个玻璃卡片”，而是让所有 UI 像从**同一块液态玻璃材料**里生长出来。

---

## 2. 不可违反的原则

1. **禁止 Material 3 默认 tonalElevation 污染玻璃色。**
2. **禁止大面积实心纯白卡片。**
3. **禁止粗描边、硬边框、Bootstrap 式标签。**
4. **禁止同一页面混用多套圆角语言。**
5. **禁止普通直角 / 深黑输入框。**
6. **禁止巨大状态方块占据首页首屏。**
7. **禁止节点选中态只靠文字变色或小圆点。**
8. **禁止悬浮底栏遮住最后一项内容。**
9. **禁止为了动画而牺牲触控面积、可读性和无障碍。**
10. **玻璃效果不可导致白块、闪烁、拖影或 OEM RuntimeShader 异常。**
11. **功能逻辑、网络稳定性优先级高于视觉特效。**
12. **不允许视觉重构改坏 Root / Mihomo / TPROXY / eBPF 等运行链路。**

---

# Part A · Design Spec

## 3. 页面背景

### 3.1 浅色模式

基础背景：

```
#F5F7FB
```

禁止使用纯白 `#FFFFFF` 作为整页背景。

### 3.2 深色模式

建议基础范围：

```
#0E1014 ~ #11131A
```

深色模式不应退化成“纯黑 + 灰卡片”。

---

## 4. Mesh Gradient 环境弥散光

玻璃必须有可折射的底层光环境。

推荐浅色光斑：

- 左上：淡青 `rgba(130, 220, 255, 0.16)`
- 右上：浅紫 `rgba(180, 160, 255, 0.14)`
- 左下：冷蓝 `rgba(160, 210, 255, 0.08)`

要求：

- 极低饱和
- 大范围羽化
- 不形成明显彩色壁纸
- 静态优先，避免持续 GPU 高负载动画
- 深色模式透明度进一步降低

---

## 5. 统一玻璃材质

### 5.1 浅色

```
fill: rgba(255, 255, 255, 0.65)
stroke: rgba(255, 255, 255, 0.60)
blur: 20dp 左右
```

阴影建议：

```
0 10dp 30dp rgba(31, 38, 135, 0.10)
0 2dp 8dp rgba(0, 0, 0, 0.04)
```

### 5.2 深色

```
fill: rgba(30, 30, 35, 0.70)
stroke: rgba(255, 255, 255, 0.10)
```

深色主要依赖：

- 半透明层次
- 顶部微高光
- 低对比阴影
- 环境光折射

---

## 6. 圆角系统

统一标准：

| 层级 | 圆角 |
|---|---:|
| 大型主浮岛 | 26–28dp |
| 普通主卡片 | 24dp |
| Metric Tile | 20dp |
| 输入槽 | 18–22dp |
| 设置分组 | 24–26dp |
| Bottom Sheet / Dialog | 28–32dp |
| 小标签 / 状态标签 | Pill / 999dp |
| 液态分段选择器 | Pill / 999dp |

禁止在同层级随意混用 12dp、16dp、30dp。

---

## 7. 顶部运行状态区

首页原有大面积“运行中 + 巨型图标”结构废弃。

改为 **Status Capsule / 灵动状态胶囊**：

左侧：

- 8–10dp 状态灯
- 运行时翠绿微光呼吸
- 内核名称
- 模式 / 连接状态

右侧：

- 液态连接 / 断开滑块
- 不使用 Material Switch
- 高亮块滑动切换
- 支持 pressed scale + spring release

首页首屏必须明显释放空间。

---

## 8. 操作按钮组

“重载 / 停止 / 重启”改为：

**Segmented Liquid Pill**

要求：

- 一个统一外壳
- 三段触控区域
- 高亮滑块在分段间平滑移动
- 按下时 0.96–0.98 scale
- 松开 spring 回弹
- 危险语义只使用文字 / 轻色底，不做大红实心块
- 每段最小触控高度 48dp

---

## 9. 四格监控 Tile

首页监控项：

- 延迟
- WAN
- 实时速率
- 订阅

默认采用双列浮动微晶 Tile；320dp 窄屏或大字体时改为单列，以完整显示内容为准。

### Tile 规则

- radius: 20dp
- padding: 14–16dp
- 背景使用统一玻璃材质
- 主数值保持稳定基线
- 标题弱化
- 避免多余边框

### 数字

速率、流量、延迟、内存、CPU：

- 使用 Tabular Numbers
- 禁止数字宽度变化导致布局横跳

### 延迟药丸

低延迟：

```
background: rgba(16, 185, 129, 0.12~0.15)
foreground: #059669
```

中延迟：低饱和琥珀色。

高延迟 / 超时：低饱和红色。

---

## 10. 节点与策略组

### 10.1 选中态

禁止只使用：

- 小圆点
- 勾选图标
- 纯文字变色

选中节点必须拥有：

**Liquid Selection Capsule**

视觉来源与底栏活动指示块一致。

### 10.2 切换动效

选中背景在节点 / 策略组之间“游动”：

- Spring Physics
- 轻果冻阻尼
- 不拖沓
- 不超过约 300ms 的主观完成感

### 10.3 列表卡片

- 外层是玻璃分组
- 内项依靠留白与极弱分隔
- 不使用 Excel 样式网格
- 长节点名不可被不合理截断
- 延迟按钮保持 48dp 触控目标

---

## 11. 设置页

整体采用：

**Grouped Inset Floating Cards**

要求：

- 屏幕两侧 16dp
- 各设置组悬浮
- 分组间留足垂直呼吸空间
- 行高统一
- 图标基线统一
- supporting text 不能挤压标题
- trailing control 最大宽度受控

---

## 12. 输入框

所有 API / 配置 / 参数输入框统一为：

**Glass Input Well**

特征：

- 18–22dp 圆角
- 半透明磨砂底
- 极轻内阴影
- focus 时高光增强
- 禁止黑色直角矩形
- 文本与光标对比度符合可读性要求
- 错误态仅使用轻红描边 / 提示，不整块变红

---

## 13. Dialog / Bottom Sheet

要求：

- 28–32dp 顶部圆角
- 同源玻璃材质
- 背景轻微暗化 / 模糊
- 内容间距充足
- 顶部拖拽条克制
- 所有内部输入框继续使用 Glass Input Well
- 所有按钮继续使用 Liquid Pill 体系

---

## 14. 悬浮底栏

当前悬浮液态底栏是整个 Design System 的设计母体。

其它组件必须继承：

- 同类折射
- 同类透明度
- 同类高光
- 同类 spring
- 同类选中态移动
- 同类圆角比例

底栏自身要求：

- 不过扁
- 视觉高度约 68–76dp
- 浮动模式左右 20dp 左右留白
- 与系统导航栏安全区正确叠加
- 图标不变形
- 标签不跳动
- RuntimeShader 不支持时必须稳定 fallback 到 haze / 半透明材质

---

## 15. 列表底部安全区

所有主页面 LazyColumn / LazyGrid 必须：

```
contentPaddingBottom =
    实际底栏总高度
    + 底栏与内容间距
    + 20dp
```

原则：

> 列表滑到底部时，最后一项必须完整停在悬浮底栏上方。

底栏已经包含 navigationBars inset 时，不可重复叠加系统 inset。

---

## 16. Overscroll

要求：

- 使用柔和弹性回弹
- 禁止老式 Android 蓝色 Edge Glow
- 动效不可影响触控稳定性
- Reduce Motion / 系统动画关闭时降级

---

## 17. 动效统一

### Press

```
scale: 0.96 ~ 0.98
duration: 80–120ms
release: spring
```

### 节点 / Segment 切换

- 高亮块平移
- 微拉伸
- spring settle

### 页面进入

- alpha + 8–12dp vertical translation
- 不做大幅位移

### 状态灯

- 只在运行 / 连接过程使用低频呼吸
- 不持续高频动画

### 数值

- 更新不得导致 Tile 尺寸变化
- 不滥用数字滚动动画

---

## 18. 排版

建议继续沿用河图现有紧凑排版语言，但统一：

- 页面标题：20–22sp
- 卡片标题：15–17sp
- 正文：13–14.5sp
- 辅助信息：11.5–13sp
- 按钮：12–14sp
- 数字：Tabular Numbers

重要：

- 标题与说明必须分层
- 不将一堆状态塞进胶囊
- 不使用过多高饱和 badge
- 320dp 宽设备和 1.5× fontScale 必须可用

---

# Part B · Compose Implementation Checklist

## 19. Design Token 重构

### P0

建立 / 收敛：

```
HetuGlassColors
HetuGlassRadius
HetuGlassElevation
HetuGlassStroke
HetuMotionSpec
HetuBottomBarMetrics
```

现有 `LocalHetuTokens` 可以继续作为入口，但玻璃相关参数不能散落到各页面。

验收：

- 同类卡片不再自行硬编码不同白色 / 灰色
- 同层级圆角统一
- Light / Dark 一处切换

---

## 20. 基础 Compose 组件

### 必做组件

```
LiquidScaffoldBackground
GlassIslandCard
GlassMetricTile
GlassPill
SegmentedLiquidPill
LiquidStatusCapsule
LiquidSelectionIndicator
GlassInputWell
GroupedInsetSection
LiquidBottomSheet
HetuGlassDock
```

每个组件都必须支持：

- Light / Dark
- Reduce Motion
- 大字体
- 48dp 触控目标
- 无 RuntimeShader fallback

---

## 21. 首页重构

### P0

- [ ] Mesh Gradient 背景
- [ ] 顶部 Status Capsule
- [ ] 移除旧巨大运行状态块
- [ ] Segmented Liquid Pill 操作器
- [ ] 2×2 Metric Tiles
- [ ] Tabular Numbers
- [ ] WAN / 订阅长文本安全截断
- [ ] 状态灯轻呼吸
- [ ] 首页最后一项 bottom safe area

验收：

- 360dp / 412dp 宽度通过
- 1.0× / 1.5× fontScale 通过
- Light / Dark 通过
- 不产生白块
- 页面重新进入不闪默认空状态

---

## 22. 节点 / 面板重构

### P0

- [ ] 策略组选中态改 Liquid Selection Capsule
- [ ] 节点组选中态改 Liquid Selection Capsule
- [ ] 切换时 indicator spring move
- [ ] 长节点名保持可读
- [ ] 延迟状态 pill 统一
- [ ] Delay hit target ≥ 48dp
- [ ] 去除多余彩色 badge
- [ ] 面板卡片材质与底栏一致

---

## 23. 设置页重构

### P1

- [ ] 所有设置页改 Grouped Inset
- [ ] 两侧统一 16dp
- [ ] 图标列宽统一
- [ ] supporting text 可换行
- [ ] trailing 控件对齐
- [ ] 开关重做液态风格
- [ ] 页面底部正确避让 dock

---

## 24. API / 网络 / 配置弹窗

### P1

- [ ] 黑色直角输入框全部移除
- [ ] GlassInputWell
- [ ] 焦点态高光
- [ ] 错误态轻提示
- [ ] Bottom Sheet 统一 28–32dp
- [ ] Sheet 与页面背景材质连续
- [ ] 键盘弹出时不遮保存按钮

---

## 25. 底栏统一

### P0

- [ ] 保持当前液态底栏为视觉母体
- [ ] 浮动高度统一
- [ ] active lens 与节点选中态共享 Motion Spec
- [ ] 实际测量 dock 高度注入 `LocalHetuDockHeight`
- [ ] 所有列表通过 `hetuContentBottomPadding()` 避让
- [ ] 不重复计算 navigationBars inset
- [ ] RuntimeShader / Haze / Plain 三档 fallback 一致

---

## 26. 动效与物理反馈

### P1

- [ ] Press scale
- [ ] Spring release
- [ ] Segmented indicator move
- [ ] Node indicator move
- [ ] 页面轻上浮进入
- [ ] Reduce Motion
- [ ] 禁止页面切换闪白
- [ ] 禁止动画导致文本截断 / hitbox 缩小

---

## 27. UI 回归测试

必须保留或新增 Robolectric / Compose Render Regression：

### 尺寸

- 320dp
- 360dp
- 412dp
- 480dp

### 字体

- 1.0×
- 1.3×
- 1.5×

### 模式

- Light
- Dark
- Motion On
- Motion Off
- RuntimeShader fallback

### 必测

- 首页首屏
- 首页滚到底
- 面板节点
- 长节点名
- 设置页
- API 输入弹窗
- Bottom Sheet
- 悬浮底栏
- 最后一项避让

---

## 28. 功能回归红线

UI 重构不得影响：

- Root 启动速度
- TPROXY
- REDIRECT
- Enhance
- TUN
- eBPF
- IPv6 防泄漏
- DNS 防泄漏
- WebRTC 防泄漏
- 微信长连接
- 网络切换原地修复
- Mihomo keepalive
- 多配置
- 去广告规则热更新
- App 名称 / 图标连接归类

所有正式 UI 合并必须继续通过：

- network-kernel
- message filtering / continuity
- Root Mihomo
- Compose UI regressions
- APK identity / payload

---

## 29. 验收标准

重构完成后必须满足：

### 视觉

- [ ] 页面、卡片、Bottom Sheet、输入槽、节点选中态、底栏明显属于同一材质
- [ ] 不再出现实心白块
- [ ] 不再出现不一致的圆角
- [ ] 不再出现 Android 默认深黑输入框
- [ ] 不再出现大面积高饱和 badge
- [ ] 首页首屏明显比旧版更轻、更紧凑

### 布局

- [ ] 320dp 不横向溢出
- [ ] 1.5× 字体不截断关键操作
- [ ] 最后一项永远可滚到底栏上方
- [ ] Dock 不遮内容

### 动效

- [ ] 按压有轻反馈
- [ ] 节点 / Segment 高亮块平滑移动
- [ ] 动画关闭后仍完整可用
- [ ] 不闪白、不跳布局

### 性能

- [ ] UI 动效不增加 Root 查询频率
- [ ] 不增加代理启动链路工作量
- [ ] 首页滚动稳定
- [ ] 页面切换不重复加载重资源
- [ ] RuntimeShader 不稳定设备自动降级

---

## test.131 自适应布局约束

- 策略与节点卡按内容测量高度，不再采用 68/80dp 与 66/78dp 固定高度。
- 旧的名称裁剪偏好自动按完整换行处理，保留用户主动选择的滚动方式。
- 手动列数是上限，仍需服从可用宽度与系统字体缩放。
- 选中背景以实际卡片边界计算位置和尺寸，不按固定行高推算。
- 延迟状态文字不经过尺寸裁切动画；数字保留字体度量空间。
- 1.0、1.5、2.0 倍字体均检查实际文字布局与父级边界。
- 重载、停止、重启是瞬时操作，不显示持久的分段选中态。

## 30. 实施顺序

### Phase 1 · 视觉地基

1. Token
2. Mesh Gradient
3. GlassIslandCard
4. LiquidStatusCapsule
5. SegmentedLiquidPill
6. MetricTile
7. Dock safe-area

### Phase 2 · 核心页面

1. 首页
2. 节点 / 策略组
3. 面板
4. 设置

### Phase 3 · 输入与浮层

1. API 配置
2. 网络设置
3. Bottom Sheet
4. Dialog
5. 输入框

### Phase 4 · 物理统一

1. Press
2. Spring
3. Selection indicator
4. Overscroll
5. Reduce Motion

### Phase 5 · 回归

1. UI Render
2. 大字体
3. 小屏幕
4. 深色
5. RuntimeShader fallback
6. 网络 / Root 全回归

---

# 最终产品定义

河图不是“普通代理工具 + 玻璃底栏”。

河图的目标是：

> **一套悬浮在空气中的液态网络控制台。**

所有组件必须共享同一种视觉材料、同一种圆角逻辑、同一种空间层级与同一种物理反馈。
