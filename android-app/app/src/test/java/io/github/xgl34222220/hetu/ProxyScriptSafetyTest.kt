package io.github.xgl34222220.hetu

import android.app.Application
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.ByteArrayInputStream
import java.io.InputStream

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
class ProxyScriptSafetyTest {
    @Test fun distinctExistingNamesNeverCollapseIntoTheSameLazyListKey() {
        val files = ProxyScriptHooks.parseManaged("a b.sh\t12\na_b.sh\t34\n")
        assertEquals(listOf("a b.sh", "a_b.sh"), files.map { it.name })
        assertEquals(2, files.map { it.path }.toSet().size)
    }
    @Test fun actualUnicodeFilesystemIdentityIsPreserved() {
        val file = ProxyScriptHooks.parseManaged("联网检查.sh\t21\n").single()
        assertEquals("/data/adb/hetu/scripts/联网检查.sh", file.path)
    }
    @Test fun repeatedOutputCannotCreateDuplicateComposeKeys() {
        assertEquals(1, ProxyScriptHooks.parseManaged("check.sh\t12\ncheck.sh\t12\n").size)
    }
    @Test fun hooksTraversalAndMalformedSizeAreRejected() {
        assertEquals(emptyList<ManagedScript>(), ProxyScriptHooks.parseManaged("pre-start.sh\t12\npost-stop.sh\t12\n../evil.sh\t12\ncheck.sh\t-1\ncheck.sh\tx\ncheck.sh\t1\textra\n"))
    }
    @Test fun emptyListingIsSupported() { assertTrue(ProxyScriptHooks.parseManaged("").isEmpty()) }
    @Test fun exactLimitIsAccepted() {
        val expected = ByteArray(65536) { 97 }
        assertArrayEquals(expected, ProxyScriptImport.readBounded(ByteArrayInputStream(expected)))
    }
    @Test fun oversizedStreamStopsAfterAtMostLimitPlusOne() {
        var read = 0
        val input = object : InputStream() {
            override fun read(): Int { read++; return 97 }
            override fun read(bytes: ByteArray, off: Int, len: Int): Int {
                bytes.fill(97, off, off + len); read += len; return len
            }
        }
        try { ProxyScriptImport.readBounded(input); fail("infinite provider must be bounded") }
        catch (error: IllegalArgumentException) { assertTrue(error.message.orEmpty().contains("64 KiB")) }
        assertEquals(65537, read)
    }
    @Test fun shortReadsAreNotMistakenForEndOfStream() {
        val expected = "#!/system/bin/sh\nprintf 'hi'\n".toByteArray()
        val input = object : ByteArrayInputStream(expected) {
            override fun read(bytes: ByteArray, off: Int, len: Int): Int = super.read(bytes, off, minOf(3, len))
        }
        assertArrayEquals(expected, ProxyScriptImport.readBounded(input))
    }
    @Test fun zeroByteProviderReadCannotSpinForever() {
        val source = ByteArrayInputStream("abc".toByteArray())
        val input = object : InputStream() {
            override fun read() = source.read()
            override fun read(bytes: ByteArray, off: Int, len: Int) = if (source.available() > 0) 0 else -1
        }
        assertArrayEquals("abc".toByteArray(), ProxyScriptImport.readBounded(input))
    }
    @Test fun specialFilenameIsExecutedAsOneFileAndCannotInjectLogCommands() {
        val folder = java.nio.file.Files.createTempDirectory("hetu-script-command-").toFile()
        try {
            val scripts = java.io.File(folder, "scripts").apply { mkdirs() }
            val run = java.io.File(folder, "run").apply { mkdirs() }
            val name = "quote'; touch owned; echo '.sh"
            java.io.File(scripts, name).writeText("printf ran\n")
            val command = ProxyScriptHooks.managedCommand(name, "tproxy", "config' name")
                .replace(ProxyScriptHooks.ROOT, scripts.absolutePath).replace("/data/adb/hetu/run", run.absolutePath)
            val process = ProcessBuilder("sh", "-c", command).directory(folder).start()
            assertEquals(0, process.waitFor())
            assertFalse(java.io.File(folder, "owned").exists())
            val log = java.io.File(run, "scripts.log").readText()
            assertTrue(log.contains(name)); assertTrue(log.contains("ran"))
        } finally { folder.deleteRecursively() }
    }
    @Test fun emptyScriptIsStillSupported() {
        assertEquals(0, ProxyScriptImport.readBounded(ByteArrayInputStream(byteArrayOf())).size)
    }
}
