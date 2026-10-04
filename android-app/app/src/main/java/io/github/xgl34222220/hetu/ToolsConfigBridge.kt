package io.github.xgl34222220.hetu

import android.content.Context
import java.io.File
import java.io.IOException
import java.io.InputStream

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
