package io.github.xgl34222220.hetu

import android.content.Context
import android.content.Intent
import androidx.core.content.pm.ShortcutInfoCompat
import androidx.core.content.pm.ShortcutManagerCompat
import androidx.core.graphics.drawable.IconCompat
import io.github.xgl34222220.hetu.ui.translateHetuText
import java.util.Locale

/**
 * Audit4 G8: long-press launcher shortcuts 启动代理 / 停止代理 / 网络测试. Dynamic shortcuts only
 * (no manifest entry, no permission); each opens HetuActivity with [EXTRA], which hands start / stop
 * to the view model's guarded toggle (same ProxyComposeController path as the QS tile) and opens the
 * network test page for [NET_TEST].
 */
internal object HxLauncherShortcuts {
    const val EXTRA = "io.github.xgl34222220.hetu.SHORTCUT"
    const val START = "start"
    const val STOP = "stop"
    const val NET_TEST = "net-test"
    val actions = listOf(START, STOP, NET_TEST)

    /** Short and long label (Chinese source text, translated when published). */
    fun labels(action: String): Pair<String, String> = when (action) {
        START -> "启动代理" to "启动河图代理"
        STOP -> "停止代理" to "停止河图代理"
        else -> "网络测试" to "连通性与网速测试"
    }

    fun intent(context: Context, action: String): Intent =
        Intent(Intent.ACTION_VIEW).setClass(context, HetuActivity::class.java).putExtra(EXTRA, action)

    /** Publishes (or relabels) the three shortcuts; never throws. Call off the main thread. */
    fun publish(context: Context) {
        runCatching {
            val app = context.applicationContext
            val setting = app.getSharedPreferences("hetu", Context.MODE_PRIVATE).getString("appLanguage", "system").orEmpty()
            val language = if (setting.isBlank() || setting == "system") Locale.getDefault().toLanguageTag() else setting
            val shortcuts = actions.mapIndexed { rank, action ->
                val (short, long) = labels(action)
                ShortcutInfoCompat.Builder(app, "hetu-$action")
                    .setShortLabel(translateHetuText(short, language))
                    .setLongLabel(translateHetuText(long, language))
                    .setIcon(IconCompat.createWithResource(app, R.drawable.ic_hetu))
                    .setIntent(intent(app, action))
                    .setRank(rank)
                    .build()
            }
            ShortcutManagerCompat.setDynamicShortcuts(app, shortcuts)
        }
    }
}
