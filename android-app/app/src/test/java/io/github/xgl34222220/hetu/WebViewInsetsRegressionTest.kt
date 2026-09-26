package io.github.xgl34222220.hetu

import android.content.Context
import android.content.Intent
import android.view.View
import android.view.ViewGroup
import android.webkit.WebView
import android.widget.FrameLayout
import androidx.core.graphics.Insets
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class WebViewInsetsRegressionTest {
    private val app get() = ApplicationProvider.getApplicationContext<Context>()
    private val handledTypes = WindowInsetsCompat.Type.systemBars() or
        WindowInsetsCompat.Type.displayCutout() or WindowInsetsCompat.Type.ime()

    private fun insets(
        top: Int = 24,
        bottom: Int = 28,
        cutout: Insets = Insets.NONE,
        ime: Int = 0,
        right: Int = 0,
    ) = WindowInsetsCompat.Builder()
        .setInsets(WindowInsetsCompat.Type.statusBars(), Insets.of(0, top, 0, 0))
        .setInsets(WindowInsetsCompat.Type.navigationBars(), Insets.of(0, 0, right, bottom))
        .setInsets(WindowInsetsCompat.Type.displayCutout(), cutout)
        .setInsets(WindowInsetsCompat.Type.ime(), Insets.of(0, 0, 0, ime))
        .build()

    private fun layout(host: SafeWebViewHost, width: Int = 400, height: Int = 800) {
        host.measure(
            View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(height, View.MeasureSpec.EXACTLY),
        )
        host.layout(0, 0, width, height)
    }

    @Test fun nativeHostKeepsWholeWebViewportInsideStatusCutoutAndNavigationBars() {
        val host = SafeWebViewHost(app)
        val child = View(app)
        var forwarded: WindowInsetsCompat? = null
        ViewCompat.setOnApplyWindowInsetsListener(child) { _, value -> forwarded = value; value }
        host.addView(child, FrameLayout.LayoutParams(-1, -1))
        ViewCompat.dispatchApplyWindowInsets(host, insets(cutout = Insets.of(8, 42, 0, 0)))
        layout(host)

        assertEquals(8, child.left)
        assertEquals(42, child.top)
        assertEquals(400, child.right)
        assertEquals(772, child.bottom)
        // HTML safe-area padding must not add the same native inset again.
        assertNotNull(forwarded)
        assertEquals(Insets.NONE, forwarded!!.getInsets(handledTypes))
    }

    @Test fun keyboardUsesLargestBottomAndNotifiesWebViewWhenItCloses() {
        val host = SafeWebViewHost(app)
        val child = View(app)
        val forwarded = mutableListOf<Insets>()
        ViewCompat.setOnApplyWindowInsetsListener(child) { _, value ->
            forwarded += value.getInsets(handledTypes)
            value
        }
        host.addView(child, FrameLayout.LayoutParams(-1, -1))
        ViewCompat.dispatchApplyWindowInsets(host, insets(ime = 300))
        layout(host)
        assertEquals(300, host.paddingBottom)
        assertEquals(500, child.bottom)

        ViewCompat.dispatchApplyWindowInsets(host, insets(ime = 0))
        layout(host)
        assertEquals(28, host.paddingBottom)
        assertEquals(772, child.bottom)
        assertEquals(listOf(Insets.NONE, Insets.NONE), forwarded)
    }

    @Test fun rotationAndHiddenBarsReplacePaddingInsteadOfAccumulatingIt() {
        val host = SafeWebViewHost(app)
        val child = View(app)
        host.addView(child, FrameLayout.LayoutParams(-1, -1))
        ViewCompat.dispatchApplyWindowInsets(host, insets(cutout = Insets.of(0, 42, 0, 0)))
        ViewCompat.dispatchApplyWindowInsets(host, insets(top = 24, bottom = 0, cutout = Insets.of(42, 0, 0, 0), right = 28))
        layout(host, width = 800, height = 400)
        assertEquals(42, child.left)
        assertEquals(24, child.top)
        assertEquals(772, child.right)
        assertEquals(400, child.bottom)

        ViewCompat.dispatchApplyWindowInsets(host, insets(top = 0, bottom = 0))
        layout(host, width = 800, height = 400)
        assertEquals(0, child.left)
        assertEquals(0, child.top)
        assertEquals(800, child.right)
        assertEquals(400, child.bottom)
    }

    @Test fun everyWebPanelEntryUsesTheNativeSafeViewport() {
        val activities = listOf(
            ProxyLocalWebUiActivity::class.java,
            ProxyWebPanelViewerActivity::class.java,
            ProxySubStoreWebActivity::class.java,
        )
        for (type in activities) {
            val intent = Intent(app, type)
                .putExtra("panel_name", "面板")
                .putExtra("panel_url", "https://example.com/panel")
            val controller = Robolectric.buildActivity(type, intent).create()
            try {
                val content = controller.get().findViewById<ViewGroup>(android.R.id.content)
                assertTrue("${type.simpleName} needs a safe host", content.getChildAt(0) is SafeWebViewHost)
                val host = content.getChildAt(0) as SafeWebViewHost
                assertTrue(host.getChildAt(0) is WebView)
                ViewCompat.dispatchApplyWindowInsets(host, insets())
                assertEquals("${type.simpleName} host inset", 24, host.paddingTop)
                layout(host)
                // Stock ShadowWebView's Chromium provider has a no-op setFrame,
                // so top/bottom stay zero even after correct native measurement.
                // Assert the real WebView receives the safe viewport dimensions;
                // the other three tests check exact host child bounds/insets.
                assertEquals("${type.simpleName} top safe area", 24, host.paddingTop)
                assertEquals("${type.simpleName} bottom safe area", 28, host.paddingBottom)
                assertEquals("${type.simpleName} WebView viewport width", 400, host.getChildAt(0).measuredWidth)
                assertEquals("${type.simpleName} WebView viewport height", 748, host.getChildAt(0).measuredHeight)
            } finally {
                controller.destroy()
            }
        }
    }
}
