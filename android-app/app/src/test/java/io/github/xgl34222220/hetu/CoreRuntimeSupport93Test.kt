package io.github.xgl34222220.hetu

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Core download/runtime mapping: every core that can be downloaded can also run; the rest are
 * disabled with a reason. Asset names are the real release names (MetaCubeX/mihomo v1.19.32,
 * lux5am/mihomo-smart Prerelease-Alpha, 2026-10).
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

    @Test fun onlyTheMihomoFamilyRunsAndEveryOtherCoreSaysWhy() {
        ProxyRuntimeProfile.Core.values().forEach { core ->
            val mihomoFamily = core == ProxyRuntimeProfile.Core.MIHOMO || core == ProxyRuntimeProfile.Core.MIHOMO_SMART
            assertEquals(core.id, mihomoFamily, ProxyCoreSupport.runtimeSupported(core))
            // The Root runtime capability model agrees with the download policy.
            assertEquals(core.id, mihomoFamily, ProxyRuntimeProfile.capability(core, ProxyRuntimeProfile.Mode.TPROXY).available)
            if (mihomoFamily) assertEquals("", ProxyCoreSupport.unsupportedReason(core))
            else assertTrue(core.id, ProxyCoreSupport.unsupportedReason(core).length > 10)
        }
        assertFalse(ProxyCoreSupport.runtimeSupported(ProxyRuntimeProfile.Core.SING_BOX))
    }

    @Test fun settingsConfigMenuOpensViewAndEditInPlace() {
        assertEquals(listOf("查看", "编辑", "导出", "重命名", "删除"), settingsConfigMenu(ProxyConfigUi("自用_tproxy.yaml", selected = true, bundled = false)))
        assertEquals(listOf("查看", "编辑", "导出"), settingsConfigMenu(ProxyConfigUi("config.yaml", selected = false, bundled = true)))
    }
}
