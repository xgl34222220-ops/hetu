package io.github.xgl34222220.hetu

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.os.Build
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import io.github.xgl34222220.hetu.ui.translateHetuText

/**
 * Audit4 G7: 订阅到期 / 流量不足提醒. Off by default (通知设置 › 订阅提醒). Checked whenever the app
 * reads the subscription list; posts an ordinary notification on the existing 「代理运行状态」
 * channel ([CHANNEL_ID], the same id, name and importance ProxyStatusNotificationService creates),
 * at most once per subscription per day. Uses the already-declared POST_NOTIFICATIONS only when
 * it is granted; never asks for anything.
 */
internal object HxSubscriptionAlerts {
    /** Starts with “notification” so a settings backup carries it like the other notification options. */
    const val PREF_ENABLED = "notificationSubscriptionAlerts"
    /** Per-subscription day stamp; deliberately outside every backup prefix. */
    private const val PREF_DAY_PREFIX = "hxSubscriptionAlertDay."
    private const val CHANNEL_ID = "proxy_status"
    private const val NOTIFICATION_BASE = 0x5B100
    const val EXPIRE_DAYS = 3L
    const val LOW_FRACTION = 0.10

    sealed interface Alert {
        val name: String
        /** Expires in [days] whole days (0 = today, negative = already expired). */
        data class Expiring(override val name: String, val days: Long) : Alert
        /** [remaining] of [total] bytes left, at or below [LOW_FRACTION]. */
        data class LowTraffic(override val name: String, val remaining: Long, val total: Long) : Alert
    }

    fun enabled(prefs: SharedPreferences): Boolean = prefs.getBoolean(PREF_ENABLED, false)

    /** Pure decision for one subscription; expiry wins over traffic. [expire] in epoch seconds (ms tolerated). */
    fun alertFor(name: String, total: Long, used: Long, expire: Long, hasInfo: Boolean, nowMs: Long): Alert? {
        if (!hasInfo) return null
        if (expire > 0L) {
            val millis = if (expire > 100_000_000_000L) expire else expire * 1000L
            val left = millis - nowMs
            if (left <= EXPIRE_DAYS * 86_400_000L) return Alert.Expiring(name, Math.floorDiv(left, 86_400_000L))
        }
        if (total > 0L) {
            val remaining = (total - used).coerceAtLeast(0L)
            if (remaining.toDouble() <= total.toDouble() * LOW_FRACTION) return Alert.LowTraffic(name, remaining, total)
        }
        return null
    }

    fun dayStamp(nowMs: Long): String = SimpleDateFormat("yyyyMMdd", Locale.ROOT).format(Date(nowMs))

    /** Called on the main thread after a subscription read; cheap and silent when switched off. */
    fun check(context: Context, providers: List<DashboardProviderUi>, nowMs: Long = System.currentTimeMillis()) {
        runCatching {
            val app = context.applicationContext
            val prefs = app.getSharedPreferences("hetu", Context.MODE_PRIVATE)
            if (!enabled(prefs) || providers.isEmpty() || !canPost(app)) return
            val today = dayStamp(nowMs)
            val edit = prefs.edit()
            var changed = false
            providers.forEach { p ->
                val alert = alertFor(p.name, p.total, p.used, p.expire, p.hasSubscriptionInfo, nowMs) ?: return@forEach
                val key = PREF_DAY_PREFIX + p.name
                if (prefs.getString(key, "") == today) return@forEach
                if (post(app, prefs, alert)) {
                    edit.putString(key, today)
                    changed = true
                }
            }
            if (changed) edit.apply()
        }
    }

    private fun canPost(context: Context): Boolean {
        if (Build.VERSION.SDK_INT >= 33 &&
            context.checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) return false
        val manager = context.getSystemService(NotificationManager::class.java) ?: return false
        return manager.areNotificationsEnabled()
    }

    private fun post(context: Context, prefs: SharedPreferences, alert: Alert): Boolean {
        val manager = context.getSystemService(NotificationManager::class.java) ?: return false
        if (manager.getNotificationChannel(CHANNEL_ID) == null) {
            // Same channel the status service creates (id, name, importance), so nothing new appears in settings.
            manager.createNotificationChannel(
                NotificationChannel(CHANNEL_ID, "代理运行状态", NotificationManager.IMPORTANCE_LOW).apply {
                    description = "显示河图 Root/Mihomo 状态、速率和快捷控制"
                    setShowBadge(false)
                },
            )
        }
        val language = language(prefs)
        fun tr(text: String) = translateHetuText(text, language)
        val (title, body) = when (alert) {
            is Alert.Expiring -> tr("订阅即将到期") to when {
                alert.days < 0L -> tr("%s 已过期").format(alert.name)
                alert.days == 0L -> tr("%s 今天到期").format(alert.name)
                else -> tr("%s 将在 %d 天后到期").format(alert.name, alert.days)
            }
            is Alert.LowTraffic -> tr("订阅流量不足") to tr("%s 剩余 %s（%d%%）").format(
                alert.name, HxFormat.bytes(alert.remaining),
                if (alert.total > 0L) (alert.remaining * 100L / alert.total).toInt() else 0,
            )
        }
        val open = Intent(context, HetuActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
            .putExtra(HetuActivity.EXTRA_START_PAGE, "providers")
        val id = NOTIFICATION_BASE + (alert.name.hashCode() and 0xFFF)
        val notification = Notification.Builder(context, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setContentTitle(title)
            .setContentText(body)
            .setStyle(Notification.BigTextStyle().bigText(body))
            .setAutoCancel(true)
            .setContentIntent(PendingIntent.getActivity(context, id, open, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE))
            .build()
        manager.notify(id, notification)
        return true
    }

    private fun language(prefs: SharedPreferences): String {
        val setting = prefs.getString("appLanguage", "system").orEmpty()
        return if (setting.isBlank() || setting == "system") Locale.getDefault().toLanguageTag() else setting
    }
}
