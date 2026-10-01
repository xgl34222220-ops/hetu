package io.github.xgl34222220.hetu

import android.app.Application
import android.net.Uri
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import java.io.ByteArrayInputStream
import java.io.File
import java.io.IOException
import java.io.InputStream

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
class PolicyIconImportTest {
    private val app get() = ApplicationProvider.getApplicationContext<Application>()
    private val prefs get() = app.getSharedPreferences("policy-icon-import-test", 0)
    private lateinit var previous: File
    private lateinit var before: String

    @Before fun prepare() {
        prefs.edit().clear().commit()
        File(app.filesDir, "policy-icons").deleteRecursively()
        previous = File(app.filesDir, "policy-icons/fixture.img")
        previous.parentFile!!.mkdirs()
        previous.writeBytes(byteArrayOf(4, 5, 6))
        ProxyPolicyIconOverrides.put(prefs, ProxyPolicyIconOverrides.Entry("测试策略", path = previous.absolutePath))
        before = prefs.getString(ProxyPolicyIconOverrides.PREF_KEY, null)!!
    }

    private fun assertPreviousIntact() {
        assertEquals(before, prefs.getString(ProxyPolicyIconOverrides.PREF_KEY, null))
        assertArrayEquals(byteArrayOf(4, 5, 6), previous.readBytes())
        assertEquals(listOf(previous.name), previous.parentFile!!.listFiles()!!.map { it.name })
    }

    @Test fun oversizedProviderStopsReadingClosesAndPreservesPreviousIcon() {
        var bytesRead = 0L
        var closed = false
        val input = object : InputStream() {
            override fun read(): Int { bytesRead++; return 7 }
            override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
                buffer.fill(7, offset, offset + length)
                bytesRead += length
                return length
            }
            override fun close() { closed = true }
        }
        val uri = Uri.parse("content://hetu-test/icons/unbounded")
        shadowOf(app.contentResolver).registerInputStream(uri, input)
        assertThrows(IllegalArgumentException::class.java) { ProxyPolicyIconOverrides.importLocal(app, prefs, "测试策略", uri) }
        assertEquals(PolicyIconInput.MAX_BYTES + 1L, bytesRead)
        assertTrue(closed)
        assertPreviousIntact()
    }

    @Test fun emptyAndFailedProvidersPreservePreviousIcon() {
        val uri = Uri.parse("content://hetu-test/icons/failure")
        shadowOf(app.contentResolver).registerInputStream(uri, ByteArrayInputStream(byteArrayOf()))
        assertThrows(IllegalArgumentException::class.java) { ProxyPolicyIconOverrides.importLocal(app, prefs, "测试策略", uri) }
        assertPreviousIntact()
        var closed = false
        val expected = IOException("synthetic document read failed")
        shadowOf(app.contentResolver).registerInputStream(uri, object : InputStream() {
            override fun read(): Int = throw expected
            override fun close() { closed = true }
        })
        assertSame(expected, assertThrows(IOException::class.java) { ProxyPolicyIconOverrides.importLocal(app, prefs, "测试策略", uri) })
        assertTrue(closed)
        assertPreviousIntact()
    }

    @Test fun exactSizeLimitImportsTheSameBytesAndThenReplacesPreviousIcon() {
        val bytes = ByteArray(PolicyIconInput.MAX_BYTES) { (it % 251).toByte() }
        val uri = Uri.parse("content://hetu-test/icons/exact-limit")
        shadowOf(app.contentResolver).registerInputStream(uri, ByteArrayInputStream(bytes))
        val imported = ProxyPolicyIconOverrides.importLocal(app, prefs, "测试策略", uri)
        assertEquals(imported, ProxyPolicyIconOverrides.get(prefs, "测试策略"))
        assertArrayEquals(bytes, File(imported.path).readBytes())
        assertFalse(previous.exists())
        assertEquals(1, previous.parentFile!!.listFiles()!!.size)
    }
}
