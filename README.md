# 河图 Hetu

河图是一个 Root Android 网络管理 App，负责 Mihomo 核心部署、透明代理、规则、订阅、去广告串联和运行状态管理。

## 当前架构

- Android 包名：`io.github.xgl34222220.hetu`
- Root 运行根目录：`/data/adb/hetu`
- 核心、配置、运行状态、规则和日志都由 App 直接部署和维护。
- 不再要求安装独立 Magisk / KernelSU / APatch 模块。
- 开机恢复由 App 的 `BootReceiver` 与 Root 控制层完成。
- Mihomo 配置中的 `proxies`、`proxy-groups` 等标准字段保持原义，不做品牌化改名。

## 运行目录

```text
/data/adb/hetu/
├── bin/
├── run/
│   ├── state/
│   └── ruleset/
└── proxy-root.sh
```

其中 `proxy-root.sh` 是透明代理控制脚本文件名，不再代表运行目录；运行目录本身不再包含旧的 `/proxy` 层级。

## 规则资产

规则源与内置规则已迁入 APK 的 `assets/`，构建不再从独立模块目录复制文件。

## 构建

```sh
cd android-app
gradle :app:assembleDebug --stacktrace
```

JNI 桥接与 Android 源码命名空间均使用 `io.github.xgl34222220.hetu`。原生库名为 `libhetu_core.so`。
