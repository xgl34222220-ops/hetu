# 第三方来源与许可

## AdAway 默认域名规则

- 上游：https://github.com/AdAway/adaway.github.io
- 在线订阅：https://adaway.org/hosts.txt
- 本次种子对应官方镜像：https://raw.githubusercontent.com/AdAway/adaway.github.io/master/hosts.txt
- 本地路径：`module/rules/adaway.txt`。
- 许可：Creative Commons Attribution 3.0 (CC BY 3.0)。https://creativecommons.org/licenses/by/3.0/
- 作者归属：Kicelo、Dominik Schuermann，以及上游提交历史中的贡献者。
- 保留上游头部、来源与贡献说明。本地运行时只提取可用于阻断的精确域名，规范化大小写、去重并合并用户的例外规则；不分发 AdAway App 代码。

## AWAvenue 秋风广告规则

- 上游：https://github.com/TG-Twilight/AWAvenue-Ads-Rule
- 许可：GPL-3.0；全文见根目录 `LICENSE`，上游：https://github.com/TG-Twilight/AWAvenue-Ads-Rule/blob/main/LICENSE。
- `module/rules/china.txt` 对应官方 `Filters/AWAvenue-Ads-Rule-hosts-Only.Ads.txt`，作为“秋风纯广告”。
- `module/rules/tracking.txt` 对应官方 `Filters/AWAvenue-Ads-Rule-hosts-No.Unwelcome.txt`，作为“秋风隐私增强”；其内容包含广告与隐私规则，排除该项目的“不受欢迎”类别。
- 原始规则保留上游头部，运行时提取精确域名、去重、应用白名单；hosts 不具有 DNS 过滤器的子域通配语义。

## HaGeZi Multi LIGHT 可选增强

- 上游及作者：HaGeZi / Gerd Z.，https://github.com/hagezi/dns-blocklists 。
- 本地路径：`module/rules/hagezi.txt`，保留原始头部、作者项目与许可链接，未改动上游文本。
- 许可：GPL-3.0；上游全文：https://github.com/hagezi/dns-blocklists/blob/main/LICENSE 。本包 `LICENSE` 同时提供 GPL 第三版全文。
- 官方来源：https://raw.githubusercontent.com/hagezi/dns-blocklists/main/wildcard/light-onlydomains.txt 。
- 本次核验日期：2026-09-12；上游标注 2026.0912.0812.52；35,280 个域名、675,282 字节，确切哈希见 `rules/snapshot.json`。
- 此源默认关闭，只有用户主动开启才参与本地过滤。这里使用精确域名列表；模块和 App 当前不会自动将父域名扩展到所有子域，不等同上游的通配 DNS 引擎覆盖率。
- 同日比较了官方 Normal 域名列表（180,141 条、3,410,151 字节），未随包分发。选择 Light 是为了控制移动设备规则体积和功能影响，不以规则数量作为拦截率指标。

## App 构建依赖与参考

- 当前 App 使用 Java 与 Android SDK 平台 API，通过项目构建脚本编译；未打包 AndroidX、Compose、Kotlin、miuix 或 Gradle Wrapper。这些早期方案参考不应列为当前二进制依赖。
- Android 平台 API / SDK 工具由 Android Open Source Project 与相应 SDK 发行条款提供；开发工具不作为 APK 代码一起分发。https://source.android.com/docs/setup/about/licenses
- Magisk 为外部运行环境，本包不分发其二进制；模块遵循官方模块接口。https://topjohnwu.github.io/Magisk/guides.html

源码包包含第三方种子规则，能够独立重新构建本次交付。规则条数和日期由 `module/rules/snapshot.json` 记录；重新下载上游会产生新的快照。
