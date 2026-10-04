package io.github.xgl34222220.hetu

import android.content.Context
import android.net.Uri
import android.util.Base64
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.ByteArrayInputStream
import java.io.File

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class SettingsBackupParity57Test {
    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val core = ProxyRuntimeProfile.Core.MIHOMO
    @Before fun reset() { context.getSharedPreferences("hetu", 0).edit().clear().commit() }
    private fun archive(name: String, text: String, selected: Boolean = true): Uri {
        val settings = JSONObject()
        if (selected) settings.put("proxySelectedConfig.${core.id}", JSONObject().put("t", "s").put("v", name))
        val data = JSONObject().put("schema", 1).put("settings", settings).put("configs", JSONArray().put(
            JSONObject().put("core", core.id).put("name", name).put("data", Base64.encodeToString(text.toByteArray(), Base64.NO_WRAP))))
        return Uri.fromFile(File(context.cacheDir, "backup-${System.nanoTime()}.json").apply { writeText(data.toString()) })
    }
    @Test fun conflictsKeepOriginalAndSelectRestoredCopy() = runBlocking {
        val lib = ProxyConfigLibrary(context)
        val original = lib.importConfig(core, "collision-${System.nanoTime()}.yaml", ByteArrayInputStream("mode: rule\n".toByteArray()))
        HetuSettingsBackup.restore(context, archive(original.name, "mode: direct\n"))
        assertEquals("mode: rule\n", lib.read(original))
        assertNotEquals(original.name, lib.selected(core).name)
        assertEquals("mode: direct\n", lib.read(lib.selected(core)))
    }
    @Test fun identicalFileIsReusedWithoutExtraCopy() = runBlocking {
        val lib = ProxyConfigLibrary(context)
        val original = lib.importConfig(core, "same-${System.nanoTime()}.yaml", ByteArrayInputStream("mode: rule\n".toByteArray()))
        val count = lib.list(core).size
        HetuSettingsBackup.restore(context, archive(original.name, "mode: rule\n"))
        assertEquals(count, lib.list(core).size)
        assertEquals(original.name, lib.selected(core).name)
    }
    @Test fun backupWithoutSelectedKeyKeepsExistingSelection() = runBlocking {
        val lib = ProxyConfigLibrary(context)
        val original = lib.importConfig(core, "selected-${System.nanoTime()}.yaml", ByteArrayInputStream("mode: rule\n".toByteArray()))
        HetuSettingsBackup.restore(context, archive("import-${System.nanoTime()}.yaml", "mode: direct\n", false))
        assertEquals(original.name, lib.selected(core).name)
    }
}
