package io.github.xgl34222220.hetu

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Root monitor daemon (BoxProxy gap #4/#5): the exported policy, the monitor-status reply, the App
 * hand-off validation and the multi-dimension traffic history fed by the daemon's batches
 * (exactly-once ingestion, top lists per dimension and range, totals, retention).
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
class DaemonTrafficHistory105Test {
    private val context: Application get() = ApplicationProvider.getApplicationContext()
    private val prefs get() = context.getSharedPreferences("hetu", 0)

    @Before fun reset() {
        prefs.edit().clear().putBoolean("proxyApiHistoryEnabled", true).commit()
        runBlocking { ProxyApiHistoryStore.clear(context) }
    }

    @Test fun policyCarriesRulesAndDropsLineBreakingValues() {
        prefs.edit().putBoolean("networkMatchEnabled", true).putBoolean("networkMatchMobile", true)
            .putStringSet("networkMatchSsids", setOf("Home", "Evil\nTRAFFIC=1", "家"))
            .putStringSet("networkMatchBssids", setOf("AA:BB:CC:DD:EE:01"))
            .putStringSet("networkMatchSimSlots", setOf("2", "9"))
            .putStringSet("networkMatchMccMnc", setOf("460-00", "x"))
            .putString("networkMatchAction", "run_script:on-home.sh").putString("networkUnmatchAction", "switch_config:../etc")
            .putInt("proxyControllerPort", 29091).putString("proxyControllerSecret", "s3cret").commit()
        val body = DaemonMonitorBridge.policyBody(prefs)
        val lines = body.lines()
        assertTrue(lines.first().matches(Regex("GEN=[0-9a-f]{8}")))
        assertTrue("TRAFFIC=1" in lines); assertTrue("API_PORT=29091" in lines); assertTrue("API_SECRET=s3cret" in lines)
        assertTrue("NETMATCH=1" in lines); assertTrue("MOBILE=1" in lines)
        assertEquals(listOf("SSID=Home", "SSID=家"), lines.filter { it.startsWith("SSID=") })
        assertTrue("BSSID=aa:bb:cc:dd:ee:01" in lines)
        assertEquals(listOf("SLOT=2"), lines.filter { it.startsWith("SLOT=") })
        assertEquals(listOf("MCCMNC=46000"), lines.filter { it.startsWith("MCCMNC=") })
        assertTrue("MATCH_ACTION=run_script:on-home.sh" in lines)
        assertTrue("a malformed target acts as none", "UNMATCH_ACTION=none" in lines)
        assertEquals("one line per key: an SSID cannot inject a switch", 1, lines.count { it.startsWith("TRAFFIC=") })
        assertTrue(DaemonMonitorPolicy.wanted(body))
        assertEquals(lines.first().removePrefix("GEN="), DaemonMonitorPolicy.generation(body))
        assertEquals("same input, same generation", body, DaemonMonitorBridge.policyBody(prefs))
        val install = DaemonMonitorPolicy.installCommand(body)
        assertTrue(install.contains("chmod 600") && install.contains("chown 0:0") && install.endsWith("'/data/adb/hetu/policy/monitor'"))

        // A secret that would break a line disables traffic instead of polling unauthenticated.
        prefs.edit().putString("proxyControllerSecret", "a\nb").commit()
        val unsafe = DaemonMonitorBridge.policyBody(prefs).lines()
        assertTrue("TRAFFIC=0" in unsafe); assertTrue("API_SECRET=" in unsafe)
        // Nothing enabled: no policy file, so start spawns no monitor.
        prefs.edit().putBoolean("networkMatchEnabled", false).putBoolean("proxyApiHistoryEnabled", false).commit()
        val off = DaemonMonitorBridge.policyBody(prefs)
        assertFalse(DaemonMonitorPolicy.wanted(off))
        assertEquals("rm -f '/data/adb/hetu/policy/monitor'", DaemonMonitorPolicy.installCommand(off))
    }

    @Test fun statusAndHandoffAreValidatedBeforeTheAppDefersOrActs() {
        val alive = DaemonMonitorPolicy.parseStatus("noise\n{\"ok\":true,\"alive\":true,\"pid\":42,\"netmatch\":true,\"traffic\":false,\"policy\":\"1a2b3c4d\",\"lastAction\":\"handoff=stop id=1-2-3\"}\n")
        assertTrue(alive.ownsNetworkMatch()); assertFalse(alive.traffic)
        assertEquals("1a2b3c4d", alive.policy); assertEquals("handoff=stop id=1-2-3", alive.lastAction)
        assertFalse(DaemonMonitorPolicy.parseStatus("{\"ok\":true,\"alive\":true,\"netmatch\":false}").ownsNetworkMatch())
        assertFalse(DaemonMonitorPolicy.parseStatus("{\"ok\":true,\"alive\":false,\"netmatch\":true}").ownsNetworkMatch())
        assertFalse("an old script without monitor-status keeps the App in charge",
            DaemonMonitorPolicy.parseStatus("{\"ok\":false,\"message\":\"未知 Root 代理操作\"}").ownsNetworkMatch())
        assertFalse(DaemonMonitorPolicy.parseStatus(null).alive)

        val now = 1_000_000L
        val stop = DaemonMonitorPolicy.parseHandoff("stop", "", "移动数据", "16748-250447-2", now - 1_000L, now)
        assertNotNull(stop); assertEquals("stop", stop!!.kind)
        val switch = DaemonMonitorPolicy.parseHandoff("switch", "Away.yaml", "Wi‑Fi · Cafe", "16748-250447-3", now, now)
        assertEquals("Away.yaml", switch!!.target)
        assertNull(DaemonMonitorPolicy.parseHandoff("switch", "../x.yaml", "", "1-2-3", now, now))
        assertNull(DaemonMonitorPolicy.parseHandoff("start", "", "", "1-2-3", now, now))
        assertNull(DaemonMonitorPolicy.parseHandoff("stop", "", "", "bad id", now, now))
        assertNull("stale hand-off", DaemonMonitorPolicy.parseHandoff("stop", "", "", "1-2-3", now - 61_000L, now))
        assertNull(DaemonMonitorPolicy.parseHandoff("stop", "", "", "1-2-3", 0L, now))
        assertEquals("control characters never reach a preference", "", DaemonMonitorPolicy.parseHandoff("stop", "", "a\nb", "1-2-3", now, now)!!.label)
    }

    private fun batch(id: String, vararg minutes: Pair<Long, String>): DaemonMonitorPolicy.Batch {
        val text = StringBuilder("HETU-TRAFFIC\t1\t$id\n")
        minutes.forEach { (minute, rows) -> text.append("M\t$minute\n").append(rows) }
        return DaemonMonitorPolicy.parseBatch(text.toString())!!
    }

    @Test fun batchesParseStrictlyAndIngestExactlyOnce() {
        val now = System.currentTimeMillis()
        val m1 = now / 60_000 * 60 - 120
        val m2 = m1 + 60
        val parsed = DaemonMonitorPolicy.parseBatch("HETU-TRAFFIC\t1\t1760000000-77\nM\t$m1\nT\t600\t3000\n" +
            "B\tapp\tcom.old\t100\t1000\t0\nB\thost\t\t5\t5\t1\nB\tbogus\tx\t1\t1\t1\nB\tip\t1.2.3.4\t-5\t1\t1\nM\t61\nB\tapp\tx\t1\t1\t1\ngarbage\n")!!
        assertEquals("1760000000-77", parsed.id)
        assertEquals(2, parsed.rows.size)
        assertEquals("-", parsed.rows[1].key)
        assertEquals(5, parsed.rejected)
        assertEquals(600L, parsed.totals[m1]!![0])
        assertNull(DaemonMonitorPolicy.parseBatch("{\"ok\":false}"))
        assertNull(DaemonMonitorPolicy.parseBatch(""))

        val first = batch("1760000000-1", m1 to "T\t600\t3000\nB\tapp\tcom.a\t100\t1000\t1\nB\toutbound\tNodeA\t100\t1000\t1\nB\trule\tMatch\t100\t1000\t1\n",
            m2 to "T\t50\t60\nB\tapp\tcom.a\t10\t20\t0\nB\tapp\tuid:10123\t300\t5\t2\nB\tip\t198.51.100.7\t300\t5\t2\n")
        runBlocking {
            assertEquals(6, ProxyApiHistoryStore.ingestDaemonBatch(context, first, now))
            assertEquals("the same batch again (crash before ack) is skipped", 0, ProxyApiHistoryStore.ingestDaemonBatch(context, first, now))
            val apps = ProxyApiHistoryStore.dimensionRanking(context, "app", "traffic", now - 3_600_000L)
            assertEquals(listOf("com.a", "uid:10123"), apps.map { it.name })
            assertEquals(TrafficRank("com.a", 1, 110, 1020), apps[0])
            val byCount = ProxyApiHistoryStore.dimensionRanking(context, "app", "count", now - 3_600_000L)
            assertEquals("uid:10123", byCount[0].name)
            assertEquals(listOf("NodeA"), ProxyApiHistoryStore.dimensionRanking(context, "outbound", "traffic", now - 3_600_000L).map { it.name })
            assertEquals(listOf("198.51.100.7"), ProxyApiHistoryStore.dimensionRanking(context, "ip", "traffic", now - 3_600_000L).map { it.name })
            assertTrue("route is not a daemon dimension", ProxyApiHistoryStore.dimensionRanking(context, "route", "traffic", 0L).isEmpty())
            assertEquals(650L to 3060L, ProxyApiHistoryStore.rangeTotals(context, now - 3_600_000L))
            assertEquals("the range bounds the window", 50L to 60L, ProxyApiHistoryStore.rangeTotals(context, m2 * 1000))
            // A later batch merges into existing minutes.
            ProxyApiHistoryStore.ingestDaemonBatch(context, batch("1760000060-2", m2 to "T\t1\t1\nB\tapp\tcom.a\t1\t1\t1\n"), now)
            assertEquals(TrafficRank("com.a", 2, 111, 1021), ProxyApiHistoryStore.dimensionRanking(context, "app", "traffic", now - 3_600_000L)[0])
        }
    }

    @Test fun retentionAndDisabledHistoryBoundWhatIsKept() {
        val now = System.currentTimeMillis()
        val old = (now - 3 * 86_400_000L) / 60_000 * 60
        val recent = now / 60_000 * 60 - 60
        prefs.edit().putInt("proxyApiHistoryRetentionDays", 1).commit()
        runBlocking {
            ProxyApiHistoryStore.ingestDaemonBatch(context, batch("1-1", old to "T\t9\t9\nB\thost\told.example\t9\t9\t1\n",
                recent to "T\t1\t2\nB\thost\tnew.example\t1\t2\t1\n"), now)
            assertEquals(listOf("new.example"), ProxyApiHistoryStore.dimensionRanking(context, "host", "traffic", 0L).map { it.name })
            assertEquals(1L to 2L, ProxyApiHistoryStore.rangeTotals(context, 0L))
            prefs.edit().putBoolean("proxyApiHistoryEnabled", false).commit()
            assertEquals(0, ProxyApiHistoryStore.ingestDaemonBatch(context, batch("1-2", recent to "B\thost\tx\t1\t1\t1\n"), now))
            assertTrue(ProxyApiHistoryStore.dimensionRanking(context, "host", "traffic", 0L).isEmpty())
            prefs.edit().putBoolean("proxyApiHistoryEnabled", true).commit()
            ProxyApiHistoryStore.clear(context)
            assertTrue(ProxyApiHistoryStore.dimensionRanking(context, "host", "traffic", 0L).isEmpty())
            assertEquals(0L to 0L, ProxyApiHistoryStore.rangeTotals(context, 0L))
        }
    }
}
