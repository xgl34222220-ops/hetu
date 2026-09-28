# V18.3 · 动效与交互统一

未在本地编译（环境无 Gradle / Android SDK）。合入后请执行：

```sh
cd android-app
gradle :app:assembleDebug :app:testDebugUnitTest --stacktrace
```

## 新增 `ui/HetuMotion.kt`
- `HetuMotion`：统一时长（90/160/240/320ms）与 M3 缓动（Standard / EmphasizedDecelerate / EmphasizedAccelerate）。
- `HetuHaptics` / `HetuHaptic`：Tap、Tick、ToggleOn/Off、Confirm、Reject、LongPress；API 34/30 以下自动降级；受 `hetu` prefs 中 `hapticFeedback` 控制（默认开）。
- `hetuPressScale`：基于 Interaction 事件的按压回弹，快速轻点也有可见反馈，滑动取消不回弹。
- `hetuPressHighlight`：分组列表行按压高亮，快进慢出。
- `rememberHetuStagger` / `hetuStaggerIn`：仅首次出现时级联入场，纯绘制阶段。
- `hetuAnimateItem`：Lazy 列表插入淡入、重排滑动，移除不淡出（避免切 Tab 时重影）。

## 全局
- 页面切换按 Dock 方向横向滑入，透明度自 0.4 起；外壳底色渐变。遵循系统动画开关。
- Dock：切换 Tick 触感、激活图标轻弹、标签滑出淡入。
- `hetuTap` / `nativePress` / `panelNodePress11` / `HetuFilterTabs` 接入共享按压与触感；节点长按独立触感。

## 首页
- 卡片首次出现级联入场。
- 状态点运行中呼吸涟漪；状态图标环形扫描 + 对勾描绘。
- 启动/停止/重载结束时按结果触感：成功 Confirm、停止 ToggleOff、失败 Reject。

## 面板
- Tab 触感；内容按 Tab 条可视顺序方向滑入。
- 订阅 / 连接 / 规则集列表插入与重排动画（连接 3 秒刷新不再跳动）。
- 策略组、节点卡片选中态颜色渐变、按压高亮；选中勾弹出。
- 修复：节点卡片 `weight` 被包在 `AnimatedVisibility` 内部而失效，现在外层先占位，入场期间不再抖动。
- 延迟胶囊：等级颜色渐变、数值滚动切换、测速中呼吸（文案改为「测速中」）。
- 测速 / 收起悬浮按钮从右下角展开。

## 工具 / 设置
- 列表行改为按压高亮 + Tap 触感，开关行 ToggleOn/Off 触感。
- 首次出现级联入场。
- 设置 → 界面 新增「触感反馈」开关。

## 需关注的测试
- `HomeUi8ExperienceTest.realPressScalesToPoint97ThenSpringsBack`：按住仍停在 0.97，松手约 0.3s 回 1，预期通过。
- Dock 标签仍保持单一 `dock-active-label` 节点。
