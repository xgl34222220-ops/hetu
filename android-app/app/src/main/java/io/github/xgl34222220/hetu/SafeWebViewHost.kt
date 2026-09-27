package io.github.xgl34222220.hetu

import android.content.Context
import android.content.res.Configuration
import android.graphics.Color
import android.os.Bundle
import android.view.ViewGroup
import android.view.WindowManager
import android.webkit.WebView
import android.widget.FrameLayout
import androidx.activity.ComponentActivity
import androidx.activity.addCallback
import androidx.activity.enableEdgeToEdge
import androidx.core.graphics.Insets
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.doOnAttach

/**
 * Keep every embedded dashboard inside the usable window, independent of its CSS
 * or installed WebView version. Applying padding to WebView itself does not resize
 * its page viewport, so the padding belongs to this parent.
 */
internal class SafeWebViewHost(context: Context) : FrameLayout(context) {
    init {
        val dark = resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK == Configuration.UI_MODE_NIGHT_YES
        setBackgroundColor(if (dark) Color.rgb(16, 16, 20) else Color.WHITE)
        ViewCompat.setOnApplyWindowInsetsListener(this) { view, windowInsets ->
            val types = WindowInsetsCompat.Type.systemBars() or
                WindowInsetsCompat.Type.displayCutout() or WindowInsetsCompat.Type.ime()
            // Union takes the largest occupied edge, rather than adding the
            // navigation bar a second time when the keyboard is showing.
            val safe = windowInsets.getInsets(types)
            view.setPadding(safe.left, safe.top, safe.right, safe.bottom)
            // Deliver zero dimensions instead of CONSUMED. The WebView must see
            // keyboard/cutout changes to clear any previous CSS or viewport inset.
            WindowInsetsCompat.Builder(windowInsets).setInsets(types, Insets.NONE).build()
        }
        doOnAttach { ViewCompat.requestApplyInsets(it) }
    }
}

internal fun ComponentActivity.setSafeWebViewContent(webView: WebView) {
    enableEdgeToEdge()
    window.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE)
    val host = SafeWebViewHost(this)
    host.addView(webView, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
    setContentView(host)
    onBackPressedDispatcher.addCallback(this) {
        if (webView.canGoBack()) webView.goBack() else finish()
    }
}

private const val WEB_VIEW_STATE = "hetu.webViewState"

internal fun WebView.restoreSavedPage(savedInstanceState: Bundle?): Boolean =
    savedInstanceState?.getBundle(WEB_VIEW_STATE)?.let { restoreState(it) != null } == true

internal fun WebView.savePage(outState: Bundle) {
    val state = Bundle()
    if (saveState(state) != null) outState.putBundle(WEB_VIEW_STATE, state)
}
