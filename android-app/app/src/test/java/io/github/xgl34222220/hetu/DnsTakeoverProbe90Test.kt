package io.github.xgl34222220.hetu

import org.junit.Assert.*
import org.junit.Test
import java.net.InetAddress
import java.net.UnknownHostException
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.atomic.AtomicInteger

class DnsTakeoverProbe90Test {
    private val cidr = "198.18.0.0/16"
    private fun ip(value: String) = InetAddress.getByName(value)
    private fun resolve(vararg answers: String) = DnsTakeoverProbe.run(cidr, 1000) { answers.map(::ip).toTypedArray() }

    @Test fun onlyAnAnswerInTheRunningFakeRangeEstablishesCapture() {
        assertEquals(DnsTakeoverProbe.CAPTURED, DnsTakeoverProbe.verdict("198.18.23.4", cidr))
        listOf(null, "", "192.0.2.7", "2001:db8::1", "not-an-address").forEach {
            assertEquals(DnsTakeoverProbe.INCONCLUSIVE, DnsTakeoverProbe.verdict(it, cidr))
        }
    }
    @Test fun malformedRangesAndAddressesCannotEstablishCapture() {
        listOf("", "bad", "198.18.0.0/33", "198.18.0.0/-1", "300.18.0.0/16", "198.18.0.0/x").forEach {
            assertEquals(DnsTakeoverProbe.INCONCLUSIVE, DnsTakeoverProbe.verdict("198.18.23.4", it))
        }
        assertFalse(DnsTakeoverProbe.inCidr("198.18.1.999", cidr))
    }
    @Test fun aRealAddressMayBeAFilteredAnswerAndIsInconclusive() {
        val result = resolve("192.0.2.7")
        assertEquals(DnsTakeoverProbe.INCONCLUSIVE, result.verdict)
        assertTrue(result.describe().contains("暂无法判断"))
        assertFalse(result.describe().contains("没有进入核心"))
    }
    @Test fun ipv6OnlyAndEmptyAnswersRemainInconclusive() {
        assertEquals(DnsTakeoverProbe.INCONCLUSIVE, resolve("2001:db8::1").verdict)
        assertEquals(DnsTakeoverProbe.INCONCLUSIVE, resolve().verdict)
    }
    @Test fun aFakeAnswerAfterARealOrIpv6AnswerIsStillRecognized() {
        val result = resolve("2001:db8::1", "192.0.2.7", "198.18.4.5")
        assertEquals(DnsTakeoverProbe.CAPTURED, result.verdict)
        assertEquals("198.18.4.5", result.answer)
    }
    @Test fun nxdomainAndResolverFailureDoNotClaimDirectDnsOrPollution() {
        val result = DnsTakeoverProbe.run(cidr, 1000) { throw UnknownHostException("fixture") }
        assertEquals(DnsTakeoverProbe.INCONCLUSIVE, result.verdict)
        assertFalse(result.describe().contains("被污染"))
    }
    @Test fun missingFakeRangeSkipsLookupEntirely() {
        val calls = AtomicInteger()
        val result = DnsTakeoverProbe.run("", 1000) { calls.incrementAndGet(); emptyArray() }
        assertEquals(DnsTakeoverProbe.INCONCLUSIVE, result.verdict)
        assertEquals(0, calls.get())
    }
    @Test fun probeNamesUseReservedInvalidNamespaceAndAreUnique() {
        val first = resolve("198.18.1.1").name
        val second = resolve("198.18.1.1").name
        assertTrue(first.endsWith(".hetu-dns-probe.invalid"))
        assertNotEquals(first, second)
    }
    @Test fun timedOutResolversCannotAccumulateUnboundedWorkers() {
        val release = CountDownLatch(1)
        val completed = CountDownLatch(2)
        val calls = AtomicInteger()
        val lookup = DnsTakeoverProbe.Lookup {
            calls.incrementAndGet()
            while (true) {
                try { release.await(); break } catch (_: InterruptedException) { /* model netd ignoring interrupt */ }
            }
            arrayOf(ip("198.18.1.1")).also { completed.countDown() }
        }
        try {
            assertEquals(DnsTakeoverProbe.INCONCLUSIVE, DnsTakeoverProbe.run(cidr, 500, lookup).verdict)
            assertEquals(DnsTakeoverProbe.INCONCLUSIVE, DnsTakeoverProbe.run(cidr, 500, lookup).verdict)
            repeat(20) { assertEquals(DnsTakeoverProbe.INCONCLUSIVE, DnsTakeoverProbe.run(cidr, 500, lookup).verdict) }
            assertEquals(2, calls.get())
        } finally {
            release.countDown()
            assertTrue(completed.await(2, TimeUnit.SECONDS))
            val field = DnsTakeoverProbe::class.java.getDeclaredField("LOOKUPS").also { it.isAccessible = true }
            val executor = field.get(null) as ThreadPoolExecutor
            val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(2)
            while ((executor.activeCount != 0 || executor.queue.isNotEmpty()) && System.nanoTime() < deadline) Thread.yield()
            assertEquals(0, executor.activeCount)
            assertTrue(executor.queue.isEmpty())
        }
    }
    @Test fun interruptedCallerPreservesInterruptionAndDoesNotClaimCapture() {
        Thread.currentThread().interrupt()
        try {
            val result = DnsTakeoverProbe.run(cidr, 1000) { arrayOf(ip("198.18.1.1")) }
            assertEquals(DnsTakeoverProbe.INCONCLUSIVE, result.verdict)
            assertTrue(Thread.currentThread().isInterrupted)
        } finally { Thread.interrupted() }
    }
}
