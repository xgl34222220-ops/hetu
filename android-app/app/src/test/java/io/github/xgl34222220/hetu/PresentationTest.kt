package io.github.xgl34222220.hetu

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Color as AndroidColor
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.ByteArrayOutputStream
import java.io.File
import javax.net.ssl.HttpsURLConnection
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.tls.HandshakeCertificates
import okhttp3.tls.HeldCertificate

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class PresentationTest {
    @Test fun yamlVariantsPreserveNamesUrlsOrderAndSource() {
        val sources = listOf(
            "proxy-groups:\n  - name: 'AI 平台'\n    icon: 'https://icons.example/ai.svg#brand'\n  - icon: https://icons.example/git\n    name: GitHub\n",
            "proxy-groups: [{icon: 'https://icons.example/ai.svg#brand', name: 'AI 平台'}, {name: GitHub, icon: 'https://icons.example/git'}]",
            "proxy-groups:\n- name: 'AI 平台'\n  icon: 'https://icons.example/ai.svg#brand'\n- name: GitHub\n  icon: https://icons.example/git\n",
            "defaults: &logo {icon: 'https://icons.example/ai.svg#brand'}\nproxy-groups:\n  - <<: *logo\n    name: 'AI 平台'\n  - name: GitHub\n    icon: https://icons.example/git\n",
        )
        for (source in sources) {
            val before = source.toByteArray().copyOf()
            val map = ProxyGroupIcons.parse(source)
            assertEquals(listOf("AI 平台", "GitHub"), map.keys.toList())
            assertEquals("https://icons.example/ai.svg#brand", map["AI 平台"])
            assertEquals("https://icons.example/git", map["GitHub"])
            assertArrayEquals(before, source.toByteArray())
        }
    }
    @Test fun rasterAndSvgKeepOriginalColorsAndAspectRatio() {
        val bmp = Bitmap.createBitmap(24, 12, Bitmap.Config.ARGB_8888).apply { eraseColor(0xffd32f89.toInt()) }
        for (format in listOf(Bitmap.CompressFormat.PNG, Bitmap.CompressFormat.WEBP_LOSSLESS)) {
            val out = ByteArrayOutputStream(); bmp.compress(format, 100, out)
            val decoded = ProxyGroupIconRepository.decode(out.toByteArray())
            assertEquals(2f, decoded.width.toFloat() / decoded.height, .01f)
            assertEquals(0xffd32f89.toInt(), decoded.getPixel(decoded.width/2, decoded.height/2))
        }
        val svg = "<svg xmlns=\"http://www.w3.org/2000/svg\" viewBox=\"0 0 60 30\"><rect width=\"60\" height=\"30\" fill=\"#d32f89\"/></svg>"
        val rendered = ProxyGroupIconRepository.decode(svg.toByteArray())
        assertEquals(2f, rendered.width.toFloat()/rendered.height, .01f)
        assertEquals(0xffd32f89.toInt(), rendered.getPixel(rendered.width/2, rendered.height/2))
    }
    @Test fun redirectExtensionlessAndConcurrentRequestsAreCachedOffline() = runBlocking {
        val cert = HeldCertificate.Builder().addSubjectAlternativeName("localhost").build()
        val server = MockWebServer()
        val serverTls = HandshakeCertificates.Builder().heldCertificate(cert).build()
        val trusted = HandshakeCertificates.Builder().addTrustedCertificate(cert.certificate).build()
        val oldFactory = HttpsURLConnection.getDefaultSSLSocketFactory()
        try {
            // Test-only trust of this local server. Production TLS validation is untouched.
            server.useHttps(serverTls.sslSocketFactory(), false)
            server.start()
            HttpsURLConnection.setDefaultSSLSocketFactory(trusted.sslSocketFactory())
            server.enqueue(MockResponse().setResponseCode(302).addHeader("Location", "/image"))
            server.enqueue(MockResponse().setHeader("Content-Type", "image/svg+xml").setBody(
                "<svg xmlns=\"http://www.w3.org/2000/svg\" width=\"32\" height=\"32\"><rect width=\"32\" height=\"32\" fill=\"#119955\"/></svg>"))
            val context = ApplicationProvider.getApplicationContext<Context>()
            val repo = ProxyGroupIconRepository.get(context)
            val url = server.url("/no-extension").toString()
            val results = (0..5).map { async { repo.load(url) } }.awaitAll()
            assertTrue(results.all { it is GroupIconLoad.Ready })
            assertEquals(2, server.requestCount) // one redirect + one file, not six downloads
            assertTrue(File(repo.diskPath(url)).isFile)
            server.shutdown()
            val offline = repo.load(url) as GroupIconLoad.Ready
            assertTrue(offline.cached)
        } finally {
            HttpsURLConnection.setDefaultSSLSocketFactory(oldFactory)
            runCatching { server.shutdown() }
        }
    }
    @Test fun errorsUseShortMessageAndDelaySemanticsAreIndependent() {
        assertEquals("Google 更新失败（503）", UiFeedback.summary("Google 更新失败：Mihomo 控制接口返回 503 : {\"message\":\"Get https://s.example/?token=private\"}", true))
        assertEquals("good", delayBand(96)); assertEquals("fair", delayBand(193))
        assertEquals("slow", delayBand(900)); assertEquals("failed", delayBand(-1)); assertEquals("unknown", delayBand(null))
    }
    @Test fun yamlHighlightingDoesNotRewriteCommentsStringsOrSource() {
        val source = "  key: 'https://a.example/#fragment' # note"
        val before = source.toByteArray().copyOf()
        val colors = HetuYamlLanguage.colorLine(source, false)
        assertEquals(HetuYamlLanguage.YAML_KEY, colors[source.indexOf("key")])
        assertEquals(HetuYamlLanguage.YAML_VALUE, colors[source.indexOf("#fragment")])
        assertEquals(HetuYamlLanguage.YAML_COMMENT, colors[source.indexOf("# note")])
        assertArrayEquals(before, source.toByteArray())
        assertTrue(HetuYamlLanguage.colorLine("  x: true # literal", true).all { it == HetuYamlLanguage.YAML_VALUE })
    }
}
