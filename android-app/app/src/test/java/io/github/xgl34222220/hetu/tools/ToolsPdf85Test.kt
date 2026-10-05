package io.github.xgl34222220.hetu.tools

import android.app.Application
import android.graphics.Bitmap
import android.graphics.Canvas
import android.view.View
import android.view.Gravity
import android.view.WindowManager
import android.view.inspector.WindowInspector
import androidx.activity.ComponentActivity
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.hasScrollToIndexAction
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasAnyDescendant
import androidx.compose.ui.test.hasAnySibling
import androidx.compose.ui.test.hasText
import androidx.test.core.app.ApplicationProvider
import java.io.File
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode


import io.github.xgl34222220.hetu.*
/** Actual current production tools components and real overlay windows on offline fixture states.
 * Images are layout evidence only; no network/Root state is injected into the installed app. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], qualifiers = "zh-rCN-w393dp-h852dp-mdpi", application = Application::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class ToolsPdf85Test {
    @get:Rule val rule = createAndroidComposeRule<ComponentActivity>()
    private fun snapshotAfter(name: String, content: @Composable () -> Unit, afterSet: () -> Unit) {
        rule.setContent { HetuAppTheme(appearance = "light", dynamic = false) {
            CompositionLocalProvider(LocalNav provides HxNav(), LocalHxBlur provides false) {
                Box(Modifier.fillMaxSize()) { content() }
            }
        } }
        rule.waitForIdle()
        rule.mainClock.advanceTimeBy(1000)
        rule.waitForIdle()
        afterSet()
        rule.waitForIdle()
        rule.mainClock.advanceTimeBy(350)
        rule.waitForIdle()
        rule.runOnIdle {
            val decor = rule.activity.window.decorView
            assertTrue("Window must be laid out", decor.width > 0 && decor.height > 0)
            val bitmap = Bitmap.createBitmap(decor.width, decor.height, Bitmap.Config.ARGB_8888)
            val canvas = Canvas(bitmap)
            val roots = WindowInspector.getGlobalWindowViews().filter { it.width > 0 && it.height > 0 && it.isShown }
            val ordered = buildList {
                if (roots.none { it === decor }) add(decor)
                addAll(roots)
            }
            val drawn = HashSet<View>()
            ordered.forEach { root ->
                if (!drawn.add(root)) return@forEach
                val location = IntArray(2)
                root.getLocationOnScreen(location)
                val params = root.layoutParams as? WindowManager.LayoutParams
                // Match the real WM placement/dimming, as the full state capture does.
                // Robolectric otherwise reports a separate dialog decor at (0, 0).
                if (root !== decor && params != null) {
                    val placed = android.graphics.Rect()
                    Gravity.apply(params.gravity, root.width, root.height,
                        android.graphics.Rect(0, 0, decor.width, decor.height), params.x, params.y, placed)
                    location[0] = placed.left
                    location[1] = placed.top
                    if ((params.flags and WindowManager.LayoutParams.FLAG_DIM_BEHIND) != 0) {
                        canvas.drawColor(android.graphics.Color.argb((params.dimAmount * 255).toInt(), 0, 0, 0))
                    }
                }
                canvas.save()
                canvas.translate(location[0].toFloat(), location[1].toFloat())
                root.draw(canvas)
                canvas.restore()
            }
            val file = File(System.getProperty("hetu.qa.output", "build/outputs/pdf85"), "$name.png")
            file.parentFile!!.mkdirs()
            file.outputStream().use { assertTrue(bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)) }
            bitmap.recycle()
            assertTrue("Screenshot must contain bytes", file.length() > 0)
        }
    }
    @Test fun page01() = snapshotAfter("03A-001-current", { Pdf85C01ToolsExpanded() }) {}
    @Test fun page02() = snapshotAfter("03A-002-current", { Pdf85C02ToolsCollapsed() }) {}
    @Test fun page03() = snapshotAfter("03A-003-current", { Pdf85C03ToolsSearch() }) {}
    @Test fun page04() = snapshotAfter("03A-004-current", { Pdf85C04Configs() }) {}
    @Test fun page05() = snapshotAfter("03A-005-current", { Pdf85C05AddSubscription() }) {}
    @Test fun page06() = snapshotAfter("03A-006-current", { Pdf85C06MenuCurrent() }) {}
    @Test fun page07() = snapshotAfter("03A-007-current", { Pdf85C07MenuOther() }) {}
    @Test fun page08() = snapshotAfter("03A-008-current", { Pdf85C08MenuBundled() }) {}
    @Test fun page09() = snapshotAfter("03A-009-current", { Pdf85C09Rename() }) {}
    @Test fun page10() = snapshotAfter("03A-010-current", { Pdf85C10DeleteConfig() }) {}
    @Test fun page11() = snapshotAfter("03A-011-current", { Pdf85C11DeleteSubscription() }) {}
    @Test fun page12() = snapshotAfter("03A-012-current", { Pdf85C12ImportDiscard() }) {}
    @Test fun page13() = snapshotAfter("03A-013-current", { Pdf85C13Outline() }) {}
    @Test fun page14() = snapshotAfter("03A-014-current", { Pdf85C14SaveConflict() }) {}
    @Test fun page15() = snapshotAfter("03A-015-current", { Pdf85C15Invalid() }) {}
    @Test fun page16() = snapshotAfter("03A-016-current", { Pdf85C16LoadFailed() }) {}
    @Test fun page17() = snapshotAfter("03A-017-current", { Pdf85C17SourceChanged() }) {}
    @Test fun page18() = snapshotAfter("03A-018-current", { Pdf85C18ConfirmReload() }) {}
    @Test fun page19() = snapshotAfter("03A-019-current", { Pdf85C19ConfirmDiscard() }) {}
    @Test fun page20() = snapshotAfter("03A-020-current", { Pdf85C20ImportUrlInvalid() }) {}
    @Test fun page21() = snapshotAfter("03A-021-current", { Pdf85C21AddNameEmpty() }) {}
    @Test fun page22() = snapshotAfter("03A-022-current", { Pdf85C22AddUrlInvalid() }) {}
    @Test fun page23() = snapshotAfter("03A-023-current", { Pdf85C23EditUrlInvalid() }) {}
    @Test fun page24() = snapshotAfter("03A-024-current", { Pdf85C24ImportLink() }) {}
    @Test fun page25() = snapshotAfter("03A-025-current", { Pdf85C25Editor() }) {}
    @Test fun page26() = snapshotAfter("03A-026-current", { Pdf85C26EditSubscription() }) {}
    @Test fun page27() = snapshotAfter("03A-027-current", { Pdf85C27ImportFile() }) {}
    @Test fun page28() = snapshotAfter("03A-028-current", { Pdf85C28AppsBlacklist() }) {}
    @Test fun page29() = snapshotAfter("03A-029-current", { Pdf85C29AppsSortMenu() }) {}
    @Test fun page30() = snapshotAfter("03A-030-current", { Pdf85C30AppsMoreMenu() }) {}
    @Test fun page31() = snapshotAfter("03A-031-current", { Pdf85C31AppsWhitelist() }) {}
    @Test fun page32() = snapshotAfter("03A-032-current", { Pdf85C32AppsCore() }) {}
    @Test fun page33() = snapshotAfter("03A-033-current", { Pdf85C33AppsSearch() }) {}
    @Test fun page34() = snapshotAfter("03A-034-current", { Pdf85C34Cores() }) {}
    @Test fun page35() = snapshotAfter("03A-035-current", { Pdf85C35CoresDownloading() }) {}
    @Test fun page36() = snapshotAfter("03A-036-current", { Pdf85C36Bypass() }) {}
    @Test fun page37() = snapshotAfter("03A-037-current", { Pdf85C37BypassDiscard() }) {}
    @Test fun page38() = snapshotAfter("03A-038-current", { Pdf85C38Share() }) {}
    @Test fun page39() = snapshotAfter("03A-039-current", { Pdf85C39CnIp() }) {}
    @Test fun page40() = snapshotAfter("03A-040-current", { Pdf85C40Diag() }) {}
    @Test fun page41() = snapshotAfter("03A-041-current", { Pdf85C41StartupConfig() }) {}
    @Test fun page42() = snapshotAfter("03A-042-current", { Pdf85C42RestoreNetwork() }) {}
    @Test fun page43() = snapshotAfter("03A-043-current", { Pdf85C43Report() }) {}
    @Test fun page44() = snapshotAfter("03A-044-current", { Pdf85C44AdblockRules() }) {}
    @Test fun page45() = snapshotAfter("03A-045-current", { Pdf85C45AdblockStatus() }) {}
    @Test fun page46() = snapshotAfter("03A-046-current", { Pdf85C46AdblockHelp() }) {}
    @Test fun page47() = snapshotAfter("03A-047-current", { Pdf85C47AddAllow() }) {}
    @Test fun page48() = snapshotAfter("03A-048-current", { Pdf85C48AddBlock() }) {}
    @Test fun page49() = snapshotAfter("03A-049-current", { Pdf85C49ConfirmAllow() }) {}
}
