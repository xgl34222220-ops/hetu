package io.github.xgl34222220.hetu

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Core download/runtime mapping: every downloadable core runs in Root TPROXY / Redirect, and the
 * Clash-controller features degrade per core with an explicit "此核心不支持" state. Asset names are
 * the real release names (MetaCubeX/mihomo v1.19.32, lux5am/mihomo-smart Prerelease-Alpha,
 * SagerNet/sing-box v1.14.3, reF1nd/sing-box-releases, XTLS/Xray-core v26.10.10,
 * v2fly/v2ray-core v5.54.2, apernet/hysteria app/v2.13.0; 2026-10).
 */
class CoreRuntimeSupport93Test {
    private val mihomo = listOf(
        "mihomo-android-386-v1.19.32.gz", "mihomo-android-amd64-v1.19.32.gz",
        "mihomo-android-arm64-v8-v1.19.32.gz", "mihomo-android-armv7-v1.19.32.gz",
        "mihomo-linux-arm64-v1.19.32.gz", "mihomo-android-arm64-v8-v1.19.32.gz.sha256",
    )
    private val smart = listOf(
        "mihomo-android-386-alpha-smart-8d4c8c7.gz", "mihomo-android-amd64-alpha-smart-8d4c8c7.gz",
        "mihomo-android-arm64-v8-alpha-smart-8d4c8c7.gz", "mihomo-android-armv7-alpha-smart-8d4c8c7.gz",
    )

    private fun pick(core: ProxyRuntimeProfile.Core, names: List<String>, abi: String): String? =
        names.filter { ProxyCoreDownloadManager.matchesAsset(core, it, abi) }
            .minByOrNull { ProxyCoreDownloadManager.assetRank(core, it) }

    @Test fun abiTagFollowsTheFirstSupportedDeviceAbi() {
        assertEquals("arm64", ProxyCoreDownloadManager.abiTag(arrayOf("arm64-v8a", "armeabi-v7a", "armeabi")))
        assertEquals("armv7", ProxyCoreDownloadManager.abiTag(arrayOf("armeabi-v7a", "armeabi")))
        assertEquals("amd64", ProxyCoreDownloadManager.abiTag(arrayOf("x86_64", "x86", "arm64-v8a")))
        assertEquals("386", ProxyCoreDownloadManager.abiTag(arrayOf("x86")))
        assertEquals("unknown", ProxyCoreDownloadManager.abiTag(arrayOf("mips")))
    }

    @Test fun mihomoReleaseHasExactlyOneAndroidBinaryPerAbi() {
        val core = ProxyRuntimeProfile.Core.MIHOMO
        assertEquals("mihomo-android-arm64-v8-v1.19.32.gz", pick(core, mihomo, "arm64"))
        assertEquals("mihomo-android-armv7-v1.19.32.gz", pick(core, mihomo, "armv7"))
        assertEquals("mihomo-android-amd64-v1.19.32.gz", pick(core, mihomo, "amd64"))
        assertEquals("mihomo-android-386-v1.19.32.gz", pick(core, mihomo, "386"))
        assertEquals(null, pick(core, mihomo, "unknown"))
        assertEquals("v1.19.32", ProxyCoreDownloadManager.assetVersion(core, "v1.19.32", "mihomo-android-arm64-v8-v1.19.32.gz"))
    }

    @Test fun compatibleMihomoBuildIsPreferredWhenOffered() {
        val names = listOf("mihomo-android-amd64-v1.19.32.gz", "mihomo-android-amd64-compatible-v1.19.32.gz")
        assertEquals("mihomo-android-amd64-compatible-v1.19.32.gz", pick(ProxyRuntimeProfile.Core.MIHOMO, names, "amd64"))
    }

    @Test fun mihomoSmartMapsEveryAbiAndRecordsItsBuildAsVersion() {
        val core = ProxyRuntimeProfile.Core.MIHOMO_SMART
        assertEquals("mihomo-android-arm64-v8-alpha-smart-8d4c8c7.gz", pick(core, smart, "arm64"))
        assertEquals("mihomo-android-armv7-alpha-smart-8d4c8c7.gz", pick(core, smart, "armv7"))
        assertEquals("mihomo-android-amd64-alpha-smart-8d4c8c7.gz", pick(core, smart, "amd64"))
        assertEquals("mihomo-android-386-alpha-smart-8d4c8c7.gz", pick(core, smart, "386"))
        // Every Smart build is published under the same tag: the build id must be part of the version.
        val first = ProxyCoreDownloadManager.assetVersion(core, "Prerelease-Alpha", "mihomo-android-arm64-v8-alpha-smart-8d4c8c7.gz")
        val next = ProxyCoreDownloadManager.assetVersion(core, "Prerelease-Alpha", "mihomo-android-arm64-v8-alpha-smart-9e0f1a2.gz")
        assertEquals("Prerelease-Alpha · alpha-smart-8d4c8c7", first)
        assertTrue(first != next)
    }

    private val singBox = listOf(
        "sing-box-1.14.3-android-386.tar.gz", "sing-box-1.14.3-android-amd64.tar.gz",
        "sing-box-1.14.3-android-arm.tar.gz", "sing-box-1.14.3-android-arm64.tar.gz",
        "sing-box-1.14.3-linux-arm64.tar.gz", "SFA-1.14.3-universal.apk", "sing-box-1.14.3-android-arm64.tar.gz.sha256",
    )
    private val reF1nd = listOf(
        "sing-box-1.14.3-reF1nd-android-386.tar.gz", "sing-box-1.14.3-reF1nd-android-amd64.tar.gz",
        "sing-box-1.14.3-reF1nd-android-amd64v3.tar.gz", "sing-box-1.14.3-reF1nd-android-arm.tar.gz",
        "sing-box-1.14.3-reF1nd-android-arm64.tar.gz",
    )
    private val xray = listOf(
        "Xray-android-amd64.zip", "Xray-android-arm64-v8a.zip", "Xray-android-arm64-v8a.zip.dgst",
        "Xray-linux-32.zip", "Xray-linux-64.zip", "Xray-linux-arm32-v7a.zip", "Xray-linux-arm64-v8a.zip", "Xray-windows-64.zip",
    )
    private val v2ray = listOf(
        "v2ray-android-arm64-v8a.zip", "v2ray-linux-32.zip", "v2ray-linux-64.zip", "v2ray-linux-arm32-v7a.zip",
        "v2ray-linux-arm64-v8a.zip", "v2ray-linux-arm32-v6.zip",
    )
    private val hysteria = listOf(
        "hysteria-android-386", "hysteria-android-amd64", "hysteria-android-arm64", "hysteria-android-armv7",
        "hysteria-linux-arm64", "hysteria-android-arm64.sha256",
    )

    @Test fun singBoxAndReF1ndMapEveryAbiIncludingArmNamedArm() {
        val core = ProxyRuntimeProfile.Core.SING_BOX
        assertEquals("sing-box-1.14.3-android-arm64.tar.gz", pick(core, singBox, "arm64"))
        assertEquals("sing-box-1.14.3-android-arm.tar.gz", pick(core, singBox, "armv7"))
        assertEquals("sing-box-1.14.3-android-amd64.tar.gz", pick(core, singBox, "amd64"))
        assertEquals("sing-box-1.14.3-android-386.tar.gz", pick(core, singBox, "386"))
        val ref = ProxyRuntimeProfile.Core.SING_BOX_REF1ND
        assertEquals("sing-box-1.14.3-reF1nd-android-arm64.tar.gz", pick(ref, reF1nd, "arm64"))
        assertEquals("sing-box-1.14.3-reF1nd-android-arm.tar.gz", pick(ref, reF1nd, "armv7"))
        // amd64v3 needs x86-64-v3 CPUs: the baseline build is the one that always runs.
        assertEquals("sing-box-1.14.3-reF1nd-android-amd64.tar.gz", pick(ref, reF1nd, "amd64"))
        assertEquals("v1.14.3", ProxyCoreDownloadManager.assetVersion(core, "v1.14.3", "sing-box-1.14.3-android-arm64.tar.gz"))
    }

    @Test fun xrayAndV2flyPreferAndroidBuildsAndFallBackToStaticLinuxBuilds() {
        val x = ProxyRuntimeProfile.Core.XRAY
        assertEquals("Xray-android-arm64-v8a.zip", pick(x, xray, "arm64"))
        assertEquals("Xray-android-amd64.zip", pick(x, xray, "amd64"))
        assertEquals("Xray-linux-arm32-v7a.zip", pick(x, xray, "armv7"))
        assertEquals("Xray-linux-32.zip", pick(x, xray, "386"))
        val v = ProxyRuntimeProfile.Core.V2FLY
        assertEquals("v2ray-android-arm64-v8a.zip", pick(v, v2ray, "arm64"))
        assertEquals("v2ray-linux-arm32-v7a.zip", pick(v, v2ray, "armv7"))
        assertEquals("v2ray-linux-64.zip", pick(v, v2ray, "amd64"))
        assertEquals("v2ray-linux-32.zip", pick(v, v2ray, "386"))
        assertEquals(listOf("geoip.dat", "geosite.dat"), ProxyCoreDownloadManager.GEO_ASSETS)
    }

    @Test fun hysteriaMapsRawAndroidBinariesAndStripsTheAppTagPrefix() {
        val core = ProxyRuntimeProfile.Core.HYSTERIA
        assertEquals("hysteria-android-arm64", pick(core, hysteria, "arm64"))
        assertEquals("hysteria-android-armv7", pick(core, hysteria, "armv7"))
        assertEquals("hysteria-android-amd64", pick(core, hysteria, "amd64"))
        assertEquals("hysteria-android-386", pick(core, hysteria, "386"))
        assertEquals("v2.13.0", ProxyCoreDownloadManager.assetVersion(core, "app/v2.13.0", "hysteria-android-arm64"))
    }

    @Test fun everyCoreRunsInRootTproxyAndRedirectWithExplicitFeatureLimits() {
        ProxyRuntimeProfile.Core.values().forEach { core ->
            val mihomoFamily = core == ProxyRuntimeProfile.Core.MIHOMO || core == ProxyRuntimeProfile.Core.MIHOMO_SMART
            assertTrue(core.id, ProxyCoreSupport.runtimeSupported(core))
            assertTrue(core.id, ProxyRuntimeProfile.capability(core, ProxyRuntimeProfile.Mode.TPROXY).available)
            assertTrue(core.id, ProxyRuntimeProfile.capability(core, ProxyRuntimeProfile.Mode.REDIRECT).available)
            assertEquals(core.id, mihomoFamily, ProxyRuntimeProfile.capability(core, ProxyRuntimeProfile.Mode.TUN).available)
            assertEquals("", ProxyCoreSupport.unsupportedReason(core))
            assertEquals(core.id, mihomoFamily, ProxyCoreSupport.hotReload(core))
            assertEquals(core.id, mihomoFamily || core.id.startsWith("sing-box"), ProxyCoreSupport.panel(core))
            assertEquals(core.id, mihomoFamily, ProxyCoreSupport.featureSummary(core).isEmpty())
        }
        val hy = ProxyRuntimeProfile.Core.HYSTERIA
        assertTrue(ProxyCoreSupport.singleServer(hy))
        assertFalse(ProxyCoreSupport.ruleRouting(hy))
        assertTrue(ProxyCoreSupport.featureSummary(hy).contains("单服务器"))
        assertEquals("此核心不支持热重载（Xray）", ProxyCoreSupport.unsupported(ProxyRuntimeProfile.Core.XRAY, "热重载"))
        assertTrue(ProxyRuntimeProfile.capability(ProxyRuntimeProfile.Core.XRAY, ProxyRuntimeProfile.Mode.TUN).reason.contains("TPROXY"))
    }

    private fun gate(core: ProxyRuntimeProfile.Core, customApi: Boolean = false) = io.github.xgl34222220.hetu.panel.PanelCoreGate.of(
        core.label, ProxyCoreSupport.clashApi(core), ProxyCoreSupport.providers(core), ProxyCoreSupport.singleServer(core), customApi)

    @Test fun panelTabsDegradePerCoreWithTheUnsupportedState() {
        val tabs = io.github.xgl34222220.hetu.panel.PanelTab.entries
        listOf(ProxyRuntimeProfile.Core.MIHOMO, ProxyRuntimeProfile.Core.MIHOMO_SMART).forEach { core ->
            assertTrue(tabs.all { gate(core).supports(it) }); assertTrue(gate(core).latency)
            assertTrue(ProxyCoreSupport.providers(core)); assertTrue(ProxyCoreSupport.trafficMode(core))
        }
        // sing-box clash_api (1.14.3): proxies, group delay, connections and rules work; providers do not.
        listOf(ProxyRuntimeProfile.Core.SING_BOX, ProxyRuntimeProfile.Core.SING_BOX_REF1ND).forEach { core ->
            val g = gate(core)
            assertEquals(setOf(io.github.xgl34222220.hetu.panel.PanelTab.Subscriptions, io.github.xgl34222220.hetu.panel.PanelTab.RuleSets), g.features.keys)
            assertTrue(g.latency)
            assertEquals(io.github.xgl34222220.hetu.panel.PanelTab.Groups, g.fallbackFor(io.github.xgl34222220.hetu.panel.PanelTab.Subscriptions))
            assertEquals(io.github.xgl34222220.hetu.panel.PanelTab.Rules, g.fallbackFor(io.github.xgl34222220.hetu.panel.PanelTab.RuleSets))
            assertFalse(ProxyCoreSupport.trafficMode(core))
        }
        // No Clash controller: every controller tab is gated, 日志 still works, no latency.
        listOf(ProxyRuntimeProfile.Core.XRAY, ProxyRuntimeProfile.Core.V2FLY, ProxyRuntimeProfile.Core.HYSTERIA).forEach { core ->
            val g = gate(core)
            assertEquals(tabs.filter { it != io.github.xgl34222220.hetu.panel.PanelTab.Logs }.toSet(), g.features.keys)
            assertTrue(g.supports(io.github.xgl34222220.hetu.panel.PanelTab.Logs))
            assertFalse(g.latency)
            assertEquals(core.label, g.core)
            assertTrue(g.details.values.all { it.startsWith(core.label) })
            // An external Clash API is not the local core: never gated.
            assertEquals(io.github.xgl34222220.hetu.panel.PanelCoreGate.Full, gate(core, customApi = true))
        }
        assertTrue(gate(ProxyRuntimeProfile.Core.HYSTERIA).details.values.first().contains("单服务器"))
        assertEquals("当前核心（Xray）不支持此功能", io.github.xgl34222220.hetu.home.homeCoreUnsupportedTitle("Xray"))
    }

    @Test fun adblockStateFollowsTheCoreThatAppliesTheRules() {
        val base = io.github.xgl34222220.hetu.tools.ToolsAdblockState(enabled = true, proxyRunning = true, ruleMode = true, modeKnown = true)
        assertFalse(base.coreManaged)
        val singBox = base.copy(coreLabel = "Sing-Box", coreLiveVerify = false)
        assertTrue(singBox.coreManaged)
        assertFalse(singBox.copy(enabled = false).coreManaged)
        assertFalse(singBox.copy(proxyRunning = false).coreManaged)
        assertFalse(base.copy(coreLabel = "Hysteria", coreRoutesAds = false, coreLiveVerify = false).coreManaged)
    }

    @Test fun settingsConfigMenuOpensViewAndEditInPlace() {
        assertEquals(listOf("查看", "编辑", "导出", "重命名", "删除"), settingsConfigMenu(ProxyConfigUi("自用_tproxy.yaml", selected = true, bundled = false)))
        assertEquals(listOf("查看", "编辑", "导出"), settingsConfigMenu(ProxyConfigUi("config.yaml", selected = false, bundled = true)))
    }
}
