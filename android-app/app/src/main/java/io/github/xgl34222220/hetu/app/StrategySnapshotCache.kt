package io.github.xgl34222220.hetu

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.concurrent.Executors

/**
 * The last complete strategy-group list, kept only so a cold open can draw the
 * cards at once instead of empty placeholders while Root status and the four
 * controller reads finish. It is display data: the restored list never sets
 * panelReady, and the ViewModel refuses selections/probes while it is shown.
 * Latency readings are not stored; they always come from a fresh measurement.
 */
internal object StrategySnapshotCache {
    private const val FILE = "strategy-groups-v1.json"
    private const val SCHEMA = 1
    const val PREF_KEY = "proxyUiStrategyCacheConfig"
    private val writer = Executors.newSingleThreadExecutor { task ->
        Thread(task, "hetu-strategy-cache").apply { isDaemon = true }
    }
    @Volatile private var lastWritten: Pair<String, List<ProxyGroupUi>>? = null

    private fun file(context: Context) = File(context.applicationContext.noBackupFilesDir, FILE)

    private fun stripped(groups: List<ProxyGroupUi>) = groups.map { group ->
        group.copy(nodes = group.nodes.map { it.copy(lastDelay = null, lastDelayAt = 0L) })
    }

    fun read(context: Context, config: String): List<ProxyGroupUi> = try {
        val source = file(context)
        if (!source.isFile) emptyList() else {
            val root = JSONObject(source.readText())
            if (root.optInt("schema") != SCHEMA || root.optString("config") != config) emptyList()
            else {
                val array = root.optJSONArray("groups") ?: JSONArray()
                (0 until array.length()).mapNotNull { index ->
                    val group = array.optJSONObject(index) ?: return@mapNotNull null
                    val name = group.optString("name")
                    if (name.isBlank()) return@mapNotNull null
                    val nodes = group.optJSONArray("nodes") ?: JSONArray()
                    ProxyGroupUi(
                        name = name, type = group.optString("type"), now = group.optString("now"),
                        nodes = (0 until nodes.length()).mapNotNull { n ->
                            val node = nodes.optJSONObject(n) ?: return@mapNotNull null
                            node.optString("name").takeIf { it.isNotBlank() }?.let {
                                ProxyNodeUi(it, node.optString("type"), node.optBoolean("udp", false),
                                    provider = node.optString("provider"))
                            }
                        },
                        iconUrl = group.optString("iconUrl"), iconPath = group.optString("iconPath"),
                        hidden = group.optBoolean("hidden", false),
                    )
                }
            }
        }
    } catch (_: Exception) { emptyList() }

    /** Writes off the main thread, and only when the group structure actually changed. */
    fun write(context: Context, config: String, groups: List<ProxyGroupUi>) {
        if (groups.isEmpty()) return
        val clean = stripped(groups)
        val key = config to clean
        if (lastWritten == key) return
        lastWritten = key
        val target = file(context)
        writer.execute {
            try {
                val array = JSONArray()
                clean.forEach { group ->
                    val nodes = JSONArray()
                    group.nodes.forEach { node ->
                        nodes.put(JSONObject().put("name", node.name).put("type", node.type)
                            .put("udp", node.udp).put("provider", node.provider))
                    }
                    array.put(JSONObject().put("name", group.name).put("type", group.type).put("now", group.now)
                        .put("iconUrl", group.iconUrl).put("iconPath", group.iconPath)
                        .put("hidden", group.hidden).put("nodes", nodes))
                }
                val text = JSONObject().put("schema", SCHEMA).put("config", config).put("groups", array).toString()
                target.parentFile?.mkdirs()
                val temporary = File(target.parentFile, "$FILE.tmp")
                temporary.writeText(text)
                if (!temporary.renameTo(target)) { target.delete(); temporary.renameTo(target) }
            } catch (_: Exception) {
                lastWritten = null
            }
        }
    }
}
