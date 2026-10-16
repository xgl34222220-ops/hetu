package io.github.xgl34222220.hetu

import android.app.Application
import android.content.Context
import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.test.core.app.ApplicationProvider
import io.github.xgl34222220.hetu.tools.ToolsDestination
import io.github.xgl34222220.hetu.tools.ToolsFeatureActions
import io.github.xgl34222220.hetu.tools.ToolsFeaturePage
import io.github.xgl34222220.hetu.tools.ToolsPreflight
import io.github.xgl34222220.hetu.ui.translateHetuText
import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File
import java.io.IOException
import java.net.ConnectException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import javax.net.ssl.SSLHandshakeException

/**
 * 2026-10-16 4.0.0 function pass: retry of transient download / subscription failures (S1/S2),
 * web-page instead of config (S3), 导出诊断包 (D1), status notification start action and error line
 * (N1–N3), tile language / refresh (T1/T2) and the no-Root start message (E1).
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], qualifiers = "zh-rCN-w393dp-h852dp-mdpi", application = Application::class)
class Func400Test {
    @get:Rule val rule = createAndroidComposeRule<ComponentActivity>()
    private val app get() = ApplicationProvider.getApplicationContext<Application>()
    private fun source(path: String) = File("src/main/java/io/github/xgl34222220/hetu/$path").readText()

    @Test fun transientFailuresAreRetriedAndFinalAnswersAreNot() {
        assertTrue(HxNetRetry.transient(SocketTimeoutException("read timed out")))
        assertTrue(HxNetRetry.transient(ConnectException("refused")))
        assertTrue(HxNetRetry.transient(UnknownHostException("sub.example")))
        assertTrue(HxNetRetry.transient(IOException("下载失败：HTTP 503")))
        assertTrue(HxNetRetry.transient(IOException("下载失败：HTTP 429")))
        assertTrue(HxNetRetry.transient(IOException("wrapped", SocketTimeoutException())))
        assertTrue(HxNetRetry.transient(MihomoControllerClient.ControllerHttpException(503, "{\"message\":\"timeout\"}", false)))
        assertFalse(HxNetRetry.transient(MihomoControllerClient.ControllerHttpException(401, "", false)))
        assertFalse(HxNetRetry.transient(MihomoControllerClient.ControllerHttpException(404, "", false)))
        assertFalse(HxNetRetry.transient(IOException("下载失败：HTTP 404")))
        assertFalse(HxNetRetry.transient(IOException("下载失败：HTTP 403")))
        assertFalse(HxNetRetry.transient(SSLHandshakeException("bad cert")))
        assertFalse(HxNetRetry.transient(IOException("配置超过 4 MiB")))
        assertTrue(source("app/HetuViewModel.kt").contains("if (!HxNetRetry.transient(first)) throw first"))
    }

    @Test fun configDownloadRetriesA503AndStopsAtA404() = runBlocking {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setResponseCode(503))
            server.enqueue(MockResponse().setResponseCode(200).setBody("proxies:\n  - {name: a, type: direct}\n"))
            server.start()
            val (body, _) = HxConfigTransaction.download(server.url("/sub").toString())
            assertTrue(String(body).startsWith("proxies:"))
            assertEquals(2, server.requestCount)
        }
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setResponseCode(404))
            server.enqueue(MockResponse().setResponseCode(200).setBody("proxies:\n"))
            server.start()
            try {
                HxConfigTransaction.download(server.url("/gone").toString())
                fail("404 must fail")
            } catch (error: IOException) {
                assertTrue(error.message.orEmpty().contains("HTTP 404"))
            }
            assertEquals("A 404 is final", 1, server.requestCount)
        }
    }

    @Test fun aWebPageIsReportedAsAWebPage() {
        val page = "<!DOCTYPE html>\n<html><head><title>Login</title></head><body>sign in</body></html>"
        try {
            HxConfigTransaction.decode(page.toByteArray())
            fail("HTML must be rejected")
        } catch (error: IOException) {
            assertEquals("下载到的是网页而不是配置，请检查链接是否正确或需要登录", error.message)
        }
        assertTrue(HxConfigTransaction.looksLikeHtml("  <html lang=\"en\">"))
        assertFalse(HxConfigTransaction.looksLikeHtml("proxies:\n  - name: <html>"))
        // A real config is still accepted, and a Base64 list keeps its own hint.
        assertTrue(HxConfigTransaction.decode("proxies:\nrules:\n".toByteArray()).startsWith("proxies:"))
        try {
            HxConfigTransaction.decode("dm1lc3M6Ly9hYmM=".toByteArray()); fail()
        } catch (error: IOException) { assertTrue(error.message.orEmpty().contains("Base64")) }
    }

    @Test fun diagnosticsBundleCollectsSelfHealStateAndRedactsSecrets() = runBlocking {
        app.getSharedPreferences("hetu", Context.MODE_PRIVATE).edit().putString("proxyControllerSecret", "s3cr3t-token").commit()
        var command = ""
        val text = HxDiagnosticsBundle.build(
            app,
            report = { "controller secret=s3cr3t-token\nsub=https://example.com/api/sub?token=abc" },
            rootRead = { command = it; "--- selfheal-state ---\nlevel=1 secret s3cr3t-token\n--- watchdog.log (last 300 lines) ---\nliveness ok" },
        )
        assertTrue(text.startsWith("河图诊断包"))
        assertTrue(text.contains("app=" + BuildConfig.VERSION_NAME))
        assertTrue(text.contains("liveness ok"))
        assertFalse("secret redacted", text.contains("s3cr3t-token"))
        assertFalse("full URL redacted", text.contains("token=abc"))
        // The Root read is read-only and covers the watchdog / self-heal / routing state.
        listOf("watchdog.log", "selfheal-state", "egress-state", "cgroup-escape", "wifi-reconnects",
            "ip rule show", "iptables-save -t mangle", "ip6tables-save").forEach { assertTrue(it, command.contains(it)) }
        listOf("hetu-root.sh", "iptables -D", "iptables -F", "iptables -X", "rm ", "kill ", "setprop").forEach { assertFalse(it, command.contains(it)) }
        assertTrue(HxDiagnosticsBundle.fileName(0L).matches(Regex("hetu-diagnostics-\\d{8}-\\d{6}\\.txt")))
        // A failing part is written as text instead of aborting the export.
        val partial = HxDiagnosticsBundle.build(app, report = { throw IOException("controller down") }, rootRead = { throw IOException("su missing") })
        assertTrue(partial.contains("controller down"))
        assertTrue(partial.contains("su missing"))
    }

    @Test fun diagnosticsPageOffersTheExportRow() {
        rule.setContent {
            HetuAppTheme(appearance = "light", dynamic = false) {
                CompositionLocalProvider(LocalNav provides HxNav(), LocalHxBlur provides false) {
                    Box(Modifier.fillMaxSize()) {
                        ToolsFeaturePage(
                            ToolsDestination.Diag,
                            ToolsFeatureActions(runPreflight = { ToolsPreflight.Passed() }, exportDiagnostics = { "x" }),
                            onBack = {},
                        )
                    }
                }
            }
        }
        rule.mainClock.advanceTimeBy(1000); rule.waitForIdle()
        rule.onNodeWithText("导出诊断包").assertExists()
        assertTrue(source("tools/ToolsFeatureAdapter.kt").contains("HxDiagnosticsBundle.build(app"))
    }

    @Test fun notificationOffersStartWhileStoppedAndShowsTheFailure() {
        val service = source("ProxyStatusNotificationService.kt")
        assertTrue(service.contains("\"stop\" -> if (running) \"停止\" to ACTION_STOP_PROXY else \"启动\" to ACTION_START_PROXY"))
        assertTrue(service.contains("ACTION_START_PROXY -> runControl(\"正在启动…\") { controller.start() }"))
        assertTrue(service.contains("recentError()"))
        assertTrue(service.contains("hxPlainText(this, source)"))
        assertEquals(10 * 60_000L, ProxyStatusNotificationService.ERROR_VISIBLE_MS)
    }

    @Test fun tileFollowsTheLanguageAndIsRefreshedAfterAnInAppToggle() {
        val tile = source("HetuProxyTileService.kt")
        assertTrue(tile.contains("tile.label = text(\"河图代理\")"))
        assertTrue(tile.contains("TileService.requestListeningState"))
        assertTrue(source("app/HetuViewModel.kt").contains("HetuProxyTileService.requestRefresh(app)"))
        HetuProxyTileService.requestRefresh(app) // never throws, even without System UI
        app.getSharedPreferences("hetu", Context.MODE_PRIVATE).edit().putString("appLanguage", "en").commit()
        assertEquals("Hetu proxy", hxPlainText(app, "河图代理"))
        app.getSharedPreferences("hetu", Context.MODE_PRIVATE).edit().remove("appLanguage").commit()
    }

    @Test fun startWithoutRootSaysWhatToFix() = runBlocking {
        assertFalse("No su in the test JVM", HxRootCheck.granted(app))
        assertEquals(HxRootCheck.NO_ROOT + "\n\n启动失败", HxRootCheck.message("启动失败"))
        assertEquals(HxRootCheck.NO_ROOT, HxRootCheck.message(""))
        assertTrue(source("app/HetuViewModel.kt").contains("} else if (!HxRootCheck.granted(app)) {"))
    }

    @Test fun newWordsAreTranslated() {
        listOf("导出诊断包", "诊断包已导出", "诊断包导出失败", "没有可用的文件保存应用", "下载到的是网页而不是配置，请检查链接是否正确或需要登录",
            "诊断信息、看门狗与核心日志、路由与 iptables 状态，保存为一个文本文件", "隐藏", "已停止", "正在重载…", "正在读取代理状态…",
            "暂时无法读取代理状态", "河图 · 代理状态", "上次操作失败", "通知栏操作失败", "代理切换失败", "河图代理", "切换失败", "切换中…",
            HxRootCheck.NO_ROOT).forEach { key ->
            listOf("en", "zh-TW", "ru").forEach { lang ->
                if (!(lang == "zh-TW" && key == "已停止")) assertTrue("$key/$lang", translateHetuText(key, lang) != key)
            }
        }
    }
}
