package io.github.xgl34222220.hetu

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** 测速历史: records, estimate flags, no-response hints, persistence, retention cap, delete and clear. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
class SpeedTestHistory103Test {
    private val context: Application get() = ApplicationProvider.getApplicationContext()

    @Before fun reset() { SpeedTestHistory.clear(context) }

    @Test fun latencyRunKeepsPerNodeDelaysTimeoutsAndEstimatedTraffic() {
        val record = SpeedTestHistory.latencyRecord("策略组 Proxy", listOf("hk", "jp", "us", "sg", "hk"),
            mapOf("hk" to 120L, "jp" to 45L, "us" to -1L), 1_000L)
        assertEquals(SpeedRunKind.Latency, record.kind)
        assertEquals(listOf("hk", "jp", "us", "sg"), record.nodes.map { it.name })
        assertEquals(2, record.ok)
        assertEquals(1, record.timeouts)
        assertEquals("sg never answered: no response, not a timeout", 1, record.noResponse)
        assertEquals("jp", record.best?.name)
        assertTrue("controller probes report no bytes: always 估算", record.estimated)
        assertEquals(2 * SpeedTestHistory.PROBE_DOWN_BYTES + SpeedTestHistory.TIMEOUT_DOWN_BYTES, record.bytesDown)
        assertEquals(2 * SpeedTestHistory.PROBE_UP_BYTES + SpeedTestHistory.TIMEOUT_UP_BYTES, record.bytesUp)
        assertTrue(record.hint.contains("1 个节点没有返回结果"))
        assertTrue(SpeedTestHistory.summary(record).startsWith("2/4 可用 · 1 超时 · 1 无响应 · 最快 jp 45 ms"))
        assertTrue(SpeedTestHistory.trafficText(record).endsWith("（估算）"))
        assertEquals("超时", SpeedTestHistory.delayText(record.nodes[2]))
        assertEquals("无响应", SpeedTestHistory.delayText(record.nodes[3]))
    }

    @Test fun silentAndAllTimeoutRunsExplainWhatToCheck() {
        val silent = SpeedTestHistory.latencyRecord("全部节点", listOf("a", "b"), emptyMap(), 1L)
        assertTrue(silent.hint.startsWith("所有节点均无响应"))
        assertEquals(0L, silent.bytesDown)
        val timeouts = SpeedTestHistory.latencyRecord("全部节点", listOf("a", "b"), mapOf("a" to -1L, "b" to -1L), 1L)
        assertTrue(timeouts.hint.startsWith("所有节点均超时"))
        assertEquals("", SpeedTestHistory.latencyRecord("g", listOf("a"), mapOf("a" to 9L), 1L).hint)
    }

    @Test fun bandwidthRunMarksIncompleteOrSmallSamplesAsEstimated() {
        val full = SpeedTestHistory.bandwidthRecord("网速测试", 30L, 95.5 to 40_000_000L, 20.0 to 9_000_000L, 0L, 0L, null, 5L)
        assertFalse(full.estimated)
        assertEquals(40_000_000L, full.bytesDown)
        assertEquals("", full.hint)
        assertEquals("本次流量 ↓ ${HxFormat.bytes(40_000_000L)} ↑ ${HxFormat.bytes(9_000_000L)}", SpeedTestHistory.trafficText(full))
        val stopped = SpeedTestHistory.bandwidthRecord("网速测试", 30L, 50.0 to 30_000_000L, null, 30_000_000L, 1_000_000L, null, 6L)
        assertTrue(stopped.estimated)
        assertNull(stopped.upMbps)
        assertEquals("live counter of the unfinished phase", 1_000_000L, stopped.bytesUp)
        assertTrue(stopped.hint.contains("未完成"))
        val small = SpeedTestHistory.bandwidthRecord("网速测试", 30L, 1.0 to 500_000L, 0.5 to 100_000L, 0L, 0L, null, 7L)
        assertTrue(small.estimated)
        assertTrue(small.hint.contains("样本过少"))
        val dead = SpeedTestHistory.bandwidthRecord("网速测试", -1L, null, null, 0L, 0L, null, 8L)
        assertTrue(dead.hint.contains("没有响应"))
        val failed = SpeedTestHistory.bandwidthRecord("网速测试", 20L, null, null, 0L, 0L, "连接被重置", 9L)
        assertEquals("连接被重置", failed.hint)
        assertTrue(failed.estimated)
    }

    @Test fun historyPersistsNewestFirstWithCapDeleteAndClear() {
        repeat(SpeedTestHistory.MAX_RUNS + 5) { i ->
            SpeedTestHistory.add(context, SpeedTestHistory.latencyRecord("g$i", listOf("a"), mapOf("a" to (i + 1).toLong()), 10_000L + i))
        }
        val runs = SpeedTestHistory.load(context)
        assertEquals(SpeedTestHistory.MAX_RUNS, runs.size)
        assertEquals("g${SpeedTestHistory.MAX_RUNS + 4}", runs.first().title)
        assertEquals("oldest runs drop out", "g5", runs.last().title)
        assertEquals((SpeedTestHistory.MAX_RUNS + 5).toLong(), runs.first().nodes.single().delayMs)
        SpeedTestHistory.delete(context, runs.first().id)
        assertEquals(SpeedTestHistory.MAX_RUNS - 1, SpeedTestHistory.load(context).size)
        assertFalse(SpeedTestHistory.load(context).any { it.id == runs.first().id })
        // Same timestamp twice keeps both runs (distinct ids).
        SpeedTestHistory.clear(context)
        val bw = SpeedTestHistory.bandwidthRecord("网速测试", 12L, 10.0 to 9_000_000L, 2.0 to 3_000_000L, 0L, 0L, null, 77L)
        SpeedTestHistory.add(context, bw); SpeedTestHistory.add(context, bw)
        val two = SpeedTestHistory.load(context)
        assertEquals(2, two.size)
        assertEquals(2, two.map { it.id }.toSet().size)
        assertEquals(10.0, two.first().downMbps!!, 0.0001)
        assertEquals(12L, two.first().latencyMs)
        SpeedTestHistory.clear(context)
        assertTrue(SpeedTestHistory.load(context).isEmpty())
        assertTrue("damaged data is ignored, not a crash", SpeedTestHistory.decode("{not json").isEmpty())
    }

    @Test fun historyLivesOutsideTheSettingsPreferences() {
        SpeedTestHistory.add(context, SpeedTestHistory.latencyRecord("g", listOf("a"), mapOf("a" to 5L), 1L))
        val settings = context.getSharedPreferences("hetu", 0).all.keys
        assertFalse(settings.any { it.contains("runsV1") })
        assertEquals(1, SpeedTestHistory.load(context).size)
    }
}
