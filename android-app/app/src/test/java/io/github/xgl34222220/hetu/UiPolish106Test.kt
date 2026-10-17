package io.github.xgl34222220.hetu

import android.app.Application
import io.github.xgl34222220.hetu.home.HomeConnectionObservation
import io.github.xgl34222220.hetu.home.HomeGlyphMode
import io.github.xgl34222220.hetu.home.HomeStatus
import io.github.xgl34222220.hetu.home.HomeUiState
import io.github.xgl34222220.hetu.home.homeHeroGlyphMode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** 2026-10 UI polish: startup-config masking (display only) and the pending-restart hero glyph. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
class UiPolish106Test {
    @Test fun startupViewerHidesSubscriptionQueriesAndSecretsButNothingElse() {
        val text = "mixed-port: 7890\nsecret: abc123\n  secret: \"q w\" # note\n" +
            "proxy-providers:\n  a: {url: \"https://sub.example.com/api/v1/client?token=T0K&flag=1\", interval: 3600}\n" +
            "  b:\n    url: https://sub.example.org/s/path?x=1\n    path: ./a.yaml\n"
        val masked = maskStartupSecrets(text)
        assertFalse(masked.contains("abc123"))
        assertFalse(masked.contains("q w"))
        assertFalse(masked.contains("T0K"))
        assertFalse(masked.contains("x=1"))
        assertTrue(masked.contains("secret: ••••••\n"))
        assertTrue(masked.contains("secret: \"••••••\" # note"))
        assertTrue(masked.contains("url: \"https://sub.example.com/api/v1/client?••••••\", interval: 3600}"))
        assertTrue(masked.contains("url: https://sub.example.org/s/path?••••••\n"))
        assertTrue(masked.contains("mixed-port: 7890\n") && masked.contains("path: ./a.yaml\n"))
        // Nothing to hide: the viewer shows the original bytes.
        val plain = "mode: rule\r\nexternal-controller: 127.0.0.1:9090\r\nrules: [MATCH,DIRECT]\r\n"
        assertEquals(plain, maskStartupSecrets(plain))
    }

    @Test fun pendingRestartKeepsTheRingOnUnlessTheConnectionIsReallyUnhealthy() {
        val pending = HomeUiState(status = HomeStatus.PendingRestart(600))
        assertEquals(HomeGlyphMode.On, pending.homeHeroGlyphMode())
        val degraded = pending.copy(connection = HomeConnectionObservation(controllerReady = true, takeoverHealthy = false))
        assertEquals(HomeGlyphMode.Attention, degraded.homeHeroGlyphMode())
        // Running keeps its stricter reading.
        assertEquals(HomeGlyphMode.Unconfirmed, HomeUiState(status = HomeStatus.Running(600)).homeHeroGlyphMode())
    }
}
