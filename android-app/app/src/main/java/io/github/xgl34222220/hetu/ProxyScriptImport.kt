package io.github.xgl34222220.hetu

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import java.io.InputStream

internal object ProxyScriptImport {
    const val MAX_BYTES = 64 * 1024
    data class Source(val name: String, val bytes: ByteArray)

    suspend fun read(context: Context, uri: Uri): Source = withContext(Dispatchers.IO) {
        val name = context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use {
            if (it.moveToFirst()) it.getString(0) else null
        } ?: uri.lastPathSegment ?: "script.sh"
        val bytes = context.contentResolver.openInputStream(uri)?.use(::readBounded)
            ?: throw IllegalStateException("无法读取所选脚本")
        Source(name, bytes)
    }

    internal fun readBounded(input: InputStream): ByteArray {
        val output = ByteArrayOutputStream(8192)
        val buffer = ByteArray(8192)
        while (true) {
            val count = input.read(buffer, 0, minOf(buffer.size, MAX_BYTES + 1 - output.size()))
            if (count < 0) break
            if (count == 0) {
                val one = input.read()
                if (one < 0) break
                require(output.size() < MAX_BYTES) { "脚本最多 64 KiB" }
                output.write(one)
            } else {
                require(output.size() + count <= MAX_BYTES) { "脚本最多 64 KiB" }
                output.write(buffer, 0, count)
            }
        }
        return output.toByteArray()
    }
}
