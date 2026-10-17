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
    private class HistoryDb(context: Context) : SQLiteOpenHelper(context, "hetu-api-history.db", null, 2) {
        override fun onCreate(db: SQLiteDatabase) {
            db.execSQL("CREATE TABLE traffic(time INTEGER PRIMARY KEY, upload INTEGER NOT NULL, download INTEGER NOT NULL)")
            db.execSQL("CREATE TABLE connections(id TEXT PRIMARY KEY, seen INTEGER NOT NULL, host TEXT, app TEXT, route TEXT, upload INTEGER NOT NULL, download INTEGER NOT NULL)")
            db.execSQL("CREATE INDEX connections_seen ON connections(seen)")
            createDimensionTables(db)
        }
        override fun onUpgrade(db: SQLiteDatabase, old: Int, new: Int) { if (old < 2) createDimensionTables(db) }
        // Version 2: minute buckets fed by the Root monitor daemon (it keeps counting while the App
        // is killed), by app / host / destination IP / outbound / rule, plus controller totals.
        private fun createDimensionTables(db: SQLiteDatabase) {
            db.execSQL("CREATE TABLE IF NOT EXISTS traffic_dimension_buckets(minute INTEGER NOT NULL, dim TEXT NOT NULL, key TEXT NOT NULL, upload INTEGER NOT NULL, download INTEGER NOT NULL, connections INTEGER NOT NULL, PRIMARY KEY(minute, dim, key))")
            db.execSQL("CREATE INDEX IF NOT EXISTS traffic_dimension_dim_minute ON traffic_dimension_buckets(dim, minute)")
            db.execSQL("CREATE TABLE IF NOT EXISTS traffic_minutes(minute INTEGER PRIMARY KEY, upload INTEGER NOT NULL, download INTEGER NOT NULL)")
            db.execSQL("CREATE TABLE IF NOT EXISTS history_meta(key TEXT PRIMARY KEY, value TEXT NOT NULL)")
        }
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
                pruneLocked(db, prefs, now)
                lastPrune = now
            }
        }
    }
    /** Retention days and database size cap over every history table (oldest third first). */
    private fun pruneLocked(db: SQLiteDatabase, prefs: android.content.SharedPreferences, now: Long) {
        val cutoff = now - prefs.getInt("proxyApiHistoryRetentionDays", 7).coerceIn(1, 90) * 86_400_000L
        db.delete("traffic", "time < ?", arrayOf(cutoff.toString()))
        db.delete("connections", "seen < ?", arrayOf(cutoff.toString()))
        db.delete("traffic_dimension_buckets", "minute < ?", arrayOf((cutoff / 1000).toString()))
        db.delete("traffic_minutes", "minute < ?", arrayOf((cutoff / 1000).toString()))
        val limit = prefs.getInt("proxyApiHistoryMaxMb", 16).coerceIn(4, 256) * 1024L * 1024
        fun size() = db.rawQuery("PRAGMA page_count", null).use { it.moveToFirst(); it.getLong(0) } * db.rawQuery("PRAGMA page_size", null).use { it.moveToFirst(); it.getLong(0) }
        var attempts = 0
        while (size() > limit && attempts++ < 8) {
            db.execSQL("DELETE FROM traffic WHERE time IN (SELECT time FROM traffic ORDER BY time LIMIT max(1,(SELECT count(*) / 3 FROM traffic)))")
            db.execSQL("DELETE FROM connections WHERE id IN (SELECT id FROM connections ORDER BY seen LIMIT max(1,(SELECT count(*) / 3 FROM connections)))")
            // Daemon buckets: drop the oldest third of the minutes they cover (at least one minute).
            db.execSQL("DELETE FROM traffic_dimension_buckets WHERE minute <= (SELECT minute FROM (SELECT DISTINCT minute FROM traffic_dimension_buckets ORDER BY minute) LIMIT 1 OFFSET (SELECT count(DISTINCT minute) / 3 FROM traffic_dimension_buckets))")
            db.execSQL("DELETE FROM traffic_minutes WHERE minute IN (SELECT minute FROM traffic_minutes ORDER BY minute LIMIT max(1,(SELECT count(*) / 3 FROM traffic_minutes)))")
            db.execSQL("VACUUM")
        }
    }

    /**
     * Commits one daemon batch (minute buckets + totals) and its id in a single transaction, so a
     * batch delivered again after a crash between commit and acknowledgement is skipped. Returns
     * the number of rows merged. Disabled history discards the batch (the caller still acks it).
     */
    suspend fun ingestDaemonBatch(context: Context, batch: DaemonMonitorPolicy.Batch, now: Long = System.currentTimeMillis()): Int = withContext(Dispatchers.IO) {
        val prefs = context.getSharedPreferences("hetu", 0)
        if (!prefs.getBoolean("proxyApiHistoryEnabled", false)) return@withContext 0
        gate.withLock {
            val db = database(context)
            val seen = db.rawQuery("SELECT value FROM history_meta WHERE key='daemonBatch'", null).use { if (it.moveToFirst()) it.getString(0) else "" }
            if (seen == batch.id) return@withLock 0
            val cutoff = (now - prefs.getInt("proxyApiHistoryRetentionDays", 7).coerceIn(1, 90) * 86_400_000L) / 1000
            var merged = 0
            db.beginTransaction()
            try {
                val update = db.compileStatement("UPDATE traffic_dimension_buckets SET upload=upload+?, download=download+?, connections=connections+? WHERE minute=? AND dim=? AND key=?")
                val insert = db.compileStatement("INSERT INTO traffic_dimension_buckets(minute,dim,key,upload,download,connections) VALUES(?,?,?,?,?,?)")
                for (row in batch.rows) {
                    if (row.minute < cutoff) continue
                    update.clearBindings()
                    update.bindLong(1, row.upload); update.bindLong(2, row.download); update.bindLong(3, row.connections.toLong())
                    update.bindLong(4, row.minute); update.bindString(5, row.dimension); update.bindString(6, row.key)
                    if (update.executeUpdateDelete() == 0) {
                        insert.clearBindings()
                        insert.bindLong(1, row.minute); insert.bindString(2, row.dimension); insert.bindString(3, row.key)
                        insert.bindLong(4, row.upload); insert.bindLong(5, row.download); insert.bindLong(6, row.connections.toLong())
                        insert.executeInsert()
                    }
                    merged++
                }
                for ((minute, value) in batch.totals) {
                    if (minute < cutoff) continue
                    db.execSQL("INSERT OR IGNORE INTO traffic_minutes(minute,upload,download) VALUES(?,0,0)", arrayOf(minute))
                    db.execSQL("UPDATE traffic_minutes SET upload=upload+?, download=download+? WHERE minute=?", arrayOf(value[0], value[1], minute))
                }
                db.execSQL("INSERT OR REPLACE INTO history_meta(key,value) VALUES('daemonBatch',?)", arrayOf(batch.id))
                db.setTransactionSuccessful()
            } finally { db.endTransaction() }
            pruneLocked(db, prefs, now)
            lastPrune = now
            merged
        }
    }

    /** Top list of one daemon dimension (app / host / ip / outbound / rule) since [since] (ms). */
    suspend fun dimensionRanking(context: Context, dimension: String, sort: String, since: Long, limit: Int = 20): List<TrafficRank> = withContext(Dispatchers.IO) {
        if (!context.getSharedPreferences("hetu", 0).getBoolean("proxyApiHistoryEnabled", false)) return@withContext emptyList()
        if (dimension !in DaemonMonitorPolicy.DIMENSIONS) return@withContext emptyList()
        val order = if (sort == "traffic") "sum(upload)+sum(download)" else "sum(connections)"
        gate.withLock {
            database(context).rawQuery("SELECT key,sum(connections),sum(upload),sum(download) FROM traffic_dimension_buckets WHERE dim=? AND minute>=? GROUP BY key ORDER BY $order DESC, key LIMIT ?",
                arrayOf(dimension, (since / 1000).toString(), limit.coerceIn(1, 100).toString())).use { rows ->
                buildList { while (rows.moveToNext()) add(TrafficRank(rows.getString(0).orEmpty().ifBlank { "—" }, rows.getInt(1), rows.getLong(2), rows.getLong(3))) }
            }
        }
    }

    /** Controller totals (upload, download) since [since] (ms), counted by the daemon. */
    suspend fun rangeTotals(context: Context, since: Long): Pair<Long, Long> = withContext(Dispatchers.IO) {
        if (!context.getSharedPreferences("hetu", 0).getBoolean("proxyApiHistoryEnabled", false)) return@withContext 0L to 0L
        gate.withLock {
            database(context).rawQuery("SELECT coalesce(sum(upload),0),coalesce(sum(download),0) FROM traffic_minutes WHERE minute>=?", arrayOf((since / 1000).toString())).use {
                if (it.moveToFirst()) it.getLong(0) to it.getLong(1) else 0L to 0L
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
    /**
     * Daemon controller totals in slots of [bucketSeconds] from [since] (ms) to [now]: (slot start ms, upload, download),
     * oldest first, with empty slots as zero so the time axis is continuous.
     */
    suspend fun timeDistribution(context: Context, since: Long, bucketSeconds: Long, now: Long = System.currentTimeMillis()): List<Triple<Long, Long, Long>> = withContext(Dispatchers.IO) {
        if (!context.getSharedPreferences("hetu", 0).getBoolean("proxyApiHistoryEnabled", false)) return@withContext emptyList()
        val bucket = bucketSeconds.coerceAtLeast(60L)
        val read = gate.withLock {
            database(context).rawQuery("SELECT (minute / ?) * ? AS slot,sum(upload),sum(download) FROM traffic_minutes WHERE minute>=? GROUP BY slot ORDER BY slot",
                arrayOf(bucket.toString(), bucket.toString(), (since / 1000).toString())).use { rows ->
                buildMap { while (rows.moveToNext()) put(rows.getLong(0), rows.getLong(1) to rows.getLong(2)) }
            }
        }
        distributionSlots(read, since / 1000, now / 1000, bucket)
    }

    /** Continuous slots from the one holding [fromSec] to the one holding [toSec]; at most 400. */
    internal fun distributionSlots(read: Map<Long, Pair<Long, Long>>, fromSec: Long, toSec: Long, bucket: Long): List<Triple<Long, Long, Long>> {
        if (toSec < fromSec || bucket <= 0L) return emptyList()
        val first = (fromSec / bucket) * bucket
        val last = (toSec / bucket) * bucket
        val start = maxOf(first, last - 399L * bucket)
        return generateSequence(start) { it + bucket }.takeWhile { it <= last }
            .map { slot -> read[slot].let { Triple(slot * 1000L, it?.first ?: 0L, it?.second ?: 0L) } }.toList()
    }

    suspend fun ranking(context: Context, dimension: String, sort: String, since: Long, limit: Int = 20): List<TrafficRank> = withContext(Dispatchers.IO) {
        if (!context.getSharedPreferences("hetu", 0).getBoolean("proxyApiHistoryEnabled", false)) return@withContext emptyList()
        val column = when(dimension) { "app" -> "app"; "route" -> "route"; else -> "host" }
        val order = if(sort == "traffic") "sum(upload)+sum(download)" else "count(*)"
        gate.withLock {
            database(context).rawQuery("SELECT $column,count(*),sum(upload),sum(download) FROM connections WHERE seen >= ? GROUP BY $column ORDER BY $order DESC LIMIT ?", arrayOf(since.toString(), limit.coerceIn(1, 100).toString())).use { rows ->
                buildList { while(rows.moveToNext()) add(TrafficRank(rows.getString(0).orEmpty().ifBlank { "—" }, rows.getInt(1), rows.getLong(2), rows.getLong(3))) }
            }
        }
    }
    suspend fun clear(context: Context) = withContext(Dispatchers.IO) {
        gate.withLock { database(context).apply { delete("traffic", null, null); delete("connections", null, null); delete("traffic_dimension_buckets", null, null); delete("traffic_minutes", null, null); execSQL("VACUUM") }; lastRecord = 0; lastPrune = 0 }
        context.getSharedPreferences("hetu", 0).edit().remove("proxyApiTrafficHistory").apply()
    }
}
