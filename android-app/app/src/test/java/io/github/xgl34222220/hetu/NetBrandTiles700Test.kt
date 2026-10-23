package io.github.xgl34222220.hetu

import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.VectorGroup
import androidx.compose.ui.graphics.vector.VectorPath
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** 7.0.0 网络测试 icons: every service is a full-colour app-icon tile, bundled as vector data. */
class NetBrandTiles700Test {
    private fun paths(group: VectorGroup): List<VectorPath> =
        group.flatMap { if (it is VectorPath) listOf(it) else paths(it as VectorGroup) }

    @Test fun everyServiceWithAPublishedMarkDrawsItAsATile() {
        val lettered = NetworkTest.sites.filter { it.brand == null }.map { it.id }
        assertEquals("only NetEase has no permissively licensed mark", listOf("netease"), lettered)
        for (site in NetworkTest.sites) {
            val slug = site.brand ?: continue
            val mark = NetBrandIcons.vector(slug)
            assertNotNull(slug, mark)
            val art = NetBrandIcons.art(slug)
            assertNotNull(slug, art)
            assertTrue("$slug inset", art!!.inset in .05f..0.3f)
            assertTrue("$slug draws", paths(mark!!.root).all { it.pathData.isNotEmpty() })
        }
        assertEquals("网易", NetworkTest.sites.single { it.id == "netease" }.monogram)
    }

    @Test fun multiColourLogosKeepTheirColours() {
        fun solids(slug: String) = paths(NetBrandIcons.vector(slug)!!.root).mapNotNull { (it.fill as? SolidColor)?.value }.toSet()
        assertEquals("Google G is four colours", 4, solids("google").size)
        assertEquals("YouTube is red with a white play", 2, solids("youtube").size)
        assertTrue("TikTok keeps cyan, red and white", solids("tiktok").size >= 3)
        assertTrue("PayPal keeps its two blues", solids("paypal").size >= 3)
        val source = File("src/main/java/io/github/xgl34222220/hetu/app/NetBrandIcons.kt").readText()
        assertTrue(source.contains("CC0 1.0") && source.contains("MIT"))
        assertTrue("no runtime downloads", !source.contains("http.get") && !source.contains("openConnection"))
    }
}
