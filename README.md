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
└── hetu-root.sh
```

其中 `hetu-root.sh` 是河图自己的 Root 网络控制脚本；运行目录和运行文件均不再使用旧的 `proxy-root.sh` 命名。

## 配置与 DNS 过滤

- 支持保存多份 YAML/YML 配置并随时切换；同名导入自动保留为副本，不覆盖原配置。
- DNS 过滤采用后缀匹配，个人白名单优先；Root 代理运行时由 Mihomo 本地 rule-provider 接管。
- Root 代理关闭时可启用河图本地 DNS VPN 继续过滤，不依赖独立模块。
- 默认均衡档使用 HaGeZi Normal + 中国本地规则；规则源支持镜像回退和部分成功提交，一个来源超时不会让整批更新作废。

## 规则资产

规则源与内置规则已迁入 APK 的 `assets/`，构建不再从独立模块目录复制文件。

## 构建

```sh
cd android-app
gradle :app:assembleDebug --stacktrace
```

JNI 桥接与 Android 源码命名空间均使用 `io.github.xgl34222220.hetu`。原生库名为 `libhetu_core.so`。
