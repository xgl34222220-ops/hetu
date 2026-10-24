package io.github.xgl34222220.hetu

import io.github.xgl34222220.hetu.home.HomeConnectionHealth
import io.github.xgl34222220.hetu.home.HomeConnectionObservation
import io.github.xgl34222220.hetu.home.HomeGlyphMode
import io.github.xgl34222220.hetu.home.HomeStatus
import io.github.xgl34222220.hetu.home.HomeUiState
import io.github.xgl34222220.hetu.home.homeEgressFault
import io.github.xgl34222220.hetu.home.homeHeroGlyphMode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * 6.0.0 (field report: only foreign sites fail, node delays are fine, stop/start does not help). The Root
 * watchdog's verdict for a request sent the way apps send it reaches the home card; the core's own policy
 * listener answering ("出口已验证") no longer hides a failing app path.
 */
class ForeignOutage600Test {
    private val ready = HomeConnectionObservation(controllerReady = true, takeoverHealthy = true, egressVerified = true)

    @Test fun failingVerdictsHaveASentenceAndHealthyOnesDoNot() {
        for (verdict in listOf("fakeip-lost", "dataplane-down", "route-down", "proxy-down")) {
            assertTrue(verdict, homeEgressFault(verdict).startsWith("外网异常："))
        }
        for (verdict in listOf("", "ok", "offline", "skip", "unknown-future-verdict")) {
            assertEquals(verdict, "", homeEgressFault(verdict))
        }
    }

    @Test fun aFailingVerdictOutranksTheListenerCheck() {
        assertEquals(HomeConnectionHealth.Verified, ready.health)
        assertEquals(HomeConnectionHealth.Verified, ready.copy(watchdogVerdict = "ok").health)
        val failing = ready.copy(watchdogVerdict = "fakeip-lost")
        assertEquals(HomeConnectionHealth.LocalReady, failing.health)
        assertEquals("外网异常：核心的假 IP 记录失效，河图正在自动重启核心", failing.egressFault)
        // Faults that already explain themselves keep their own state.
        assertEquals(HomeConnectionHealth.TakeoverDegraded, failing.copy(takeoverHealthy = false).health)
        assertEquals(HomeConnectionHealth.ControllerUnavailable, failing.copy(controllerReadFailed = true).health)
    }

    @Test fun theRunningGlyphAsksForAttentionWhileTheWatchdogSeesForeignFailures() {
        val running = HomeUiState(status = HomeStatus.Running(uptimeSeconds = 60), connection = ready)
        assertEquals(HomeGlyphMode.On, running.homeHeroGlyphMode())
        assertEquals(HomeGlyphMode.Attention, running.copy(connection = ready.copy(watchdogVerdict = "route-down")).homeHeroGlyphMode())
        assertEquals(HomeGlyphMode.On, running.copy(connection = ready.copy(watchdogVerdict = "ok")).homeHeroGlyphMode())
    }

    @Test fun theWatchdogUsesTheSameListenerPortAsTheCoreConfig() {
        val script = File("src/main/assets/hetu-root.sh").readText()
        val offset = Regex("(?m)^WD_DP_EGRESS_OFFSET=(\\d+)$").find(script)?.groupValues?.get(1)?.toInt()
        assertEquals(MihomoStartupConfig.EGRESS_PROBE_PORT_OFFSET, offset)
        assertEquals(29090 + MihomoStartupConfig.EGRESS_PROBE_PORT_OFFSET, MihomoStartupConfig.egressProbePort(29090))
    }

    @Test fun theMihomoConfigCheckIsExecedSoItsTimeoutReachesTheCore() {
        val source = File("src/main/java/io/github/xgl34222220/hetu/RootProxyManager.java").readText()
        assertTrue(source.contains("\" && exec \"+RootBridge.quote(BIN)+\" -t -d \"+RootBridge.quote(ROOT+\"/run\")"))
    }
}
