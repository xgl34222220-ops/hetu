package io.github.xgl34222220.hetu.tools

/** Sample data for the previews of pages 26–49; mirrors the 示例数据 of the concept pages. */
internal object ToolsFeatureSamples {
    val apps = listOf(
        ToolsApp("com.tencent.mm", "微信", "com.tencent.mm", 10142),
        ToolsApp("com.eg.android.AlipayGphone", "支付宝", "com.eg.android.AlipayGphone", 10158),
        ToolsApp("com.android.chrome", "Chrome", "com.android.chrome", 10112),
        ToolsApp("org.telegram.messenger", "Telegram", "org.telegram.messenger", 10201),
        ToolsApp("com.google.android.youtube", "YouTube", "com.google.android.youtube", 10135),
        ToolsApp("tv.danmaku.bili", "哔哩哔哩", "tv.danmaku.bili", 10206),
        ToolsApp("com.example.maps", "地图", "com.example.maps", 10220),
    )

    val appsBlacklist = ToolsAppsState(
        ToolsLoad.Ready, apps, ToolsAppScope.Blacklist,
        blacklist = setOf("com.tencent.mm", "com.eg.android.AlipayGphone"),
        whitelist = setOf("org.telegram.messenger", "com.google.android.youtube"),
    )
    val appsWhitelist = appsBlacklist.copy(scope = ToolsAppScope.Whitelist)
    val appsCore = appsBlacklist.copy(scope = ToolsAppScope.Core)
    val appsSearch = appsBlacklist.copy(searching = true, query = "微信")

    val cores = listOf(
        ToolsCore("mihomo", "Mihomo", installed = "内置版本", latest = "示例版本", updateAvailable = true, bundled = true),
        ToolsCore("xray", "Xray", installed = "未安装", runnable = false),
        ToolsCore("sing-box", "sing-box", installed = "未安装", runnable = false),
    )
    val coresIdle = ToolsCoresState(ToolsLoad.Ready, "Mihomo（示例）", cores)
    val coresDownloading = coresIdle.copy(busyId = "mihomo", progress = "正在下载 · 6.4 MB / 12.8 MB")

    val bypassRules = ToolsBypassRules(listOf("10.0.0.0/8", "fd00::/8"), listOf("dummy0"))
    val bypass = ToolsBypassState(ToolsLoad.Ready, bypassRules, bypassRules)
    val bypassDirty = bypass.copy(draft = bypassRules.copy(cidrs = bypassRules.cidrs + "192.168.0.0/16"))

    val shareSettings = ToolsShareSettings(enabled = true, directInterfaces = setOf("rndis0"), macs = listOf("02:00:00:00:00:02"))
    val share = ToolsShareState(
        ToolsLoad.Ready, shareSettings, shareSettings,
        interfaces = listOf(ToolsShareInterface("wlan1", "UP"), ToolsShareInterface("rndis0", "UP")),
        clients = listOf(
            ToolsShareClient("192.168.43.101", "02:00:00:00:00:01", "wlan1", "REACHABLE"),
            ToolsShareClient("192.168.43.102", "02:00:00:00:00:02", "wlan1", "STALE"),
        ),
    )

    val cnIp = ToolsCnIpState(ToolsLoad.Ready, enabled = true)

    val diag = ToolsDiagState(ToolsPreflight.Passed())

    val startupConfig = """
        port: 7890
        allow-lan: true
        mode: rule
        log-level: info
        ipv6: true
        external-controller: 127.0.0.1:9090
        dns:
          enable: true
        rules:
          - GEOIP,CN,DIRECT
          - MATCH,节点选择
    """.trimIndent()

    val report = """
        --- 版本与最近运行事件 ---
        time=2026-10-01 09:41
        app=示例版本
        expectedCore=mihomo
        proxyBaseMode=tproxy
        proxyRootRuntimeRunning=true

        --- 最近启动耗时（毫秒） ---
        尚无启动记录

        --- 源配置标识 ---
        bytes=2048
        sha256=示例摘要

        --- Root 网络状态 ---
        无记录
    """.trimIndent()

    val adblock = ToolsAdblockState(
        load = ToolsLoad.Ready,
        enabled = true,
        proxyRunning = true,
        ruleCount = 128420,
        hits = 36,
        allow = listOf("example.com", "example.org"),
        block = listOf("ads.example.net"),
        sources = listOf(
            ToolsAdSource("cn", "中文广告规则", 32480, "使用内置快照", enabled = true),
            ToolsAdSource("adguard-dns", "AdGuard DNS", 95940, "更新于 10 分钟前", enabled = true),
            ToolsAdSource("hagezi", "HaGeZi", 58200, "使用内置快照", enabled = false),
        ),
        level = ToolsAdLevel.Balanced,
        recent = listOf("ads.example.com", "tracker.example.org"),
        startupInjected = true,
        controllerLoaded = true,
        standaloneDns = false,
        cnameProtection = true,
    )
}
