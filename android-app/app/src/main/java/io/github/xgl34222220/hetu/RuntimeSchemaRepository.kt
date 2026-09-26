package io.github.xgl34222220.hetu

import android.content.Context
import android.util.AtomicFile
import com.fasterxml.jackson.core.JsonParser
import com.fasterxml.jackson.databind.ObjectMapper
import com.networknt.schema.JsonSchemaFactory
import com.networknt.schema.SpecVersion
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.net.HttpURLConnection
import java.net.URL

/** The editor only loads this official schema; configuration $schema URLs are never fetched. */
internal object RuntimeSchemaRepository {
    const val URL = "https://sing-box.sagernet.org/schema.json"
    private val mapper = ObjectMapper().enable(JsonParser.Feature.STRICT_DUPLICATE_DETECTION).enable(com.fasterxml.jackson.databind.DeserializationFeature.FAIL_ON_TRAILING_TOKENS)
    private fun cache(context: Context) = File(context.filesDir, "schema/sing-box-official.json")
    fun parse(text: String) = mapper.readTree(text).also { requireNotNull(it) { "JSON 内容为空" } }
    suspend fun validate(context: Context, text: String): String = withContext(Dispatchers.IO) {
        val node = parse(text)
        val schemaText = cache(context).takeIf { it.isFile }?.readText()
            ?: context.assets.open("schema/sing-box-official.json").bufferedReader().use { it.readText() }
        val schema = JsonSchemaFactory.getInstance(SpecVersion.VersionFlag.V202012).getSchema(schemaText)
        val errors = schema.validate(node)
        require(errors.isEmpty()) { errors.take(8).joinToString("\n") { it.message } }
        "sing-box Schema 校验通过；实际兼容性仍以所安装核心的 check 结果为准"
    }
    suspend fun update(context: Context) = withContext(Dispatchers.IO) {
        val connection = (java.net.URL(URL).openConnection() as HttpURLConnection).apply {
            connectTimeout = 10000; readTimeout = 15000; instanceFollowRedirects = false
        }
        try {
            check(connection.responseCode == 200) { "Schema 下载失败：HTTP ${connection.responseCode}" }
            val bytes = connection.inputStream.use { it.readBytesBounded(1024 * 1024) }
            val text = bytes.toString(Charsets.UTF_8)
            val node = parse(text)
            require(node.path("\$id").asText() == URL && node.has("\$defs")) { "Schema 来源或结构无效" }
            JsonSchemaFactory.getInstance(SpecVersion.VersionFlag.V202012).getSchema(text)
            val file = cache(context); file.parentFile!!.mkdirs()
            val atomic = AtomicFile(file); val out = atomic.startWrite()
            try { out.write(bytes); atomic.finishWrite(out) } catch (error: Exception) { atomic.failWrite(out); throw error }
        } finally { connection.disconnect() }
    }
    private fun java.io.InputStream.readBytesBounded(limit: Int): ByteArray {
        val output = java.io.ByteArrayOutputStream(); val buffer = ByteArray(8192)
        while (true) { val count = read(buffer); if (count < 0) break; require(output.size() + count <= limit) { "Schema 文件过大" }; output.write(buffer, 0, count) }
        return output.toByteArray()
    }
}
