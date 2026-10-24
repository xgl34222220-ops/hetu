package io.github.xgl34222220.hetu

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** 7.0.0: edgetunnel (Cloudflare Worker) nodes cannot reach Cloudflare test URLs; gstatic is the fallback. */
class DelayUrlChoice700Test {
    private val gstatic = "https://www.gstatic.com/generate_204"
    private val cf = "https://cp.cloudflare.com/generate_204"
    private val defaults = arrayOf(gstatic, cf)

    @Test fun recognisesCloudflareTestTargets() {
        listOf(cf, "http://cp.cloudflare.com/", "https://speed.cloudflare.com/__down", "https://CP.CLOUDFLARE.COM./generate_204",
            "http://1.1.1.1/generate_204", "https://1.0.0.1", "http://[2606:4700:4700::1111]/generate_204")
            .forEach { assertTrue(it, DelayUrlChoice700.isCloudflareTarget(it)) }
        listOf(gstatic, "https://notcloudflare.com/x", "https://cloudflare.com.evil.net/", "http://1.1.1.2/", "", "not a url")
            .forEach { assertFalse(it, DelayUrlChoice700.isCloudflareTarget(it)) }
    }

    @Test fun cloudflareGroupUrlGetsGstaticAfterItWithoutCustomUrl() {
        assertEquals(listOf(cf, gstatic), DelayUrlChoice700.delayUrls("", cf, defaults))
        assertEquals(listOf("http://1.1.1.1/generate_204", gstatic), DelayUrlChoice700.delayUrls(null, "http://1.1.1.1/generate_204", defaults))
    }

    @Test fun nonCloudflareAndCustomUrlsAreUnchanged() {
        assertEquals(listOf("https://example.com/204"), DelayUrlChoice700.delayUrls("", "https://example.com/204", defaults))
        assertEquals(listOf("https://my.custom/204", cf), DelayUrlChoice700.delayUrls("https://my.custom/204", cf, defaults))
        assertEquals(listOf(gstatic, cf), DelayUrlChoice700.delayUrls("", "", defaults))
    }

    @Test fun retryUsesGstaticInsteadOfACloudflareGroupUrl() {
        assertEquals(gstatic, DelayUrlChoice700.retryUrl("", cf, gstatic))
        assertEquals("https://example.com/204", DelayUrlChoice700.retryUrl("", "https://example.com/204", gstatic))
        assertEquals("https://my.custom/204", DelayUrlChoice700.retryUrl("https://my.custom/204", cf, gstatic))
        assertEquals(gstatic, DelayUrlChoice700.retryUrl(null, "", gstatic))
    }
}
