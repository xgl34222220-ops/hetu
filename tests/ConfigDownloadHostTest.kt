package io.github.xgl34222220.hetu

// INSERT_PRODUCTION_IMPORTS
import android.content.SharedPreferences
import java.io.File
import java.io.InputStream
import java.net.URLStreamHandler
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.Job
import kotlinx.coroutines.runBlocking

// INSERT_EXACT_PRODUCTION_FUNCTIONS

private var checks = 0
private fun verify(value: Boolean, label: String) {
    if (!value) throw AssertionError(label)
    checks++
}

private class MemoryPrefs : SharedPreferences {
    val values = ConcurrentHashMap<String, String>()
    var writes = 0
    override fun getString(key: String, fallback: String): String = values[key] ?: fallback
    override fun edit(): SharedPreferences.Editor = object : SharedPreferences.Editor {
        val updates = mutableMapOf<String, String>()
        val removed = mutableSetOf<String>()
        override fun putString(key: String, value: String) = apply { updates[key] = value }
        override fun remove(key: String) = apply { removed.add(key) }
        override fun apply() {
            writes++
            removed.forEach(values::remove)
            values.putAll(updates)
        }
    }
}

private class HostContext(val directory: File) : Context() {
    val prefs = MemoryPrefs()
    var beforePreferences: () -> Unit = {}
    override fun getFilesDir(): File = directory
    override fun getSharedPreferences(name: String, mode: Int): SharedPreferences {
        beforePreferences()
        return prefs
    }
}

/** Generates a continuous body with an assertion fuse so a regression cannot exhaust memory. */
private class BodyStream(
    val size: Int,
    val content: ByteArray = "proxies: []\n#".toByteArray(),
    val failAt: Int = Int.MAX_VALUE,
    val chunk: Int = 8192,
    val zeroReads: Boolean = false,
) : InputStream() {
    var consumed = 0
    var closed = false
    var requests = 0
    var onRead: () -> Unit = {}
    private fun nextByte(): Int {
        if (consumed > ConfigDownloadReader.LIMIT_BYTES) throw AssertionError("Reader crossed the limit + 1 safety fuse")
        if (consumed >= failAt) throw IOException("synthetic read failure")
        if (size >= 0 && consumed >= size) return -1
        val value = if (consumed < content.size) content[consumed].toInt() and 255 else 'a'.code
        consumed++
        return value
    }
    override fun read(): Int {
        requests++
        onRead()
        return nextByte()
    }
    override fun read(bytes: ByteArray, offset: Int, length: Int): Int {
        requests++
        onRead()
        if (zeroReads) return 0
        if (consumed >= failAt) throw IOException("synthetic read failure")
        if (size >= 0 && consumed >= size) return -1
        val count = minOf(length, chunk, failAt - consumed, if (size < 0) Int.MAX_VALUE else size - consumed)
        repeat(count) { bytes[offset + it] = nextByte().toByte() }
        return count
    }
    override fun close() { closed = true }
}

private class FixtureConnection(val body: BodyStream, val length: Long, val status: Int = 200) : HttpURLConnection(URL("fixture://local/config")) {
    var disconnected = false
    var bodyOpened = false
    override fun getResponseCode(): Int = status
    override fun getContentLengthLong(): Long = length
    override fun getInputStream(): InputStream { bodyOpened = true; return body }
    override fun getHeaderField(name: String): String? = null
    override fun disconnect() { disconnected = true }
    override fun usingProxy() = false
    override fun connect() {}
}

private var nextConnection: FixtureConnection? = null
private fun filesystem(context: HostContext): Map<String, List<Byte>> = context.directory.walkTopDown()
    .filter { it.isFile }.associate { it.relativeTo(context.directory).path to it.readBytes().toList() }

private fun reject(
    context: HostContext,
    label: String,
    stream: BodyStream,
    length: Long = -1,
    status: Int = 200,
    cancelled: Boolean = false,
    prepare: (Job) -> Unit = {},
): FixtureConnection {
    val filesBefore = filesystem(context)
    val prefsBefore = context.prefs.values.toMap()
    val writesBefore = context.prefs.writes
    val connection = FixtureConnection(stream, length, status)
    nextConnection = connection
    try {
        runBlocking {
            prepare(coroutineContext[Job]!!)
            downloadConfig(context, "fixture://local/config", "rejected.yaml")
        }
        throw AssertionError("$label unexpectedly accepted")
    } catch (failure: Exception) {
        verify(if (cancelled) failure is CancellationException else failure is IOException, "$label preserves failure type: $failure")
    } finally {
        context.beforePreferences = {}
    }
    verify(filesystem(context) == filesBefore, "$label leaves every configuration byte and file unchanged")
    verify(context.prefs.values == prefsBefore && context.prefs.writes == writesBefore, "$label leaves selection and preferences untouched")
    verify(connection.disconnected, "$label disconnects")
    verify(!connection.bodyOpened || stream.closed, "$label closes the opened response")
    verify(stream.consumed <= ConfigDownloadReader.LIMIT_BYTES + 1, "$label does not consume beyond limit + 1")
    return connection
}

private fun accept(context: HostContext, stream: BodyStream, length: Long, name: String) {
    val connection = FixtureConnection(stream, length)
    nextConnection = connection
    val entry = runBlocking { downloadConfig(context, "fixture://local/config", name) }
    verify(entry.file.length() == stream.size.toLong(), "$name persists all valid bytes")
    verify(context.prefs.values["proxySelectedConfig.mihomo"] == entry.name, "$name keeps successful import selection behavior")
    verify(stream.closed && connection.disconnected, "$name closes and disconnects")
    verify(stream.consumed == stream.size, "$name reads complete body")
    val expected = ByteArray(stream.size) { index -> if (index < stream.content.size) stream.content[index] else 'a'.code.toByte() }
    verify(entry.file.readBytes().contentEquals(expected), "$name preserves exact bytes including Unicode")
    // Remove successful fixtures only from this temporary host directory to keep later comparisons small.
    entry.file.delete()
    context.prefs.values["proxySelectedConfig.mihomo"] = "baseline.yaml"
}

fun main(args: Array<String>) {
    URL.setURLStreamHandlerFactory { protocol ->
        if (protocol != "fixture") throw AssertionError("External networking is forbidden: $protocol")
        object : URLStreamHandler() {
            override fun openConnection(url: URL): HttpURLConnection = nextConnection
                ?: throw AssertionError("No synthetic connection supplied")
        }
    }
    val context = HostContext(File(args[0]).apply { mkdirs() })
    val original = "proxies: []\n# untouched original\n"
    ProxyConfigLibrary(context).importConfig(ProxyRuntimeProfile.Core.MIHOMO, "baseline.yaml", original.byteInputStream())
    val limit = ConfigDownloadReader.LIMIT_BYTES
    val libraryLimit = ProxyConfigLibrary::class.java.getDeclaredField("LIMIT").apply { isAccessible = true }.getInt(null)
    verify(limit == libraryLimit, "download limit matches the production config library")

    accept(context, BodyStream(limit), limit.toLong(), "exact-limit.yaml")
    accept(context, BodyStream(32), -1, "unknown-length.yaml")
    accept(context, BodyStream(32), 1, "underreported-length.yaml")
    accept(context, BodyStream(32), 0, "false-zero-length.yaml")
    accept(context, BodyStream(32), 100, "overreported-within-limit.yaml")
    accept(context, BodyStream(32, zeroReads = true), -1, "zero-returning-stream.yaml")

    for (declared in listOf(-1L, 0L, 1L, limit.toLong())) {
        val body = BodyStream(limit + 1)
        reject(context, "oversize with Content-Length $declared", body, declared)
        verify(body.consumed == limit + 1, "oversize consumes only one sentinel byte")
    }
    val endless = BodyStream(-1)
    reject(context, "endless response", endless)
    verify(endless.consumed == limit + 1, "endless response stops at the sentinel")
    val early = BodyStream(32)
    reject(context, "huge declared response", early, Long.MAX_VALUE)
    verify(early.consumed == 0, "large length hint rejects before reading any bytes")
    reject(context, "mid-body IO failure", BodyStream(100, failAt = 17))
    reject(context, "empty response", BodyStream(0))
    reject(context, "non-config response", BodyStream(8, "<html />".toByteArray()))
    val httpError = reject(context, "HTTP error response", BodyStream(-1), status = 503)
    verify(!httpError.bodyOpened, "HTTP error never opens the response body")

    val prefix = "proxies: []\n#".toByteArray()
    for (invalid in listOf(byteArrayOf(0xC3.toByte()), byteArrayOf(0x80.toByte()), byteArrayOf(0xC0.toByte(), 0xAF.toByte()), byteArrayOf(0xED.toByte(), 0xA0.toByte(), 0x80.toByte()), byteArrayOf(0xF4.toByte(), 0x90.toByte(), 0x80.toByte(), 0x80.toByte()))) {
        val bytes = prefix + invalid
        reject(context, "malformed UTF-8 ${invalid.size} bytes", BodyStream(bytes.size, bytes, chunk = 1))
    }
    val splitUnicode = ByteArray(8191) { 'a'.code.toByte() }.also { prefix.copyInto(it) } + "河图🙂".toByteArray()
    accept(context, BodyStream(splitUnicode.size, splitUnicode), -1, "unicode-buffer-boundary.yaml")
    val unicodeAtLimit = ByteArray(limit) { 'a'.code.toByte() }.also {
        prefix.copyInto(it)
        "🙂".toByteArray().copyInto(it, limit - 4)
    }
    accept(context, BodyStream(limit, unicodeAtLimit), -1, "unicode-exact-limit.yaml")
    val truncatedAtLimit = unicodeAtLimit.copyOf().also { it[limit - 1] = 0xF0.toByte() }
    reject(context, "invalid UTF-8 at byte limit", BodyStream(limit, truncatedAtLimit))

    val cancelDuringRead = BodyStream(-1)
    reject(context, "cancel while reading", cancelDuringRead, cancelled = true, prepare = { job ->
        cancelDuringRead.onRead = { if (cancelDuringRead.consumed >= 8192) job.cancel() }
    })
    verify(cancelDuringRead.consumed <= 16384, "cancellation stops at the next bounded read")
    val cancelAtEof = BodyStream(32)
    reject(context, "cancel at EOF", cancelAtEof, cancelled = true, prepare = { job ->
        cancelAtEof.onRead = { if (cancelAtEof.consumed == 32) job.cancel() }
    })
    reject(context, "cancel just before import", BodyStream(32), cancelled = true, prepare = { job ->
        context.beforePreferences = { job.cancel() }
    })
    verify(context.directory.walkTopDown().none { it.name.endsWith(".new") }, "no partial configuration files survive")
    println("ConfigDownloadHostTest passed: $checks checks; exact production download function, synthetic streams and real temporary file IO; no network")
}
