package io.github.xgl34222220.hetu

import android.content.Context
import io.github.xgl34222220.hetu.ui.AppItem
import io.github.xgl34222220.hetu.ui.HetuComposeController
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

internal val AppItem.userId: Int get() = uid.coerceAtLeast(0) / 100000
internal val AppItem.selectionKey: String get() = if (userId == android.os.Process.myUid() / 100000) packageName else "$userId:$packageName"

internal object ProxyUserAppsRepository {
    suspend fun load(context: Context, controller: HetuComposeController, refresh: Boolean): List<AppItem> = withContext(Dispatchers.IO) {
        val local = controller.loadApps(refresh)
        val known = local.associateBy { it.packageName }
        try {
            val result = RootBridge.rootShell(context, "pm list users", 4000)
            if (!result.ok()) return@withContext local
            val users = Regex("UserInfo\\{(\\d+):").findAll(result.output).mapNotNull { it.groupValues[1].toIntOrNull() }.distinct().filter { it != android.os.Process.myUid() / 100000 }.toList()
            val extra = users.flatMap { user ->
                val packages = RootBridge.rootShell(context, "pm list packages -U --user $user", 5000)
                if (!packages.ok()) emptyList() else parse(packages.output, user).filter { it.first != context.packageName }.map { (pkg, uid) ->
                    val original = known[pkg]
                    AppItem(original?.label ?: pkg, pkg, original?.system ?: (uid % 100000 < 10000), uid = uid)
                }
            }
            (local + extra).distinctBy { it.selectionKey }.sortedWith(compareBy<AppItem> { it.userId }.thenBy { it.label })
        } catch (cancel: CancellationException) { throw cancel }
        catch (_: Exception) { local }
    }
    fun parse(output: String, user: Int): List<Pair<String, Int>> = Regex("(?m)^package:([A-Za-z0-9_.]+)\\s+uid:(\\d+)\\s*$").findAll(output).mapNotNull {
        val uid = it.groupValues[2].toIntOrNull() ?: return@mapNotNull null
        if (uid / 100000 == user) it.groupValues[1] to uid else null
    }.toList()
}
