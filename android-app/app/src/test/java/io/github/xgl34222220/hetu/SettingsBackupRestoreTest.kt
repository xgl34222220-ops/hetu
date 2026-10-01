package io.github.xgl34222220.hetu

import android.app.Application
import android.content.Context
import android.content.ContextWrapper
import android.content.SharedPreferences
import android.net.Uri
import android.util.Base64
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File
import java.nio.file.Files
import java.util.UUID

/** Actual restore/library + Android preferences/JSON/Base64. All data is synthetic. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
class SettingsBackupRestoreTest {
    private lateinit var directory: File
    private lateinit var context: Context
    private lateinit var prefs: FailingPreferences
    private val core = ProxyRuntimeProfile.Core.MIHOMO

    @Before fun prepare() {
        val app = ApplicationProvider.getApplicationContext<Application>()
        directory = Files.createTempDirectory("hetu-backup-restore-").toFile()
        prefs = FailingPreferences(app.getSharedPreferences("backup-test-${UUID.randomUUID()}", 0))
        context = object : ContextWrapper(app) {
            override fun getApplicationContext(): Context = this
            override fun getFilesDir(): File = directory
            override fun getSharedPreferences(name: String, mode: Int): SharedPreferences = prefs
        }
    }

    @After fun clean() {
        directory.deleteRecursively()
    }

    private fun file(name: String) = File(directory, "hetu/configs/mihomo/$name")
    private fun original() = ProxyConfigLibrary(context).importConfig(core, "first.yaml", "original".byteInputStream())
    private fun value(type: String, value: Any) = JSONObject().put("t", type).put("v", value)
    private fun item(name: String, content: String = "restored") = JSONObject().put("core", core.id)
        .put("name", name).put("data", Base64.encodeToString(content.toByteArray(), Base64.NO_WRAP))
    private fun selection() = JSONObject().put("proxySelectedConfig.mihomo", value("s", "first.yaml"))
    private fun restore(settings: JSONObject = JSONObject(), configs: JSONArray = JSONArray()): Result<Int> = runCatching {
        val source = File(directory, "backup.json")
        source.writeText(JSONObject().put("schema", 1).put("settings", settings).put("configs", configs).toString())
        runBlocking { HetuSettingsBackup.restore(context, Uri.fromFile(source)) }
    }

    @Test fun collisionPreservesOriginalAndSelectsIntendedRestoredCopy() {
        original()
        val configs = JSONArray().put(item("first.yaml")).put(item("last.yaml", "last"))
        assertEquals(2, restore(selection(), configs).getOrThrow())
        assertEquals("original", file("first.yaml").readText())
        assertEquals("restored", file("first (2).yaml").readText())
        assertEquals("first (2).yaml", prefs.getString("proxySelectedConfig.mihomo", ""))
        restore(selection(), configs).getOrThrow()
        assertFalse(file("first (3).yaml").exists())
    }

    @Test fun invalidLaterEntryAndKnownPreferenceTypeFailBeforeAnyMutation() {
        original()
        val before = prefs.all
        assertTrue(restore(JSONObject().put("enableBlur", value("b", false)),
            JSONArray().put(item("first.yaml")).put(item("invalid.json"))).isFailure)
        assertTrue(restore(JSONObject().put("appearance", value("b", true)), JSONArray().put(item("first.yaml"))).isFailure)
        assertEquals(before, prefs.all)
        assertEquals("original", file("first.yaml").readText())
        assertFalse(file("first (2).yaml").exists())
    }

    @Test fun invalidUtf8FailsBeforeMutation() {
        original()
        val corrupt = item("last.yaml").put("data", Base64.encodeToString(byteArrayOf(0xc3.toByte(), 0x28), Base64.NO_WRAP))
        assertTrue(restore(selection(), JSONArray().put(item("first.yaml")).put(corrupt)).isFailure)
        assertEquals("original", file("first.yaml").readText())
        assertFalse(file("first (2).yaml").exists())
    }

    @Test fun failedPreferenceCommitRollsBackOnlyAffectedKeysAndOwnCopies() {
        original()
        prefs.edit().putString("unrelated", "keep").putString("proxyCustomApiSecret", "synthetic-only-secret").commit()
        val before = prefs.all
        prefs.failures = 1
        assertTrue(restore(selection(), JSONArray().put(item("first.yaml"))).isFailure)
        assertEquals(before, prefs.all)
        assertEquals("original", file("first.yaml").readText())
        assertFalse(file("first (2).yaml").exists())
    }

    @Test fun unconfirmedPreferenceRollbackRetainsCopiesAndReportsUncertainty() {
        original()
        prefs.failures = 2
        val error = restore(selection(), JSONArray().put(item("first.yaml"))).exceptionOrNull()
        assertTrue(error is ProxyConfigLibrary.RestoreCommitUncertain)
        assertTrue(error?.message.orEmpty().contains("未能确认"))
        assertEquals("original", file("first.yaml").readText())
        assertEquals("restored", file("first (2).yaml").readText())
    }

    @Test fun rollbackPreservesAFileAndPreferenceChangedAfterInstallation() {
        original()
        prefs.failures = 1
        prefs.afterCommit = {
            file("first (2).yaml").writeText("newer write")
            prefs.delegate.edit().putString("proxySelectedConfig.mihomo", "first.yaml")
                .putString("unrelated", "newer value").commit()
        }
        assertTrue(restore(selection(), JSONArray().put(item("first.yaml"))).isFailure)
        assertEquals("newer write", file("first (2).yaml").readText())
        assertEquals("first.yaml", prefs.getString("proxySelectedConfig.mihomo", ""))
        assertEquals("newer value", prefs.getString("unrelated", ""))
    }

    @Test fun retainedSecretCannotBeReboundToAnotherEndpoint() {
        prefs.edit().putBoolean("proxyCustomApiEnabled", true).putString("proxyCustomApiHost", "original.example.invalid")
            .putInt("proxyCustomApiPort", 9090).putString("proxyCustomApiSecret", "synthetic-only-secret").commit()
        val before = prefs.all
        val error = restore(JSONObject().put("proxyCustomApiHost", value("s", "changed.example.invalid")),
            JSONArray().put(item("first.yaml"))).exceptionOrNull()
        assertTrue(error?.message.orEmpty().contains("API"))
        assertEquals(before, prefs.all)
        assertFalse(File(directory, "hetu").exists())
    }

    @Test fun equivalentEndpointAndExcludedSettingsKeepTheExistingSecret() {
        prefs.edit().putString("proxyCustomApiHost", " Original.Example.Invalid ").putInt("proxyCustomApiPort", 80)
            .putString("proxyCustomApiSecret", "synthetic-only-secret").putString("unrelated", "keep").commit()
        restore(JSONObject().put("proxyCustomApiHost", value("s", "original.example.invalid"))
            .put("proxyCustomApiPort", value("i", 9090)).put("proxyCustomApiEnabled", value("b", true))
            .put("proxyCustomApiSecret", value("s", "do-not-import")).put("unrelated", value("s", "do-not-import"))).getOrThrow()
        assertEquals("synthetic-only-secret", prefs.getString("proxyCustomApiSecret", ""))
        assertEquals("keep", prefs.getString("unrelated", ""))
    }

    private class FailingPreferences(val delegate: SharedPreferences) : SharedPreferences by delegate {
        var failures = 0
        var afterCommit: (() -> Unit)? = null
        override fun edit(): SharedPreferences.Editor {
            val editor = delegate.edit()
            return object : SharedPreferences.Editor by editor {
                override fun commit(): Boolean {
                    val committed = editor.commit()
                    afterCommit?.also { afterCommit = null }?.invoke()
                    if (failures > 0) { failures--; return false }
                    return committed
                }
            }
        }
    }
}
