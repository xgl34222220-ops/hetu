package io.github.xgl34222220.hetu

import android.content.ComponentName
import android.content.Context
import android.os.Build
import android.widget.Toast
import android.service.quicksettings.Tile
import android.service.quicksettings.TileService
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Quick Settings entry for the real Root/Mihomo runtime.
 *
 * The tile deliberately delegates start/stop to ProxyComposeController so there
 * is only one lifecycle authority. It never maintains a second proxy state.
 */
class HetuProxyTileService : TileService() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    @Volatile private var switching = false

    override fun onStartListening() {
        super.onStartListening()
        renderTile()
    }

    override fun onClick() {
        super.onClick()
        if (switching) return
        switching = true
        renderTile(stage = "切换中…")
        scope.launch {
            var error = ""
            try {
                val controller = ProxyComposeController(this@HetuProxyTileService)
                if (ProxyStatusBridge.rootProxyRunning(this@HetuProxyTileService)) {
                    controller.stop()
                } else {
                    controller.start()
                }
                ProxyStatusNotificationService.refresh(this@HetuProxyTileService)
            } catch (cancel: CancellationException) {
                throw cancel
            } catch (failure: Exception) {
                error = hxErrorMessage(failure, "代理切换失败")
                getSharedPreferences("hetu", MODE_PRIVATE)
                    .edit()
                    .putString("proxyTileLastError", error)
                    .apply()
            } finally {
                switching = false
                withContext(Dispatchers.Main) {
                    renderTile(error = error)
                    // func-400 T1: the reason is shown, not only 「切换失败」 in the tile subtitle.
                    if (error.isNotBlank()) runCatching {
                        Toast.makeText(applicationContext, text("代理切换失败") + "：" + error, Toast.LENGTH_LONG).show()
                    }
                }
            }
        }
    }

    private fun renderTile(stage: String = "", error: String = "") {
        val tile = qsTile ?: return
        val running = ProxyStatusBridge.rootProxyRunning(this)
        tile.label = text("河图代理")
        tile.state = when {
            switching -> Tile.STATE_UNAVAILABLE
            running -> Tile.STATE_ACTIVE
            else -> Tile.STATE_INACTIVE
        }
        if (Build.VERSION.SDK_INT >= 29) {
            tile.subtitle = text(when {
                error.isNotBlank() -> "切换失败"
                stage.isNotBlank() -> stage
                running -> "运行中"
                else -> "已停止"
            })
        }
        tile.updateTile()
    }

    /** func-400 T1: tile texts follow the App language, as toasts do. */
    private fun text(source: String): String = runCatching { hxPlainText(this, source) }.getOrDefault(source)

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }

    companion object {
        /**
         * func-400 T2: asks System UI to re-read the tile after the proxy was started or stopped
         * elsewhere (App, notification, shortcut), so an open Quick Settings panel is not stale.
         */
        fun requestRefresh(context: Context) {
            runCatching { TileService.requestListeningState(context, ComponentName(context, HetuProxyTileService::class.java)) }
        }
    }
}
