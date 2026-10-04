package io.github.xgl34222220.hetu

import android.app.Application
import android.content.Context
import android.content.ContextWrapper
import androidx.test.core.app.ApplicationProvider
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.Assert.*
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File
import java.io.IOException
import java.nio.file.Files
import java.util.Properties

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
class ProxyAdblockExportTest {
    private lateinit var stable: File
    @Before fun prepare() { stable = Files.createTempDirectory("hetu-filter-export-").toFile() }
    @After fun finish() { stable.deleteRecursively() }
    private fun export(revision: String = "g.fixture", block: Set<String> = setOf("blocked.test"), allow: Set<String> = setOf("allow.test")) =
        ProxyAdblockRules.exportSnapshot(stable, revision, block, allow)
    private fun index() = File(stable, "hetu-dns-filter/current.meta")

    @Test fun actualExportSurvivesCacheEvictionDuringRuleLoad() {
        val app = ApplicationProvider.getApplicationContext<Application>()
        var evicted = false
        val context = object : ContextWrapper(app) {
            override fun getApplicationContext(): Context {
                // In the old method this executes after mkdir and before .new is
                // opened, reproducing the reported ENOENT with real production I/O.
                File(super.getCacheDir(), "hetu-dns-filter").deleteRecursively()
                evicted = true
                return app
            }
            override fun getNoBackupFilesDir(): File = stable
        }
        val snapshot = ProxyAdblockRules.export(context)
        assertTrue(evicted)
        assertTrue(snapshot.count > 0)
        assertTrue(snapshot.file.canonicalPath.startsWith(stable.canonicalPath + File.separator))
        assertTrue(snapshot.file.isFile && snapshot.allowFile.isFile)
        assertFalse(File(app.cacheDir, "hetu-dns-filter").exists())
    }

    @Test fun bothProvidersArePublishedSortedFromOneGeneration() {
        val snapshot = export(block = setOf("z.test", "a.test"), allow = setOf("z-allow.test", "a-allow.test"))
        assertEquals("+.a.test\n+.z.test\n", snapshot.file.readText())
        assertEquals("+.a-allow.test\n+.z-allow.test\n", snapshot.allowFile.readText())
        assertEquals(snapshot.file.parentFile, snapshot.allowFile.parentFile)
        val meta = Properties().apply { File(snapshot.file.parentFile, "hetu-adblock.meta").inputStream().use { load(it) } }
        assertEquals(snapshot.revision, meta.getProperty("revision"))
        assertTrue(RuntimeCompatibility14.cachedPairValid(meta, snapshot.file, snapshot.allowFile))
    }

    @Test fun newerExportNeverOverwritesFilesAlreadyBeingHandedToRoot() {
        val old = export("g.old")
        val bytes = old.file.readBytes(); val exceptions = old.allowFile.readBytes()
        val new = export("g.new", setOf("new-block.test"), setOf("new-allow.test"))
        assertNotEquals(old.file.parentFile, new.file.parentFile)
        assertArrayEquals(bytes, old.file.readBytes()); assertArrayEquals(exceptions, old.allowFile.readBytes())
        assertEquals("+.new-block.test\n", new.file.readText())
    }

    @Test fun failedSecondProviderKeepsThePreviousIndexAndPair() {
        val old = export(); val indexBytes = index().readBytes(); val oldBytes = old.file.readBytes()
        try {
            ProxyAdblockRules.exportSnapshot(stable, "g.failed", setOf("new.test"), setOf("allow-new.test")) { domains, target ->
                if (target.name.contains("allow")) throw IOException("injected disk full")
                ProxyAdblockRules.writeProvider(domains, target)
            }
            fail("second provider must fail")
        } catch (error: IOException) { assertEquals("injected disk full", error.message) }
        assertArrayEquals(indexBytes, index().readBytes()); assertArrayEquals(oldBytes, old.file.readBytes())
        assertEquals(old.file, export().file)
        assertFalse(File(stable, "hetu-dns-filter").listFiles()!!.any { it.name.endsWith(".new") })
    }

    @Test fun unchangedRevisionReusesOnlyAHashVerifiedPair() {
        val first = export(); assertEquals(first.file, export().file)
        first.file.writeText("+.changed.test\n")
        val repaired = export()
        assertNotEquals(first.file, repaired.file)
        assertEquals("+.blocked.test\n", repaired.file.readText())
    }

    @Test fun missingAllowFileForcesACompleteFreshPair() {
        val first = export(); assertTrue(first.allowFile.delete())
        val new = export(); assertNotEquals(first.file, new.file)
        assertEquals("+.allow.test\n", new.allowFile.readText())
    }

    @Test fun corruptOrTraversingIndexCannotAuthorizeAnOutsideFile() {
        export(); index().writeText("directory=../../elsewhere\nrevision=filter-v4-sha256:g.fixture\n")
        val new = export()
        assertTrue(new.file.canonicalPath.startsWith(stable.canonicalPath + File.separator))
        assertEquals("+.blocked.test\n", new.file.readText())
    }

    @Test fun unavailableStableDirectoryFailsWithoutTouchingOldSnapshot() {
        val old = export(); val bytes = old.file.readBytes()
        val unavailable = File(stable, "not-a-directory").apply { writeText("sentinel") }
        try {
            ProxyAdblockRules.exportSnapshot(unavailable, "g.failed", setOf("x.test"), emptySet())
            fail("invalid root must fail")
        } catch (_: IOException) { }
        assertEquals("sentinel", unavailable.readText()); assertArrayEquals(bytes, old.file.readBytes())
    }
}
