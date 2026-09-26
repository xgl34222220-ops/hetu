package io.github.xgl34222220.hetu

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

internal data class TrafficRank(val name: String, val connections: Int, val upload: Long, val download: Long)

/** Opt-in local history. Disk work never runs in a Compose frame. */
internal object ProxyApiHistoryStore {
    private val gate = Mutex()
    private var helper: HistoryDb? = null
    private var lastRecord = 0L
    private var lastPrune = 0L
    private fun database(context: Context): SQLiteDatabase {
        if (helper == null) helper = HistoryDb(context.applicationContext)
        return helper!!.writableDatabase
    }
    private class HistoryDb(context: Context) : SQLiteOpenHelper(context, "hetu-api-history.db", null, 1) {
        override fun onCreate(db: SQLiteDatabase) {
            db.execSQL("CREATE TABLE traffic(time INTEGER PRIMARY KEY, upload INTEGER NOT NULL, download INTEGER NOT NULL)")
            db.execSQL("CREATE TABLE connections(id TEXT PRIMARY KEY, seen INTEGER NOT NULL, host TEXT, app TEXT, route TEXT, upload INTEGER NOT NULL, download INTEGER NOT NULL)")
            db.execSQL("CREATE INDEX connections_seen ON connections(seen)")
        }
        override fun onUpgrade(db: SQLiteDatabase, old: Int, new: Int) = Unit
    }
    suspend fun record(context: Context, upload: Long, download: Long, connections: List<ProxyConnectionUi> = emptyList(), now: Long = System.currentTimeMillis()) = withContext(Dispatchers.IO) {
        val prefs = context.getSharedPreferences("hetu", 0)
        if (!prefs.getBoolean("proxyApiHistoryEnabled", false)) return@withContext
        gate.withLock {
            if (now >= lastRecord && now - lastRecord < 900) return@withLock
            val db = database(context)
            db.beginTransaction()
            try {
                db.execSQL("INSERT OR REPLACE INTO traffic VALUES(?,?,?)", arrayOf(now, upload.coerceAtLeast(0), download.coerceAtLeast(0)))
                connections.forEach { item ->
                    val values = ContentValues().apply {
                        put("id", item.id); put("seen", now); put("host", item.host.take(300)); put("app", item.appName.ifBlank { item.packageName.ifBlank { "未知应用" } }.take(160))
                        put("route", item.chain.take(400)); put("upload", item.upload.coerceAtLeast(0)); put("download", item.download.coerceAtLeast(0))
                    }
                    db.insertWithOnConflict("connections", null, values, SQLiteDatabase.CONFLICT_REPLACE)
                }
                db.setTransactionSuccessful(); lastRecord = now
            } finally { db.endTransaction() }
            if (now < lastPrune || now - lastPrune >= 60_000) {
                val cutoff = now - prefs.getInt("proxyApiHistoryRetentionDays", 7).coerceIn(1, 90) * 86_400_000L
                db.delete("traffic", "time < ?", arrayOf(cutoff.toString()))
                db.delete("connections", "seen < ?", arrayOf(cutoff.toString()))
                val limit = prefs.getInt("proxyApiHistoryMaxMb", 16).coerceIn(4, 256) * 1024L * 1024
                fun size() = db.rawQuery("PRAGMA page_count", null).use { it.moveToFirst(); it.getLong(0) } * db.rawQuery("PRAGMA page_size", null).use { it.moveToFirst(); it.getLong(0) }
                var attempts = 0
                while (size() > limit && attempts++ < 8) {
                    db.execSQL("DELETE FROM traffic WHERE time IN (SELECT time FROM traffic ORDER BY time LIMIT max(1,(SELECT count(*) / 3 FROM traffic)))")
                    db.execSQL("DELETE FROM connections WHERE id IN (SELECT id FROM connections ORDER BY seen LIMIT max(1,(SELECT count(*) / 3 FROM connections)))")
                    db.execSQL("VACUUM")
                }
                lastPrune = now
            }
        }
    }
    suspend fun recent(context: Context, windowMs: Long = 60_000L, now: Long = System.currentTimeMillis()): List<Triple<Long, Long, Long>> = withContext(Dispatchers.IO) {
        if (!context.getSharedPreferences("hetu", 0).getBoolean("proxyApiHistoryEnabled", false)) return@withContext emptyList()
        gate.withLock {
            database(context).rawQuery("SELECT time,upload,download FROM traffic WHERE time BETWEEN ? AND ? ORDER BY time DESC LIMIT 3600", arrayOf((now-windowMs).toString(), now.toString())).use { rows ->
                buildList { while (rows.moveToNext()) add(Triple(rows.getLong(0), rows.getLong(1), rows.getLong(2))) }.reversed()
            }
        }
    }
    suspend fun ranking(context: Context, dimension: String, sort: String, since: Long): List<TrafficRank> = withContext(Dispatchers.IO) {
        if (!context.getSharedPreferences("hetu", 0).getBoolean("proxyApiHistoryEnabled", false)) return@withContext emptyList()
        val column = when(dimension) { "app" -> "app"; "route" -> "route"; else -> "host" }
        val order = if(sort == "traffic") "sum(upload)+sum(download)" else "count(*)"
        gate.withLock {
            database(context).rawQuery("SELECT $column,count(*),sum(upload),sum(download) FROM connections WHERE seen >= ? GROUP BY $column ORDER BY $order DESC LIMIT 20", arrayOf(since.toString())).use { rows ->
                buildList { while(rows.moveToNext()) add(TrafficRank(rows.getString(0).orEmpty().ifBlank { "—" }, rows.getInt(1), rows.getLong(2), rows.getLong(3))) }
            }
        }
    }
    suspend fun clear(context: Context) = withContext(Dispatchers.IO) {
        gate.withLock { database(context).apply { delete("traffic", null, null); delete("connections", null, null); execSQL("VACUUM") }; lastRecord = 0; lastPrune = 0 }
        context.getSharedPreferences("hetu", 0).edit().remove("proxyApiTrafficHistory").apply()
    }
}
