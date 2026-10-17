package io.github.xgl34222220.hetu

import android.app.Application
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.net.ConnectivityManager
import android.os.Looper
import androidx.activity.ComponentActivity
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.unit.dp
import androidx.test.core.app.ApplicationProvider
import io.github.xgl34222220.hetu.home.HetuHomeTheme
import io.github.xgl34222220.hetu.home.HomeActions
import io.github.xgl34222220.hetu.home.HomeBarBackdrop
import io.github.xgl34222220.hetu.home.HomeIpDetailScreen
import io.github.xgl34222220.hetu.home.HomeNetSide
import io.github.xgl34222220.hetu.home.HomeProbe
import io.github.xgl34222220.hetu.home.HomeScreen
import io.github.xgl34222220.hetu.home.HomeStatus
import io.github.xgl34222220.hetu.home.HomeUiState
import io.github.xgl34222220.hetu.home.HomeWan
import io.github.xgl34222220.hetu.home.HomeWanState
import io.github.xgl34222220.hetu.home.rememberHomeBarGlass
import io.github.xgl34222220.hetu.ui.LocalHetuMotionEnabled
import io.github.xgl34222220.hetu.ui.translateHetuText
import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File
import java.util.concurrent.TimeUnit

/**
 * 2026-10-15 3.3.3 首页: 网络延迟 measures the path apps use (proxy while running, direct while
 * stopped) and only answers that count; the WAN card / 公网 IP 详情 show the address apps see in
 * both states from several sources, keeping a labelled last answer; pinned bars are opaque.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], qualifiers = "zh-rCN-w393dp-h852dp-mdpi", application = Application::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class HomeProbe333Test {
    @get:Rule val rule = createAndroidComposeRule<ComponentActivity>()
    private val app get() = ApplicationProvider.getApplicationContext<Application>()
    private val prefs get() = app.getSharedPreferences("hetu", Context.MODE_PRIVATE)
    private val servers = mutableListOf<MockWebServer>()
    private fun source(path: String) = File("src/main/java/io/github/xgl34222220/hetu/$path").readText()

    @Before fun clean() {
        prefs.edit().clear().putString("appearance", "light").putBoolean("enableBlur", false).commit()
    }

    @After fun finish() {
        servers.forEach { runCatching { it.shutdown() } }
    }

    private fun server(vararg responses: MockResponse) = MockWebServer().apply { responses.forEach(::enqueue); start() }.also { servers += it }

    private fun eventually(reason: String, predicate: () -> Boolean) {
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10)
        while (System.nanoTime() < deadline) {
            shadowOf(Looper.getMainLooper()).idle()
            if (predicate()) return
            Thread.sleep(10)
        }
        assertTrue(reason, predicate())
    }

    private fun home(state: HomeUiState) {
        rule.setContent {
            CompositionLocalProvider(LocalHetuMotionEnabled provides false) {
                HetuHomeTheme(dark = false) {
                    HomeScreen(state, HomeActions(), {}, {}, {}, {}, contentPadding = PaddingValues(bottom = 16.dp), motion = false)
                }
            }
        }
        rule.waitForIdle()
    }

    /* ---------------- latency ---------------- */

    @Test fun generate204TargetsOnlyCountA204() {
        assertTrue(hxProbeAnswered("https://www.google.com/generate_204", 204))
        assertFalse("A portal / hijack page answering 200 is not Google", hxProbeAnswered("https://www.google.com/generate_204", 200))
        assertFalse(hxProbeAnswered("https://cp.cloudflare.com/generate_204", 302))
        assertTrue(hxProbeAnswered("https://www.baidu.com/", 200))
        assertTrue(hxProbeAnswered("https://www.baidu.com/", 302))
        assertFalse(hxProbeAnswered("https://www.baidu.com/", 403))
    }

    @Test fun googleTargetIsGoogleAndTheOldGstaticDefaultMigrates() {
        assertEquals("https://www.google.com/generate_204", ProxyLatencyTargets.defaults[2].url)
        assertEquals("https://www.google.com/generate_204", ProxyLatencyTargets.load(prefs)[2].url)
        ProxyLatencyTargets.save(prefs, listOf(ProxyLatencyTargets.defaults[0], ProxyLatencyTargets.defaults[1],
            ProxyLatencyTarget("Google", "https://www.gstatic.com/generate_204")))
        assertEquals("https://www.google.com/generate_204", ProxyLatencyTargets.load(prefs)[2].url)
        // A user's own target is never rewritten.
        ProxyLatencyTargets.save(prefs, listOf(ProxyLatencyTargets.defaults[0], ProxyLatencyTargets.defaults[1],
            ProxyLatencyTarget("谷歌", "https://www.gstatic.com/generate_204")))
        assertEquals("https://www.gstatic.com/generate_204", ProxyLatencyTargets.load(prefs)[2].url)
    }

    @Test fun directProbeCountsAWrong200AsTimeout() = runBlocking {
        val good = server(MockResponse().setResponseCode(204))
        val hijack = server(MockResponse().setResponseCode(200).setBody("<html>portal</html>"))
        val plain = server(MockResponse().setResponseCode(200))
        ProxyLatencyTargets.save(prefs, listOf(ProxyLatencyTarget("A", good.url("/generate_204").toString()),
            ProxyLatencyTarget("B", hijack.url("/generate_204").toString()), ProxyLatencyTarget("C", plain.url("/").toString())))
        val measured = hxDirectSiteLatencies(app)
        assertTrue(measured.getValue("A") > 0L)
        assertEquals(-1L, measured.getValue("B"))
        assertTrue(measured.getValue("C") > 0L)
        assertTrue(source("ProxyDashboardRepository.kt").contains("if (hxProbeAnswered(url, code))"))
    }

    @Test fun runningCardIsNetworkLatencyThroughTheProxy() {
        home(HomeUiState(status = HomeStatus.Running(120), probes = listOf(HomeProbe("Baidu", 30L), HomeProbe("Cloudflare", 90L), HomeProbe("Google", 120L))))
        rule.onNodeWithText("网络延迟").assertExists()
        rule.onNodeWithText("经代理 · 与普通应用相同的路径").assertExists()
        rule.onNodeWithText("120 ms").assertExists()
        // The former title and caption are kept only as zero-size anchors (frozen UI tests).
        rule.onNodeWithText("本机直测").assertExists()
        rule.onNodeWithText("由河图进程直接请求，未指定代理节点；结果不代表其他应用的代理路径。").assertExists()
    }

    @Test fun stoppedCardWithoutADirectResultShowsDashesNotProxyNumbers() {
        home(HomeUiState(status = HomeStatus.NotRunning, probes = listOf(HomeProbe("Baidu"), HomeProbe("Cloudflare"), HomeProbe("Google"))))
        rule.onNodeWithText("直连 · 与普通应用相同的路径").assertExists()
        rule.onNodeWithText("超时").assertDoesNotExist()
        val adapter = source("home/HomeAdapter.kt")
        assertTrue(adapter.contains("probes = if (!status.isLive) mapped.probes.map { p -> p.copy(delayMs = directProbes[p.name]"))
        assertTrue(source("app/NewUiIntegration.kt").contains("delays = if (vm.state.running) vm.siteDelays else emptyMap()"))
    }

    /* ---------------- public address ---------------- */

    @Test fun parsersReadEverySourceShape() {
        val ipip = hxParseIpipNet("""{"ret":"ok","data":{"ip":"117.40.1.2","location":["中国","江西","赣州","","电信"]}}""")
        assertEquals("117.40.1.2", ipip.address); assertEquals("CN", ipip.countryCode); assertEquals("江西", ipip.region)
        assertEquals("赣州", ipip.city); assertEquals("电信", ipip.isp); assertEquals("IPv4", ipip.ipType)
        val sb = hxParseIpSbGeo("""{"region":"Oregon","organization":"Amazon.com","isp":"Amazon.com","city":"Boardman","asn":16509,"latitude":45.8401,"ip":"52.26.208.45","country":"United States","timezone":"America/Los_Angeles","country_code":"US","longitude":-119.705}""")
        assertEquals("AS16509", sb.asn); assertEquals("US", sb.countryCode); assertEquals("45.8401, -119.7050", sb.coordinates)
        val info = hxParseIpInfo("""{"ip":"52.26.208.45","city":"Boardman","region":"Oregon","country":"US","loc":"45.8399,-119.7006","org":"AS16509 Amazon.com, Inc.","timezone":"America/Los_Angeles"}""")
        assertEquals("AS16509", info.asn); assertEquals("Amazon.com, Inc.", info.isp); assertEquals("US", info.countryCode)
        val cip = hxParseCipCc("IP\t: 117.40.1.2\n地址\t: 中国 江西 赣州\n运营商\t: 电信\n\n数据二\t: 中国 江西 | 电信\n")
        assertEquals("117.40.1.2", cip.address); assertEquals("赣州", cip.city); assertEquals("电信", cip.isp)
        assertEquals("240e:3b0::1", hxParsePlainIp("240e:3b0::1\n").address)
        assertEquals("IPv6", hxParsePlainIp("240e:3b0::1").ipType)
        assertTrue(runCatching { hxParsePlainIp("<html>blocked</html>") }.isFailure)
        assertTrue(runCatching { hxParseIpipNet("""{"ret":"err"}""") }.isFailure)
        assertNull(hxIpLiteral("example.com"))
        val merged = hxMergeWan(WanRuntimeInfo(address = "117.40.1.2", ipType = "IPv4"), ipip)
        assertEquals("江西", merged.region)
    }

    @Test fun overlayBelongsToTheCurrentPathOnly() {
        val inspector = ProxyRuntimeSnapshot(running = true, wanAddress = "198.51.100.1", wanState = "success")
        val direct = HxPublicIpView(HxIpPath.Direct, WanRuntimeInfo(address = "117.40.1.2"), "success", 1L)
        assertEquals(inspector, hxWithPublicIp(inspector, direct, running = true))
        assertEquals("117.40.1.2", hxWithPublicIp(ProxyRuntimeSnapshot(), direct, running = false).wanAddress)
        val loading = HxPublicIpView(HxIpPath.Proxy, null, "loading")
        assertEquals("Nothing of our own yet keeps the inspector's answer", inspector, hxWithPublicIp(inspector, loading, running = true))
        val kept = hxWithPublicIp(ProxyRuntimeSnapshot(), direct.copy(state = "stale", error = "所有查询源均无响应，稍后自动重试"), running = false)
        assertEquals("stale", kept.wanState); assertEquals(1L, kept.wanCheckedAt)
    }

    @Test fun lookupShowsTheFirstAnswerFillsDetailsAndKeepsItWhenAllSourcesFail() {
        val network = (app.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager).activeNetwork
        assumeTrue("Robolectric provides an active network", network != null)
        val plain = server(MockResponse().setBody("117.40.1.2\n"), MockResponse().setResponseCode(503))
        val rich = server(MockResponse().setBody("""{"ret":"ok","data":{"ip":"117.40.1.2","location":["中国","江西","赣州","","电信"]}}""")
            .setBodyDelay(300, TimeUnit.MILLISECONDS), MockResponse().setResponseCode(503))
        val sources = listOf(
            HxIpSource("plain", plain.url("/").toString(), false, parse = ::hxParsePlainIp),
            HxIpSource("rich", rich.url("/json").toString(), true, parse = ::hxParseIpipNet),
        )
        val seen = mutableListOf<HxPublicIpView>()
        val lookup = HxPublicIpLookup(app, sources) { seen += it }
        try {
            lookup.request(running = false, force = false)
            eventually("Direct lookup gets the detailed answer") { lookup.view.info?.region == "江西" }
            assertEquals(HxIpPath.Direct, lookup.view.path)
            assertEquals("success", lookup.view.state)
            assertTrue(lookup.view.checkedAt > 0L)
            assertTrue("The address appears before the details", seen.any { it.info?.address == "117.40.1.2" && it.info?.region == "—" } || seen.first { it.info != null }.info?.region == "江西")
            assertTrue(prefs.getString("hxPublicIpLast.direct", "").orEmpty().contains("117.40.1.2"))
            lookup.request(running = false, force = true)
            eventually("Failed refresh settles") { lookup.view.state == "stale" }
            assertEquals("117.40.1.2", lookup.view.info?.address)
            assertEquals("所有查询源均无响应，稍后自动重试", lookup.view.error)
        } finally {
            lookup.close()
        }
    }

    @Test fun stoppedWanCardShowsTheAddressAndAKeptOneWithItsTime() {
        val state = HomeUiState(status = HomeStatus.NotRunning, probes = listOf(HomeProbe("Baidu")),
            wan = HomeWan(ip = "117.40.1.2", region = "江西", countryCode = "CN", state = HomeWanState.Ready))
        home(state)
        rule.onNodeWithText("117.40.1.2").performScrollTo().assertExists()
        rule.onNodeWithText("江西").assertExists()
        rule.onNodeWithText("代理未运行，启动后查询公网 IP").assertDoesNotExist()
    }

    @Test fun keptWanAnswerIsLabelledWithItsTime() {
        val at = java.util.Calendar.getInstance().apply { set(java.util.Calendar.HOUR_OF_DAY, 9); set(java.util.Calendar.MINUTE, 5) }.timeInMillis
        home(HomeUiState(status = HomeStatus.Running(60), wan = HomeWan(ip = "198.51.100.7", region = "Tokyo", state = HomeWanState.Stale, checkedAt = at)))
        rule.onNodeWithText("198.51.100.7").performScrollTo().assertExists()
        rule.onNodeWithText("上次 09:05").assertExists()
    }

    @Test fun ipDetailsShowTheDirectAddressWhileStopped() {
        val state = HomeUiState(status = HomeStatus.NotRunning,
            wan = HomeWan(ip = "117.40.1.2", region = "江西", isp = "电信", state = HomeWanState.Ready))
        rule.setContent {
            CompositionLocalProvider(LocalHetuMotionEnabled provides false) {
                HetuHomeTheme(dark = false) { HomeIpDetailScreen(state, HomeNetSide.Wan, {}, {}, {}, { _, _ -> }) }
            }
        }
        rule.waitForIdle()
        rule.onNodeWithText("117.40.1.2").assertExists()
        rule.onNodeWithText("电信").assertExists()
        rule.onNodeWithText("代理未运行，显示本机直连的公网出口。 「未知」表示查询源未返回该字段。").assertExists()
        rule.onNodeWithText("公网信息查询失败：出口检测失败，稍后自动重试").assertDoesNotExist()
    }

    /* ---------------- pinned bar ---------------- */

    @Test fun pinnedBarIsOpaqueOverScrolledContent() {
        rule.setContent {
            CompositionLocalProvider(LocalHetuMotionEnabled provides false) {
                HetuHomeTheme(dark = false) {
                    Box(Modifier.size(200.dp)) {
                        Box(Modifier.fillMaxSize().background(Color.Red))
                        HomeBarBackdrop(rememberHomeBarGlass(lifted = true), Modifier.fillMaxWidth().height(60.dp).testTag("bar"))
                    }
                }
            }
        }
        rule.waitForIdle()
        val bar = rule.onNodeWithTag("bar").fetchSemanticsNode().boundsInWindow
        var pixel = 0
        var below = 0
        rule.runOnIdle {
            val view = rule.activity.window.decorView
            val bitmap = Bitmap.createBitmap(view.width, view.height, Bitmap.Config.ARGB_8888)
            view.draw(Canvas(bitmap))
            pixel = bitmap.getPixel(bar.center.x.toInt(), bar.center.y.toInt())
            below = bitmap.getPixel(bar.center.x.toInt(), (bar.bottom + 20f).toInt())
            bitmap.recycle()
        }
        assertEquals("Content under the bar is red", 255, android.graphics.Color.red(below))
        // The old bar was the page colour at 96.5 % alpha, letting text show through; red content
        // under an opaque bar leaves no trace.
        assertTrue("Bar pixel %08x".format(pixel), android.graphics.Color.blue(pixel) >= 249 && android.graphics.Color.green(pixel) >= 236)
        assertFalse(source("home/HomeComponents.kt").contains("c.bg.copy(alpha = .965f)"))
    }

    /* ---------------- words ---------------- */

    @Test fun everyNewWordIsTranslated() {
        val words = listOf("网络延迟", "经代理 · 与普通应用相同的路径", "直连 · 与普通应用相同的路径", "等待代理状态", "上次结果", "上次 %s",
            "经代理查询，与普通应用看到的出口相同。", "代理未运行，显示本机直连的公网出口。", "%s；最新查询未成功，下拉首页或点右上角可重新查询。",
            "「未知」表示查询源未返回该字段。", "所有查询源均无响应，稍后自动重试", "当前没有可用网络")
        for (word in words) {
            assertNotEquals(word, word, translateHetuText(word, "en"))
            assertNotEquals(word, word, translateHetuText(word, "ru"))
        }
        assertEquals("Latency", translateHetuText("网络延迟", "en"))
        assertEquals("網路延遲", translateHetuText("网络延迟", "zh-TW"))
    }

    @Test fun proxyPathAsksOnlyOverseasSources() {
        val mainland = HxIpSources.all.filter { it.mainland }.map { it.id }.toSet()
        org.junit.Assert.assertEquals(setOf("myip.ipip.net", "cip.cc", "ip.3322.net", "ipw.cn"), mainland)
        org.junit.Assert.assertTrue(HxIpSources.all.count { !it.mainland } >= 3)
    }
}
