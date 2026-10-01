# 河图图标 V1

使用用户批准的冰蓝玻璃环与双节点图案。应用名称、包名、签名、Launcher 组件名称及启用状态均保持原值；两个既有 Launcher 别名都引用新版图标，旧别名启用的设备也能显示新图案。

色彩图保留已批准的主体，只把原图圆角外的白色补为连续蓝底，避免系统圆形或圆角方形蒙版出现白边。Adaptive 前景使用这份完整、干净的色彩图，蓝色背景层补足外侧动画空间。生成式透明抠图产生噪点的中间版本未采用。主题单色版本用原环和双节点的简化矢量轮廓，交由系统着色。

- `drawable/ic_hetu_official.webp`：设置与关于中的统一图标
- `mipmap-*/ic_launcher.png`：48/72/96/144/192 px 普通资源
- `mipmap-anydpi-v26/ic_launcher.xml`：前景与蓝底的 adaptive 资源
- `mipmap-anydpi-v33/ic_launcher.xml`：增加系统主题单色层
- `drawable/hetu_launcher_monochrome.xml`：保持两处节点和环内开口的单色轮廓

图稿来源校验：

- 用户批准的 `河图-图标概念V1.png` SHA-256：`31573825cac7536b442c67e7a8110fd5584715a347d9cc8e9ef50cc66b29d04c`
- 蓝底延展稿 SHA-256：`6e39ee4e2cc7cf39dba94115275dd2c30d1caeadd63f67c80b6594b147dd9740`

蓝底延展使用内置图像编辑，最终提示如下。多密度导出只进行常规缩放，矢量主题层直接在 Android 资源中绘制。

> Extend ONLY its existing cobalt-blue background into the white corner pixels so the result is a completely square, full-bleed blue background with no rounded outer tile edge and no white corners. Keep the original central ice-blue twisted glass ring and its two round glass nodes completely unchanged: identical positions, size, ring silhouette, highlights, translucent refraction, perspective and color. Do not enlarge, crop, shrink, reinterpret or redesign the object. Continue the nearby blue/cyan background naturally into each corner. No new frame, no white border, no text, no added symbols.

`LauncherIconTest` 在 API 26 和 35 验证资源解析、已有别名、单色层、32/48/72/192 px 安全区域，并导出圆形、圆角方形和主题图标用于目视检查。最终运行结果以对应 CI 报告为准；静态蒙版预览不代表手机 Launcher 的动画或 GPU 性能验收。
