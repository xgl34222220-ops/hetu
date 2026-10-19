package io.github.xgl34222220.hetu

import io.github.xgl34222220.hetu.tools.ToolsCore
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 2026-10-18「其它核心根本启动不了」：内核在国内直连 GitHub 常常下载不下来，装不上就选不了。下载依次尝试用户镜像、
 * 正在运行的代理、直连，GitHub 给出 SHA-256 时最后再试公共镜像（下载后都按 SHA-256 校验）。所选内核未安装时启动立即
 * 说明去哪里下载（不经 Root、不附诊断）。
 */
class CoreUx510Test {
    private val asset = "https://github.com/SagerNet/sing-box/releases/download/v1.14.3/sing-box-1.14.3-android-arm64.tar.gz"

    @Test fun theRunningProxyIsTriedBeforeDirectAndTheMirrorOnlyWithAChecksum() {
        assertEquals(
            listOf(asset to true, asset to false, ProxyCoreDownloadManager.PUBLIC_MIRROR + asset to false),
            ProxyCoreDownloadManager.downloadPlan(asset, null, localProxy = true, digestKnown = true),
        )
        assertEquals(listOf(asset to false), ProxyCoreDownloadManager.downloadPlan(asset, null, localProxy = false, digestKnown = false))
    }

    @Test fun theUsersMirrorComesFirstAndNoLongerReplacesTheOthers() {
        val mine = "https://mirror.example.test/$asset"
        assertEquals(
            listOf(mine to false, asset to true, asset to false),
            ProxyCoreDownloadManager.downloadPlan(asset, mine, localProxy = true, digestKnown = false),
        )
    }

    @Test fun aNonGithubAssetHasNoPublicMirror() {
        val other = "https://dl.example.test/core.gz"
        assertEquals(listOf(other to false), ProxyCoreDownloadManager.downloadPlan(other, null, localProxy = false, digestKnown = true))
    }

    @Test fun aMissingCoreIsSaidAtOnceWithWhereToGetIt() {
        val problem = ProxyStartProblem.CoreMissing("Sing-Box")
        assertEquals("a configuration problem: no settle wait, no Root diagnostics", HxStartFailure.Config, hxStartFailure(problem))
        assertTrue(problem.message!!.contains("工具 → 核心管理"))
        assertEquals(HxStartFailure.Config, hxStartFailure(IllegalStateException("wrapped", problem)))
    }

    @Test fun aCoreCardSaysWhyThereIsNothingToDownload() {
        val noBuild = ToolsCore("sing-box", "Sing-Box", "未安装", canDownload = false, notice = "当前设备架构 armv7 没找到可用 Android 构建")
        assertEquals("当前设备架构 armv7 没找到可用 Android 构建", noBuild.notice)
        assertEquals("当前 未安装", noBuild.versionLine)
    }
}
