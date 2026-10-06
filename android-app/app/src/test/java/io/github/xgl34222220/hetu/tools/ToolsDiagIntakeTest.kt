package io.github.xgl34222220.hetu.tools

import android.app.Application
import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import io.github.xgl34222220.hetu.HetuAppTheme
import io.github.xgl34222220.hetu.HxNav
import io.github.xgl34222220.hetu.LocalHxBlur
import io.github.xgl34222220.hetu.LocalNav
import kotlinx.coroutines.CompletableDeferred
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import org.robolectric.shadows.ShadowDialog

/** Real DiagHost requests and modal dismissal; no duplicate request-state model or network calls. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], qualifiers = "zh-rCN-w393dp-h852dp-mdpi", application = Application::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class ToolsDiagIntakeTest {
    @get:Rule val rule = createAndroidComposeRule<ComponentActivity>()
    private val copies = mutableListOf<Pair<String, String>>()
    private var pageBacks = 0

    private fun render(startup: suspend () -> String = { "" }, report: suspend () -> String = { "" }) {
        val actions = ToolsFeatureActions(
            runPreflight = { ToolsPreflight.Passed() },
            startupConfig = startup,
            diagnostics = report,
            onCopy = { label, text -> copies += label to text },
        )
        rule.setContent {
            HetuAppTheme(appearance = "light", dynamic = false) {
                CompositionLocalProvider(LocalNav provides HxNav(), LocalHxBlur provides false) {
                    Box(Modifier.fillMaxSize()) {
                        ToolsFeaturePage(ToolsDestination.Diag, actions, onBack = { pageBacks++ })
                    }
                }
            }
        }
        settle()
    }

    private fun settle() {
        rule.mainClock.advanceTimeBy(1000)
        rule.waitForIdle()
    }

    private fun open(label: String) {
        rule.onNodeWithText(label).performClick()
        settle()
        rule.onNodeWithText("复制").assertIsNotEnabled()
    }

    @Suppress("DEPRECATION")
    private fun closeSheet() {
        // Dispatch through the actual Material3 dialog's back handler, preserving DiagHost.
        rule.runOnUiThread { checkNotNull(ShadowDialog.getLatestDialog()).onBackPressed() }
        settle()
        rule.onNodeWithText("复制").assertDoesNotExist()
        assertEquals(0, pageBacks)
    }

    @Test fun lateStartupSuccessCannotReplaceAReopenedPendingRequest() {
        val first = CompletableDeferred<String>()
        val second = CompletableDeferred<String>()
        var reads = 0
        render(startup = { if (++reads == 1) first.await() else second.await() })
        open("启动配置")
        rule.runOnIdle { assertEquals(1, reads) }
        closeSheet()
        open("启动配置")
        rule.runOnIdle { assertEquals(2, reads); first.complete("stale-request: true") }
        settle()
        rule.onNodeWithText("stale-request: true").assertDoesNotExist()
        rule.onNodeWithText("复制").assertIsNotEnabled()

        rule.runOnIdle { second.complete("latest-request: true") }
        settle()
        rule.onNodeWithText("latest-request: true").assertIsDisplayed()
        rule.onNodeWithText("复制").assertIsEnabled().performClick()
        rule.runOnIdle { assertEquals(listOf("启动配置" to "latest-request: true"), copies) }
    }

    @Test fun lateReportFailureCannotReplaceAReopenedPendingRequest() {
        val first = CompletableDeferred<String>()
        val second = CompletableDeferred<String>()
        var reads = 0
        render(report = { if (++reads == 1) first.await() else second.await() })
        open("消息与网络诊断")
        rule.runOnIdle { assertEquals(1, reads) }
        closeSheet()
        open("消息与网络诊断")
        rule.runOnIdle { assertEquals(2, reads); first.completeExceptionally(IllegalStateException("stale-read-failure")) }
        settle()
        rule.onNodeWithText("stale-read-failure").assertDoesNotExist()
        rule.onNodeWithText("复制").assertIsNotEnabled()

        rule.runOnIdle { second.complete("current-diagnostics") }
        settle()
        rule.onNodeWithText("current-diagnostics").assertIsDisplayed()
        rule.onNodeWithText("复制").assertIsEnabled().performClick()
        rule.runOnIdle { assertEquals(listOf("诊断信息" to "current-diagnostics"), copies) }
    }

    @Test fun dismissedSheetsDoNotReturnWhenTheirReadsSucceedOrFail() {
        val startup = CompletableDeferred<String>()
        val report = CompletableDeferred<String>()
        var startupReads = 0
        var reportReads = 0
        render(startup = { startupReads++; startup.await() }, report = { reportReads++; report.await() })
        open("启动配置")
        rule.runOnIdle { assertEquals(1, startupReads) }
        closeSheet()
        rule.runOnIdle { startup.complete("closed-startup-result") }
        settle()
        rule.onNodeWithText("closed-startup-result").assertDoesNotExist()
        rule.onNodeWithText("复制").assertDoesNotExist()

        open("消息与网络诊断")
        rule.runOnIdle { assertEquals(1, reportReads) }
        closeSheet()
        rule.runOnIdle { report.completeExceptionally(IllegalStateException("closed-report-failure")) }
        settle()
        rule.onNodeWithText("closed-report-failure").assertDoesNotExist()
        rule.onNodeWithText("复制").assertDoesNotExist()
        rule.onNodeWithText("诊断与维护").assertIsDisplayed()
        rule.runOnIdle { assertEquals(emptyList<Pair<String, String>>(), copies) }
    }
}
