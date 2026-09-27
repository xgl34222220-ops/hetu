package io.github.xgl34222220.hetu

import android.os.Build
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
                error = failure.message ?: "代理切换失败"
                getSharedPreferences("hetu", MODE_PRIVATE)
                    .edit()
                    .putString("proxyTileLastError", error)
                    .apply()
            } finally {
                switching = false
                withContext(Dispatchers.Main) {
                    renderTile(error = error)
                }
            }
        }
    }

    private fun renderTile(stage: String = "", error: String = "") {
        val tile = qsTile ?: return
        val running = ProxyStatusBridge.rootProxyRunning(this)
        tile.label = "河图代理"
        tile.state = when {
            switching -> Tile.STATE_UNAVAILABLE
            running -> Tile.STATE_ACTIVE
            else -> Tile.STATE_INACTIVE
        }
        if (Build.VERSION.SDK_INT >= 29) {
            tile.subtitle = when {
                error.isNotBlank() -> "切换失败"
                stage.isNotBlank() -> stage
                running -> "运行中"
                else -> "已停止"
            }
        }
        tile.updateTile()
    }

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }
}
