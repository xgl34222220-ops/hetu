package io.github.xgl34222220.hetu

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import io.github.xgl34222220.hetu.ui.translateHetuText
import java.io.File
import java.io.FileNotFoundException
import java.io.IOException
import java.net.ConnectException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import java.util.concurrent.ExecutionException
import javax.net.ssl.SSLHandshakeException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** 2026-10 cleanup B4 (word table), B10 (typed start failures, explicit toast tone), B11 (friendly errors). */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], qualifiers = "zh-rCN", application = Application::class)
class I18nCleanup106Test {
    private fun source(path: String) = File("src/main/java/io/github/xgl34222220/hetu/$path").readText()

    /* ---------------- B4 ---------------- */

    @Test fun newWordTableEntriesTranslateToAllThreeLanguages() {
        for (key in listOf("代理已启动", "正在启动…", "确认最终运行状态…", "覆写脚本", "测速历史", "DNS 接管实测", "其他可管理核心")) {
            listOf("en-US", "zh-TW", "ru-RU").forEach { lang ->
                val translated = translateHetuText(key, lang)
                assertTrue("$key has no $lang entry", translated != key || lang == "zh-TW")
                assertTrue("$key → $lang is blank", translated.isNotBlank())
            }
            assertEquals(key, translateHetuText(key, "zh-CN"))
        }
        assertEquals("Proxy started", translateHetuText("代理已启动", "en"))
        assertEquals("Перезапуск…", translateHetuText("正在重启…", "ru"))
    }

    @Test fun concatenationsBecameTemplatesThatKeepTheirPlaceholder() {
        for (key in listOf("已检查 %s 个连接", "%s 次", "当前为%s模式", "广告域名在 %s 中按规则拦截；\n该核心不支持实时验证。", "另有 %s 个节点未显示")) {
            listOf("en", "zh-TW", "ru").forEach { lang ->
                val translated = translateHetuText(key, lang)
                assertTrue("$key → $lang lost %s: $translated", translated.contains("%s"))
            }
        }
        assertEquals("42 connections checked", translateHetuText("已检查 %s 个连接", "en").format("42"))
        val adblock = source("tools/ToolsAdblockScreen.kt")
        assertFalse("ht() with a string template can never match a table key", Regex("""\bht\("[^"\n]*\$""").containsMatchIn(adblock))
        assertTrue(adblock.contains("""ht("已检查 %s 个连接").fill("""))
    }

    @Test fun staleSharedNetworkKeyIsGoneAndTheLiveOneStays() {
        val stale = "开启后河图接管共享 / 转发流量；接口与 MAC 直连在 Root 层生效，修改后重启代理。"
        assertEquals(stale, translateHetuText(stale, "en"))
        val live = "开启后河图接管共享 / 转发流量；接口与 MAC 直连在 Root PREROUTING / FORWARD 层生效，修改后重启代理。"
        assertTrue(translateHetuText(live, "en").startsWith("When on, Hetu"))
    }

    @Test fun homeStatusDetailGoesThroughTheWordTable() {
        assertTrue(source("home/HomeScreen.kt").contains("state.statusDetail?.takeIf { it.isNotBlank() }?.let { ht(it) }"))
    }

    /* ---------------- B10 ---------------- */

    @Test fun startFailureIsClassifiedByTypeNotWording() {
        assertEquals(HxStartFailure.Config, hxStartFailure(ProxyStartProblem.NoConfig()))
        assertEquals(HxStartFailure.Config, hxStartFailure(ProxyStartProblem.PlaceholderConfig()))
        // A reworded / translated message keeps its handling.
        val english = RootProxyManager.AppScopeException(IOException("Only selected apps: none selected"))
        assertEquals(HxStartFailure.AppScope, hxStartFailure(ProxyStartProblem.AppScope(english)))
        assertEquals(HxStartFailure.AppScope, hxStartFailure(RuntimeException("wrapped", ProxyStartProblem.AppScope(english))))
        // Wording alone no longer makes a configuration problem.
        assertEquals(HxStartFailure.Runtime, hxStartFailure(IllegalStateException("尚未选择配置")))
        assertEquals(HxStartFailure.Runtime, hxStartFailure(IOException("当前是“仅所选应用代理”，但尚未选择任何应用")))
        assertEquals(HxStartFailure.Runtime, hxStartFailure(IOException("core exited")))
    }

    @Test fun startProblemsKeepTheirUserFacingMessages() {
        assertEquals("尚未选择配置", ProxyStartProblem.NoConfig().message)
        assertTrue(ProxyStartProblem.PlaceholderConfig().message!!.startsWith("当前是河图内置占位配置"))
        val policy = IOException("当前是“仅所选应用代理”，但尚未选择任何应用")
        val typed = ProxyStartProblem.AppScope(RootProxyManager.AppScopeException(policy))
        assertEquals(policy.message, typed.message)
        assertTrue(typed is IllegalStateException)
        val vm = source("app/HetuViewModel.kt")
        assertFalse(vm.contains("""reason.contains("占位")"""))
        assertFalse(vm.contains("""reason.contains("仅所选应用代理")"""))
        assertTrue(vm.contains("hxStartFailure(error)"))
        assertTrue(source("ProxyComposeController.kt").contains("catch (scope: RootProxyManager.AppScopeException)"))
        assertTrue(source("RootProxyManager.java").contains("RootProxyPolicy policy=loadPolicy(profile);"))
    }

    @Test fun explicitToastToneWinsOverKeywordsAndPlainToastFallsBack() {
        val vm = HetuViewModel(ApplicationProvider.getApplicationContext<Application>())
        // Wording a keyword reader would miss or misread:
        vm.toastAs(HxTone.Bad, "服务器出错，请稍后再试（HTTP 502） · HTTP_502")
        assertEquals(HxTone.Bad, vm.toastTone("服务器出错，请稍后再试（HTTP 502） · HTTP_502"))
        vm.toastAs(HxTone.Good, "Proxy started")
        assertEquals(HxTone.Good, vm.toastTone("Proxy started"))
        vm.toastAs(HxTone.Bad, "已保存")
        assertEquals(HxTone.Bad, vm.toastTone("已保存"))
        vm.toast("已保存")
        assertEquals(HxTone.Good, vm.toastTone("已保存"))
        assertEquals(HxTone.Neutral, vm.toastTone("从未发出的消息"))
        repeat(40) { vm.toastAs(HxTone.Warn, "批量 $it") }
        assertEquals(HxTone.Warn, vm.toastTone("批量 39"))
        assertEquals("Bounded: the oldest explicit tone is dropped", HxTone.Neutral, vm.toastTone("批量 0"))
    }

    /* ---------------- B11 ---------------- */

    @Test fun jvmNetworkErrorsBecomeShortChinesePhrasesWithReasonCodes() {
        fun check(error: Throwable, text: String, code: String) {
            val friendly = hxFriendlyError(error, "兜底")
            assertEquals(code, friendly.code)
            assertEquals(text, friendly.text)
            assertEquals("$text · $code", friendly.display)
            assertTrue(friendly.detail.contains(error::class.java.simpleName))
        }
        check(UnknownHostException("Unable to resolve host \"sub.example.com\": No address associated with hostname"), "无法解析服务器地址，请检查网络或 DNS", "UNKNOWN_HOST")
        check(SocketTimeoutException("timeout"), "连接超时，请检查网络后重试", "TIMEOUT")
        check(ConnectException("Failed to connect to /127.0.0.1:9090"), "无法连接服务器，请确认地址和端口", "CONNECT_FAILED")
        check(SSLHandshakeException("Chain validation failed"), "安全连接失败，请检查系统时间或证书", "SSL")
        check(FileNotFoundException("/sdcard/x.yaml (No such file or directory)"), "文件不存在或无法访问", "FILE_NOT_FOUND")
        check(IOException("下载失败：HTTP 404"), "地址不存在，请检查链接（HTTP 404）", "HTTP_404")
        check(IOException("HTTP 403"), "请求被拒绝，请检查链接或访问令牌（HTTP 403）", "HTTP_403")
        check(IllegalStateException("Schema 下载失败：HTTP 503"), "服务器出错，请稍后再试（HTTP 503）", "HTTP_503")
        check(ExecutionException(IOException("wrapped", UnknownHostException("x"))), "无法解析服务器地址，请检查网络或 DNS", "UNKNOWN_HOST")
    }

    @Test fun appWordingAndFallbacksAreKeptUnchanged() {
        val own = hxFriendlyError(IOException("配置超过 4 MiB"), "配置导入失败")
        assertNull(own.code)
        assertEquals("配置超过 4 MiB", own.display)
        assertEquals("配置导入失败", hxErrorMessage(IOException(), "配置导入失败"))
        assertEquals("控制接口返回 401", hxErrorMessage(IOException("控制接口返回 401"), "x"))
        assertEquals("HTTP 200 不是失败", hxErrorMessage(IOException("HTTP 200 不是失败"), "x"))
        assertTrue(hxErrorMessage(IOException("HTTP 401"), "x").contains("401"))
    }

    @Test fun friendlyMessagesTranslateWithTheirCodeKept() {
        assertEquals("Connection timed out; check the network and try again · TIMEOUT",
            translateHetuText("连接超时，请检查网络后重试 · TIMEOUT", "en"))
        assertEquals("Server error; try again later (HTTP 502) · HTTP_502",
            translateHetuText("服务器出错，请稍后再试（HTTP 502） · HTTP_502", "en"))
        assertEquals("未知短语 · CODE", translateHetuText("未知短语 · CODE", "en"))
    }

    @Test fun errorSurfacesUseTheFriendlyMapping() {
        val configs = source("app/ConfigScreens.kt")
        assertFalse(configs.contains("vm.toast(error.message ?:"))
        assertFalse(configs.contains("problem = failure.message ?: \"配置导入失败\""))
        assertTrue(configs.contains("hxErrorMessage(failure, \"配置导入失败\")"))
        assertTrue(source("tools/ToolsAdapter.kt").contains("hxErrorMessage(error, \"导出失败\")"))
        assertTrue(source("app/HetuViewModel.kt").contains("hxErrorMessage(error, fallback)"))
    }
}
