package io.github.xgl34222220.hetu

import java.io.ByteArrayInputStream
import java.io.IOException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import java.util.Collections
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 网络测试 · 解锁与地区检测：每个检测器只用假 HTTP 响应判定（不访问外网），
 * 另核查每项预算、并发上限与境内外出口 IP 解析。
 */
class NetworkUnlock101Test {

    /** Answers by exact URL; anything unlisted fails like an unreachable host. */
    private class FakeHttp(private val routes: Map<String, () -> UnlockReply>) : UnlockTransport {
        val seen: MutableList<UnlockRequest> = Collections.synchronizedList(ArrayList())
        override fun fetch(request: UnlockRequest, timeoutMs: Int): UnlockReply {
            seen += request
            val route = routes[request.url] ?: throw UnknownHostException(request.url)
            return route().copy(url = request.url)
        }
    }

    private fun reply(code: Int, body: String = "", headers: Map<String, List<String>> = emptyMap()): () -> UnlockReply =
        { UnlockReply(code, headers.mapKeys { it.key.lowercase() }, body, "") }

    private fun fail(error: IOException): () -> UnlockReply = { throw error }

    private fun check(id: String, routes: Map<String, () -> UnlockReply>): UnlockResult = runBlocking {
        NetworkUnlock.runOne(NetworkUnlock.checks.single { it.id == id }, FakeHttp(routes))
    }

    private val trace = { loc: String -> reply(200, "fl=1\nh=x\nip=203.0.113.9\nts=1\ncolo=NRT\nloc=$loc\n") }

    /* ------------------------------ Netflix ------------------------------ */

    private val nfLicensed = "https://www.netflix.com/title/${NetworkUnlock.NETFLIX_LICENSED_TITLE}"
    private val nfOriginal = "https://www.netflix.com/title/${NetworkUnlock.NETFLIX_ORIGINAL_TITLE}"

    @Test fun netflixLicensedTitleMeansFullUnlockWithRequestCountry() {
        val body = "<html>" + "x".repeat(5000) + "\"requestCountry\":{\"supportedLocales\":[\"en\"],\"id\":\"SG\",\"status\":\"ALLOW\"}"
        val result = check("netflix", mapOf(nfLicensed to reply(200, body)))
        assertEquals(UnlockState.Unlocked, result.state)
        assertEquals("SG", result.region)
        assertEquals("解锁", result.chip)
    }

    @Test fun netflixOriginalsOnlyIsRestrictedAndRegionComesFromTheRedirect() {
        val result = check("netflix", mapOf(
            nfLicensed to reply(302, headers = mapOf("Location" to listOf("/jp-en/title/${NetworkUnlock.NETFLIX_LICENSED_TITLE}"))),
            "https://www.netflix.com/jp-en/title/${NetworkUnlock.NETFLIX_LICENSED_TITLE}" to reply(404),
            nfOriginal to reply(200),
        ))
        assertEquals(UnlockState.Restricted, result.state)
        assertEquals("JP", result.region)
        assertEquals("受限 · 仅自制剧", result.chip)
    }

    @Test fun netflixWithNeitherTitleIsUnavailable() {
        val result = check("netflix", mapOf(nfLicensed to reply(404), nfOriginal to reply(404)))
        assertEquals(UnlockState.Unavailable, result.state)
        assertEquals(UnlockState.Unavailable, check("netflix", mapOf(nfLicensed to reply(403))).state)
        val broken = check("netflix", mapOf(nfLicensed to reply(502)))
        assertEquals(UnlockState.Failed, broken.state)
        assertEquals("失败 · HTTP 502", broken.chip)
    }

    /* ------------------------------ YouTube ------------------------------ */

    private val ytPremium = "https://www.youtube.com/premium"

    @Test fun youtubePremiumReadsInnertubeCountry() {
        val ok = check("youtube", mapOf(ytPremium to reply(200, "ytcfg.set({\"INNERTUBE_CONTEXT_GL\":\"HK\",\"x\":1})")))
        assertEquals(UnlockState.Unlocked, ok.state)
        assertEquals("HK", ok.region)
        val no = check("youtube", mapOf(ytPremium to reply(200, "\"INNERTUBE_CONTEXT_GL\":\"CN\" … YouTube Premium is not available in your country")))
        assertEquals(UnlockState.Unavailable, no.state)
        assertEquals("CN", no.region)
    }

    @Test fun youtubeGoogleSorryPageIsAVerificationFailureNotABlock() {
        val result = check("youtube", mapOf(
            ytPremium to reply(302, headers = mapOf("Location" to listOf("https://www.google.com/sorry/index?continue=x"))),
            "https://www.google.com/sorry/index?continue=x" to reply(429),
        ))
        assertEquals(UnlockState.Failed, result.state)
        assertEquals("需人机验证", result.detail)
    }

    /* ------------------------------ Disney+ ------------------------------ */

    @Test fun disneyRegionFromHeaderAndUnavailableRedirect() {
        val ok = check("disney", mapOf("https://www.disneyplus.com/" to reply(200, headers = mapOf("Region" to listOf("us"), "physical-location" to listOf("US")))))
        assertEquals(UnlockState.Unlocked, ok.state)
        assertEquals("US", ok.region)
        val no = check("disney", mapOf(
            "https://www.disneyplus.com/" to reply(302, headers = mapOf("location" to listOf("https://www.disneyplus.com/unavailable"))),
            "https://www.disneyplus.com/unavailable" to reply(200),
        ))
        assertEquals(UnlockState.Unavailable, no.state)
    }

    /* ------------------------------ ChatGPT / Claude / Gemini ------------------------------ */

    private val openAiCompliance = "https://api.openai.com/compliance/cookie_requirements"

    @Test fun chatgptSupportedCountryFromTraceAndCompliance() {
        val ok = check("chatgpt", mapOf("https://chatgpt.com/cdn-cgi/trace" to trace("JP"), openAiCompliance to reply(200, "{\"cookie_consent_required\":false}")))
        assertEquals(UnlockState.Unlocked, ok.state)
        assertEquals("JP", ok.region)
        val refused = check("chatgpt", mapOf("https://chatgpt.com/cdn-cgi/trace" to trace("JP"), openAiCompliance to reply(403, "{\"error\":{\"code\":\"unsupported_country\"}}")))
        assertEquals(UnlockState.Unavailable, refused.state)
        val hk = check("chatgpt", mapOf("https://chatgpt.com/cdn-cgi/trace" to trace("HK"), openAiCompliance to reply(200, "{}")))
        assertEquals(UnlockState.Unavailable, hk.state)
        assertEquals("HK", hk.region)
        val down = check("chatgpt", mapOf("https://chatgpt.com/cdn-cgi/trace" to fail(SocketTimeoutException()), openAiCompliance to fail(SocketTimeoutException())))
        assertEquals("失败 · 超时", down.chip)
    }

    @Test fun claudeRegionRedirectAndChallenge() {
        val ok = check("claude", mapOf("https://claude.ai/cdn-cgi/trace" to trace("US"), "https://claude.ai/" to reply(403, headers = mapOf("cf-mitigated" to listOf("challenge")))))
        assertEquals("A browser challenge does not hide a supported exit", UnlockState.Unlocked, ok.state)
        assertEquals("US", ok.region)
        val refused = check("claude", mapOf("https://claude.ai/cdn-cgi/trace" to trace("US"), "https://claude.ai/" to reply(307, headers = mapOf("Location" to listOf("/app-unavailable-in-region")))))
        assertEquals(UnlockState.Unavailable, refused.state)
        val ru = check("claude", mapOf("https://claude.ai/cdn-cgi/trace" to trace("RU"), "https://claude.ai/" to reply(200)))
        assertEquals(UnlockState.Unavailable, ru.state)
        val challenged = check("claude", mapOf("https://claude.ai/" to reply(403, headers = mapOf("cf-mitigated" to listOf("challenge")))))
        assertEquals("失败 · 需人机验证", challenged.chip)
    }

    @Test fun geminiAlpha3RegionAndUnsupportedText() {
        val ok = check("gemini", mapOf("https://gemini.google.com/" to reply(200, "[[\"x\",2,1,200,\"SGP\"]]")))
        assertEquals(UnlockState.Unlocked, ok.state)
        assertEquals("SG", ok.region)
        val hk = check("gemini", mapOf("https://gemini.google.com/" to reply(200, "[[\"x\",2,1,200,\"HKG\"]]")))
        assertEquals(UnlockState.Unavailable, hk.state)
        assertEquals("HK", hk.region)
        val text = check("gemini", mapOf("https://gemini.google.com/" to reply(200, "Gemini isn't currently supported in your country.")))
        assertEquals(UnlockState.Unavailable, text.state)
        assertEquals("失败 · 未返回地区", check("gemini", mapOf("https://gemini.google.com/" to reply(200, "<html></html>"))).chip)
    }

    /* ------------------------------ TikTok / Bilibili ------------------------------ */

    @Test fun tiktokRegionFromPageState() {
        val ok = check("tiktok", mapOf("https://www.tiktok.com/" to reply(200, "{\"appContext\":{\"region\":\"TW\",\"language\":\"en\"}}")))
        assertEquals(UnlockState.Unlocked, ok.state)
        assertEquals("TW", ok.region)
        assertEquals(UnlockState.Unavailable, check("tiktok", mapOf("https://www.tiktok.com/" to reply(451))).state)
    }

    private fun bili(ep: String) = "https://api.bilibili.com/pgc/player/web/playurl?ep_id=$ep&fnval=16"
    private val biliZone = "https://api.bilibili.com/x/web-interface/zone"
    private val playable = reply(200, "{\"code\":0,\"message\":\"success\",\"result\":{}}")
    private val refusedArea = reply(200, "{\"code\":-10403,\"message\":\"抱歉您所在地区不可观看！\"}")

    @Test fun bilibiliDistinguishesHongKongMacauTaiwanAndTaiwanOnly() {
        val all = check("bilibili", mapOf(biliZone to reply(200, "{\"code\":0,\"data\":{\"country\":\"中国\",\"province\":\"台湾\"}}"),
            bili(NetworkUnlock.BILIBILI_HKMOTW_EP) to playable, bili(NetworkUnlock.BILIBILI_TW_EP) to playable))
        assertEquals(UnlockState.Unlocked, all.state)
        assertEquals("解锁 · 港澳台", all.chip)
        val hk = check("bilibili", mapOf(biliZone to reply(200, "{\"code\":0,\"data\":{\"country\":\"香港\"}}"),
            bili(NetworkUnlock.BILIBILI_HKMOTW_EP) to playable, bili(NetworkUnlock.BILIBILI_TW_EP) to refusedArea))
        assertEquals("受限 · 仅港澳", hk.chip)
        assertEquals("HK", hk.region)
        val tw = check("bilibili", mapOf(bili(NetworkUnlock.BILIBILI_HKMOTW_EP) to refusedArea, bili(NetworkUnlock.BILIBILI_TW_EP) to playable))
        assertEquals("受限 · 仅台湾", tw.chip)
        val none = check("bilibili", mapOf(biliZone to reply(200, "{\"data\":{\"country\":\"美国\"}}"),
            bili(NetworkUnlock.BILIBILI_HKMOTW_EP) to refusedArea, bili(NetworkUnlock.BILIBILI_TW_EP) to refusedArea))
        assertEquals(UnlockState.Unavailable, none.state)
        assertEquals("US", none.region)
    }

    /* ------------------------------ Apple / Spotify / Steam ------------------------------ */

    @Test fun appleCountryCodeEndpoint() {
        val ok = check("apple", mapOf("https://gspe1-ssl.ls.apple.com/pep/gcc" to reply(200, "JP\n")))
        assertEquals(UnlockState.Unlocked, ok.state)
        assertEquals("JP", ok.region)
        assertEquals("失败 · 解析失败", check("apple", emptyMap()).chip)
    }

    @Test fun spotifySignupRedirectCarriesTheCountry() {
        val ok = check("spotify", mapOf("https://www.spotify.com/signup" to reply(302, headers = mapOf("Location" to listOf("https://www.spotify.com/hk-en/signup/")))))
        assertEquals(UnlockState.Unlocked, ok.state)
        assertEquals("HK", ok.region)
        val no = check("spotify", mapOf("https://www.spotify.com/signup" to reply(200, "Spotify is currently not available in your country.")))
        assertEquals(UnlockState.Unavailable, no.state)
    }

    @Test fun steamCountryCookieAndStoreCurrency() {
        val result = check("steam", mapOf(
            "https://store.steampowered.com/" to reply(200, headers = mapOf("Set-Cookie" to listOf("browserid=1; path=/", "steamCountry=TR%7C4a65f845; path=/; secure"))),
            "https://store.steampowered.com/api/appdetails?appids=${NetworkUnlock.STEAM_PRICED_APP}&filters=price_overview" to
                reply(200, "{\"1086940\":{\"success\":true,\"data\":{\"price_overview\":{\"currency\":\"USD\",\"final\":5999}}}}"),
        ))
        assertEquals(UnlockState.Unlocked, result.state)
        assertEquals("TR", result.region)
        assertEquals("解锁 · USD", result.chip)
    }

    /* ------------------------------ GitHub / Twitch / Hulu ------------------------------ */

    @Test fun headerRegionsForGithubTwitchHulu() {
        val gh = check("github", mapOf("https://github.com/" to reply(200, headers = mapOf("X-GitHub-Edge-Region" to listOf("japaneast")))))
        assertEquals(UnlockState.Unlocked, gh.state)
        assertEquals("JP", gh.region)
        val tw = check("twitch", mapOf("https://www.twitch.tv/" to reply(200, headers = mapOf("set-cookie" to listOf("twitch.lohp.countryCode=DE; domain=.twitch.tv")))))
        assertEquals("DE", tw.region)
        val us = check("hulu", mapOf("https://www.hulu.com/" to reply(301, headers = mapOf("location" to listOf("https://www.hulu.com/welcome"), "x-aka-user-geo" to listOf("US")))))
        assertEquals(UnlockState.Unlocked, us.state)
        val jp = check("hulu", mapOf("https://www.hulu.com/" to reply(301, headers = mapOf("x-aka-user-geo" to listOf("JP")))))
        assertEquals("不可用 · 仅限美国", jp.chip)
        assertEquals("JP", jp.region)
    }

    /* ------------------------------ budget, concurrency, exits ------------------------------ */

    @Test fun everyCheckIsBoundedByItsBudgetAndConcurrencyIsCapped() = runBlocking {
        val active = AtomicInteger()
        val peak = AtomicInteger()
        val slow = UnlockTransport { request, timeoutMs ->
            val now = active.incrementAndGet()
            peak.accumulateAndGet(now) { a, b -> maxOf(a, b) }
            try {
                // A stalled socket: blocks past the budget unless interrupted.
                Thread.sleep(maxOf(timeoutMs.toLong(), 1L) + 5_000)
                UnlockReply(200, emptyMap(), "", request.url)
            } catch (_: InterruptedException) {
                throw SocketTimeoutException("interrupted")
            } finally {
                active.decrementAndGet()
            }
        }
        val checks = (1..8).map { n -> UnlockCheck("s$n") { http -> http.get("https://example.invalid/$n"); UnlockResult("s$n", UnlockState.Unlocked) } }
        val started = System.nanoTime()
        val results = NetworkUnlock.run(slow, checks, parallel = 3, timeoutMs = 400).toList()
        val elapsed = (System.nanoTime() - started) / 1_000_000
        assertEquals(8, results.size)
        assertTrue(results.all { it.state == UnlockState.Failed && it.detail == "超时" })
        assertTrue("never more than 3 checks at once: ${peak.get()}", peak.get() <= 3)
        assertTrue("3 waves of 400 ms, not 8 × 5 s: $elapsed ms", elapsed < 4_000)
    }

    @Test fun boundedReaderStopsAtMarkerAndAtLimit() {
        val page = "a".repeat(40_000) + "\"INNERTUBE_CONTEXT_GL\":\"US\"" + "b".repeat(400_000)
        val read = SystemUnlockTransport.readBounded(ByteArrayInputStream(page.toByteArray()), 1_000_000, listOf("\"INNERTUBE_CONTEXT_GL\""), System.nanoTime() + 5_000_000_000L)
        assertTrue(read.contains("INNERTUBE_CONTEXT_GL"))
        assertTrue("stopped soon after the marker: ${read.length}", read.length < 80_000)
        assertEquals(1000, SystemUnlockTransport.readBounded(ByteArrayInputStream(page.toByteArray()), 1000, emptyList(), System.nanoTime() + 5_000_000_000L).length)
    }

    @Test fun overseasAndDomesticExitsAreReadTogether() = runBlocking {
        val http = FakeHttp(mapOf(
            "https://www.cloudflare.com/cdn-cgi/trace" to reply(200, "fl=1\nip=203.0.113.9\ncolo=NRT\nloc=JP\n"),
            "https://api.ip.sb/geoip" to reply(200, "{\"ip\":\"203.0.113.9\",\"city\":\"Tokyo\",\"region\":\"Tokyo\",\"asn_organization\":\"Example Net\"}"),
            "https://ipservice.ws.126.net/locate/api/getLocByIp" to reply(200,
                "{\"status\":200,\"result\":{\"city\":\"赣州\",\"country\":\"中国\",\"countrySymbol\":\"CN\",\"ip\":\"198.51.100.7\",\"operator\":\"电信\",\"province\":\"江西\"}}"),
        ))
        val exits = NetworkUnlock.exits(http)
        assertEquals(ExitIp("203.0.113.9", "JP", "Tokyo", "Example Net"), exits.overseas)
        assertEquals(ExitIp("198.51.100.7", "CN", "江西 赣州", "电信"), exits.domestic)
        assertTrue("both sides were asked", http.seen.any { "126.net" in it.url } && http.seen.any { "cloudflare" in it.url })
    }

    @Test fun domesticExitFallsBackToTencentAndFailuresCarryReasons() = runBlocking {
        val exits = NetworkUnlock.exits(FakeHttp(mapOf(
            "https://ipservice.ws.126.net/locate/api/getLocByIp" to fail(IOException("reset")),
            "https://r.inews.qq.com/api/ip2city" to reply(200, "{\"ret\":0,\"ip\":\"198.51.100.8\",\"country\":\"中国\",\"province\":\"广东省\",\"city\":\"深圳市\",\"isp\":\"\"}"),
        )))
        assertEquals(ExitIp("198.51.100.8", "CN", "广东省 深圳市", ""), exits.domestic)
        assertNull(exits.overseas)
        assertEquals("解析失败", exits.overseasError)
    }

    @Test fun parsingHelpers() {
        assertEquals("US", NetworkUnlock.fromIso3("USA"))
        assertEquals("HK", NetworkUnlock.fromIso3("hkg"))
        assertNull(NetworkUnlock.fromIso3("ZZZ"))
        assertEquals("HK", NetworkUnlock.pathRegion("https://www.spotify.com/hk-en/signup/"))
        assertNull(NetworkUnlock.pathRegion("https://www.spotify.com/select-your-country-region/"))
        assertEquals("United States", NetworkUnlock.jsonString("{\"countryName\":\"United\\x20States\"}", "countryName"))
        assertEquals("US", NetworkUnlock.azureRegion("westus2"))
        assertEquals(-10403L, NetworkUnlock.jsonNumber("{\"code\":-10403}", "code"))
        assertNull("not a trace", NetworkUnlock.traceField("<html>loc=JP</html>", "loc"))
        assertEquals("TW", mergeUnlockRegion("JP", UnlockResult("x", UnlockState.Unlocked, "TW")))
        assertEquals("JP", mergeUnlockRegion("JP", null))
        // Every detector belongs to a row of the existing catalogue (grouped as before).
        val rows = setOf("chatgpt", "claude", "gemini", "tiktok", "bilibili", "disney", "hulu", "netflix", "spotify", "twitch", "youtube", "apple", "github", "steam")
        assertEquals(rows, NetworkUnlock.checkIds)
    }
}
