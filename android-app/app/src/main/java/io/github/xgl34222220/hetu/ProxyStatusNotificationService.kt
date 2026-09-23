package io.github.xgl34222220.hetu

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.util.Locale

/**
 * Optional foreground status notification for the Root/Mihomo runtime.
 * It never owns the proxy lifecycle: it only reads the existing controller and
 * forwards explicit notification actions to the same controller used by the UI.
 */
class ProxyStatusNotificationService : Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private lateinit var prefs: android.content.SharedPreferences
    private lateinit var controller: ProxyComposeController
    private lateinit var inspector: ProxyRuntimeInspector
    private var loopJob: Job? = null
    private var lastUpload = 0L
    private var lastDownload = 0L
    private var lastTrafficAt = 0L
    private var uploadRate = 0L
    private var downloadRate = 0L
    private var lastProcessTicks = 0L
    private var lastSystemTicks = 0L
    private var cpuPercent = 0f

    override fun onCreate() {
        super.onCreate()
        prefs = getSharedPreferences("hetu", MODE_PRIVATE)
        controller = ProxyComposeController(this)
        inspector = ProxyRuntimeInspector(this)
        val manager = getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(
            NotificationChannel(CHANNEL_ID, "代理运行状态", NotificationManager.IMPORTANCE_LOW).apply {
                description = "显示河图 Root/Mihomo 状态、速率和快捷控制"
                setShowBadge(false)
            },
        )
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_DISABLE -> {
                prefs.edit().putBoolean(PREF_ENABLED, false).apply()
                loopJob?.cancel()
                stopForeground(STOP_FOREGROUND_REMOVE)
                stopSelf()
                return START_NOT_STICKY
            }
            ACTION_RELOAD -> runControl("正在重载…") { controller.reload() }
            ACTION_RESTART -> runControl("正在重启…") { controller.restart() }
            ACTION_STOP_PROXY -> runControl("正在停止…") { controller.stop() }
        }
        if (!prefs.getBoolean(PREF_ENABLED, false)) {
            stopSelf()
            return START_NOT_STICKY
        }
        ensureForeground("正在读取代理状态…")
        if (loopJob?.isActive != true) {
            loopJob = scope.launch {
                while (isActive && prefs.getBoolean(PREF_ENABLED, false)) {
                    updateNotification()
                    delay(3_000L)
                }
            }
        }
        return START_STICKY
    }

    private fun runControl(stage: String, action: suspend () -> Any?) {
        ensureForeground(stage)
        scope.launch {
            try {
                action()
            } catch (cancel: CancellationException) {
                throw cancel
            } catch (error: Exception) {
                prefs.edit().putString("proxyStatusNotificationError", error.message ?: "通知栏操作失败").apply()
            }
            updateNotification()
        }
    }

    private suspend fun updateNotification() {
        val state = runCatching { controller.state() }.getOrElse {
            val body = prefs.getString("proxyStatusNotificationError", "").orEmpty().ifBlank { "暂时无法读取代理状态" }
            getSystemService(NotificationManager::class.java).notify(NOTIFICATION_ID, buildNotification("河图 · 代理状态", body, false))
            return
        }
        val runtime = runCatching { inspector.sample() }.getOrDefault(ProxyRuntimeSnapshot(running = state.running))

        val now = android.os.SystemClock.elapsedRealtime()
        if (lastTrafficAt > 0L && now > lastTrafficAt && state.uploadTotal >= lastUpload && state.downloadTotal >= lastDownload) {
            val elapsed = now - lastTrafficAt
            uploadRate = ((state.uploadTotal - lastUpload) * 1000L / elapsed).coerceAtLeast(0L)
            downloadRate = ((state.downloadTotal - lastDownload) * 1000L / elapsed).coerceAtLeast(0L)
        }
        lastTrafficAt = now
        lastUpload = state.uploadTotal
        lastDownload = state.downloadTotal

        if (state.running && lastSystemTicks > 0L && runtime.systemTicks > lastSystemTicks && runtime.processTicks >= lastProcessTicks) {
            val deltaProcess = runtime.processTicks - lastProcessTicks
            val deltaSystem = runtime.systemTicks - lastSystemTicks
            val cores = Runtime.getRuntime().availableProcessors().coerceAtLeast(1)
            cpuPercent = ((deltaProcess.toDouble() / deltaSystem.toDouble()) * 100.0 * cores)
                .toFloat().coerceIn(0f, 100f)
        } else if (!state.running) {
            cpuPercent = 0f
            uploadRate = 0L
            downloadRate = 0L
        }
        lastProcessTicks = runtime.processTicks
        lastSystemTicks = runtime.systemTicks

        val values = mapOf(
            "status" to if (state.running) "运行中" else "已停止",
            "uptime" to refDuration(runtime.elapsedSeconds),
            "upload" to refSpeed(uploadRate),
            "download" to refSpeed(downloadRate),
            "upload_total" to refBytes(state.uploadTotal),
            "download_total" to refBytes(state.downloadTotal),
            "cpu" to String.format(Locale.US, "%.1f%%", cpuPercent),
            "memory" to refBytes(runtime.rssBytes.takeIf { it > 0L } ?: state.memoryBytes),
            "connections" to state.connections.size.toString(),
            "config" to state.config,
            "core" to state.core,
            "mode" to state.mode,
        )
        val template = prefs.getString(PREF_TEMPLATE, DEFAULT_TEMPLATE).orEmpty().ifBlank { DEFAULT_TEMPLATE }
        var body = template
        values.forEach { (key, value) -> body = body.replace("{$key}", value) }
        val title = "河图 · ${state.core} · ${state.mode}"
        getSystemService(NotificationManager::class.java)
            .notify(NOTIFICATION_ID, buildNotification(title, body, state.running))
    }

    private fun buildNotification(title: String, body: String, running: Boolean): Notification {
        val launch = packageManager.getLaunchIntentForPackage(packageName)
        val builder = Notification.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_lock_lock)
            .setContentTitle(title)
            .setContentText(body.lineSequence().firstOrNull().orEmpty())
            .setStyle(Notification.BigTextStyle().bigText(body))
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setShowWhen(false)
            .setCategory(Notification.CATEGORY_SERVICE)

        if (launch != null) {
            builder.setContentIntent(
                PendingIntent.getActivity(this, 0, launch, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE),
            )
        }
        val first = prefs.getString(PREF_ACTION_1, "reload").orEmpty()
        val second = prefs.getString(PREF_ACTION_2, "restart").orEmpty()
        val third = prefs.getString(PREF_ACTION_3, "stop").orEmpty()
        listOf(first, second, third).distinct().filter { it != "none" }.forEachIndexed { index, action ->
            actionSpec(action, running)?.let { (label, intentAction) ->
                val pending = PendingIntent.getService(
                    this,
                    40 + index,
                    Intent(this, ProxyStatusNotificationService::class.java).setAction(intentAction),
                    PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
                )
                builder.addAction(Notification.Action.Builder(null, label, pending).build())
            }
        }
        return builder.build()
    }

    private fun actionSpec(value: String, running: Boolean): Pair<String, String>? = when (value) {
        "reload" -> if (running) "重载" to ACTION_RELOAD else null
        "restart" -> if (running) "重启" to ACTION_RESTART else null
        "stop" -> if (running) "停止" to ACTION_STOP_PROXY else null
        "hide" -> "隐藏" to ACTION_DISABLE
        else -> null
    }

    private fun ensureForeground(text: String) {
        val notification = buildNotification(
            "河图 · 代理状态",
            text,
            prefs.getBoolean("proxyRootRuntimeRunning", false) && prefs.getBoolean("proxyRootWanted", false),
        )
        if (Build.VERSION.SDK_INT >= 34) {
            startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
    }

    override fun onDestroy() {
        loopJob?.cancel()
        scope.cancel()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    companion object {
        const val PREF_ENABLED = "proxyStatusNotificationEnabled"
        const val PREF_TEMPLATE = "proxyStatusNotificationTemplate"
        const val PREF_ACTION_1 = "proxyStatusNotificationAction1"
        const val PREF_ACTION_2 = "proxyStatusNotificationAction2"
        const val PREF_ACTION_3 = "proxyStatusNotificationAction3"
        const val DEFAULT_TEMPLATE = "{status} · {uptime}\n↓ {download}  ↑ {upload} · CPU {cpu}"
        private const val CHANNEL_ID = "proxy_status"
        private const val NOTIFICATION_ID = 2026
        private const val ACTION_DISABLE = "io.github.xgl34222220.hetu.STATUS_NOTIFICATION_DISABLE"
        private const val ACTION_RELOAD = "io.github.xgl34222220.hetu.STATUS_NOTIFICATION_RELOAD"
        private const val ACTION_RESTART = "io.github.xgl34222220.hetu.STATUS_NOTIFICATION_RESTART"
        private const val ACTION_STOP_PROXY = "io.github.xgl34222220.hetu.STATUS_NOTIFICATION_STOP_PROXY"

        fun setEnabled(context: Context, enabled: Boolean) {
            context.getSharedPreferences("hetu", Context.MODE_PRIVATE).edit().putBoolean(PREF_ENABLED, enabled).apply()
            val intent = Intent(context, ProxyStatusNotificationService::class.java)
                .setAction(if (enabled) null else ACTION_DISABLE)
            if (enabled && Build.VERSION.SDK_INT >= 26) context.startForegroundService(intent)
            else context.startService(intent)
        }

        fun refresh(context: Context) {
            if (!context.getSharedPreferences("hetu", Context.MODE_PRIVATE).getBoolean(PREF_ENABLED, false)) return
            val intent = Intent(context, ProxyStatusNotificationService::class.java)
            if (Build.VERSION.SDK_INT >= 26) context.startForegroundService(intent) else context.startService(intent)
        }
    }
}
