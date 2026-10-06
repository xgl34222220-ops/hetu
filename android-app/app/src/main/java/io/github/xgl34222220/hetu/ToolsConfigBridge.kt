package io.github.xgl34222220.hetu

import android.content.Context
import java.io.File
import java.io.IOException
import java.io.InputStream
import io.github.xgl34222220.hetu.tools.ToolsConfigDocument
import io.github.xgl34222220.hetu.tools.ToolsSaveResult
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * The three config-library operations the 工具 module needs that `ProxyComposeController` does
 * not offer: rename, read a config other than the selected one, and import from a stream.
 *
 * It lives in the root package because `ProxyConfigLibrary` and `ProxyRuntimeProfile` are
 * package-private Java classes; `hetu.tools` cannot reach them directly. No existing file is
 * modified. All three calls block on file I/O: call them off the main thread.
 */
internal object ToolsConfigBridge {
    private class Session(context: Context) {
        val library = ProxyConfigLibrary(context.applicationContext)
        val core: ProxyRuntimeProfile.Core =
            ProxyRuntimeProfile.load(context.applicationContext.getSharedPreferences("hetu", Context.MODE_PRIVATE)).core

        fun entry(name: String): ProxyConfigLibrary.Entry =
            library.list(core).firstOrNull { it.name == name } ?: throw IOException("配置不存在")
    }

    /** Text of the config called [name], selected or not. */
    @Throws(IOException::class)
    fun read(context: Context, name: String): String = Session(context).run { library.read(entry(name)) }

    fun document(context: Context): ToolsConfigDocument = Session(context).run {
        val selected = library.selected(core) ?: throw IOException("尚未选择配置")
        ToolsConfigDocument(selected.name, library.read(selected), core.id)
    }

    /** Same identity checks as the established Sora editor; validation cannot retarget the write. */
    suspend fun saveDocument(
        context: Context,
        expected: ToolsConfigDocument,
        text: String,
        validate: suspend (String) -> Unit,
    ): ToolsSaveResult {
        suspend fun checkSource(): ToolsSaveResult? = withContext(Dispatchers.IO) {
            val current = document(context)
            when {
                current.coreId != expected.coreId || current.name != expected.name -> ToolsSaveResult.SelectionChanged(current.name)
                current.text != expected.text -> ToolsSaveResult.SourceChanged
                else -> null
            }
        }
        checkSource()?.let { return it }
        validate(text)
        return withContext(Dispatchers.IO) {
            Session(context).run {
                val selected = library.selected(core) ?: throw IOException("尚未选择配置")
                when {
                    core.id != expected.coreId || selected.name != expected.name -> ToolsSaveResult.SelectionChanged(selected.name)
                    library.read(selected) != expected.text -> ToolsSaveResult.SourceChanged
                    else -> { library.write(selected, text); ToolsSaveResult.Saved }
                }
            }
        }
    }

    /**
     * Stores [source] as a new config and selects it, exactly like a file import: a taken name
     * becomes “name (2).yaml”, the 4 MiB limit applies. Closes [source]. Returns the stored name.
     */
    @Throws(IOException::class)
    fun importStream(context: Context, requestedName: String, source: InputStream): String =
        Session(context).run { library.importConfig(core, requestedName, source).name }

    /**
     * Renames a config in place. The selection follows the file when the renamed config is the
     * current one. The bundled template cannot be renamed. Returns the new name.
     */
    @Throws(IOException::class)
    fun rename(context: Context, from: String, to: String): String = Session(context).run {
        val source = entry(from)
        if (source.name == ProxyConfigLibrary.BUNDLED_NAME) throw IOException("内置配置不能重命名")
        val name = ProxyConfigLibrary.safeName(to)
        if (!ProxyConfigLibrary.coreAccepts(core, name)) throw IOException("${core.label} 不支持该配置格式")
        if (name == source.name) return@run name
        val target = File(source.file.parentFile, name)
        if (target.exists()) throw IOException("已存在同名配置")
        val wasSelected = library.selected(core)?.name == source.name
        if (!source.file.renameTo(target)) throw IOException("无法重命名配置")
        if (wasSelected) library.select(core, name)
        name
    }
}
