package io.github.xgl34222220.hetu

import android.content.Context
import android.content.ContextWrapper
import android.content.SharedPreferences
import androidx.test.core.app.ApplicationProvider
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.net.InetAddress
import java.net.ServerSocket
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/** Real TCP requests, original credentials and loopback fixtures; no Root or external service. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class ControllerAuthenticationTest {
    private val context get() = ApplicationProvider.getApplicationContext<Context>()
    private val prefs get() = context.getSharedPreferences("hetu", 0)
    private val localSecret = "fixture-local-credential"
    private val customSecret = "fixture-custom-credential"

    @Before fun reset() { prefs.edit().clear().commit() }

    private fun endpoints(localPort: Int, customPort: Int, custom: Boolean = false) {
        prefs.edit().putBoolean("proxyCustomApiEnabled", custom)
            .putInt("proxyControllerPort", localPort).putString("proxyControllerSecret", localSecret)
            .putString("proxyCustomApiHost", "127.0.0.1").putInt("proxyCustomApiPort", customPort)
            .putString("proxyCustomApiSecret", customSecret).commit()
    }

    private fun authenticated(server: MockWebServer, expected: String) {
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse =
                if (request.getHeader("Authorization") == "Bearer $expected")
                    MockResponse().setBody("{\"version\":\"original-fixture\"}")
                else MockResponse().setResponseCode(401).setBody("credential mismatch")
        }
    }

    @Test fun defaultControllerUsesOnlyItsLocalCredential() {
        MockWebServer().use { local -> MockWebServer().use { custom ->
            local.start(); custom.start(); authenticated(local, localSecret)
            endpoints(local.port, custom.port)
            assertEquals("original-fixture", MihomoControllerClient(context).version().getString("version"))
            val request = local.takeRequest(2, TimeUnit.SECONDS)!!
            assertEquals("Bearer $localSecret", request.getHeader("Authorization"))
            assertEquals(0, custom.requestCount)
        } }
    }

    @Test fun customControllerUsesOnlyTheUserCustomCredential() {
        MockWebServer().use { local -> MockWebServer().use { custom ->
            local.start(); custom.start(); authenticated(custom, customSecret)
            endpoints(local.port, custom.port, custom = true)
            assertEquals("original-fixture", MihomoControllerClient(context).version().getString("version"))
            val request = custom.takeRequest(2, TimeUnit.SECONDS)!!
            assertEquals("Bearer $customSecret", request.getHeader("Authorization"))
            assertEquals(0, local.requestCount)
        } }
    }

    /** Changes the real prefs immediately after the atomic copy, before the request resolves fields. */
    private class SwitchingPreferences(
        private val source: SharedPreferences,
        private val afterCopy: () -> Unit,
    ) : SharedPreferences by source {
        var snapshots = 0
        override fun getAll(): MutableMap<String, *> {
            val snapshot = HashMap(source.all)
            snapshots++
            afterCopy()
            return snapshot
        }
        override fun getBoolean(key: String?, defaultValue: Boolean): Boolean =
            throw AssertionError("Endpoint mode must come from the atomic snapshot")
        override fun getString(key: String?, defaultValue: String?): String? =
            throw AssertionError("Endpoint string must come from the atomic snapshot")
        override fun getInt(key: String?, defaultValue: Int): Int =
            throw AssertionError("Endpoint port must come from the atomic snapshot")
    }

    private fun withPreferences(requestPreferences: SharedPreferences): Context = object : ContextWrapper(context) {
        override fun getApplicationContext(): Context = this
        override fun getSharedPreferences(name: String, mode: Int): SharedPreferences {
            assertEquals("hetu", name)
            return requestPreferences
        }
    }

    @Test fun savingCustomModeCannotForwardAnInflightLocalCredentialToIt() {
        MockWebServer().use { local -> MockWebServer().use { custom ->
            local.start(); custom.start(); authenticated(local, localSecret); authenticated(custom, customSecret)
            endpoints(local.port, custom.port)
            val switching = SwitchingPreferences(prefs) { prefs.edit().putBoolean("proxyCustomApiEnabled", true).commit() }
            val client = MihomoControllerClient(withPreferences(switching))
            assertEquals("original-fixture", client.version().getString("version"))
            assertEquals("Bearer $localSecret", local.takeRequest(2, TimeUnit.SECONDS)!!.getHeader("Authorization"))
            assertEquals(0, custom.requestCount)
            assertEquals(1, switching.snapshots)
            assertEquals("original-fixture", client.version().getString("version"))
            assertEquals("Bearer $customSecret", custom.takeRequest(2, TimeUnit.SECONDS)!!.getHeader("Authorization"))
            assertEquals(2, switching.snapshots)
        } }
    }

    @Test fun savingLocalModeCannotMixItWithInflightCustomCredentials() {
        MockWebServer().use { local -> MockWebServer().use { custom ->
            local.start(); custom.start(); authenticated(local, localSecret); authenticated(custom, customSecret)
            endpoints(local.port, custom.port, custom = true)
            val switching = SwitchingPreferences(prefs) { prefs.edit().putBoolean("proxyCustomApiEnabled", false).commit() }
            val client = MihomoControllerClient(withPreferences(switching))
            assertEquals("original-fixture", client.version().getString("version"))
            assertEquals("Bearer $customSecret", custom.takeRequest(2, TimeUnit.SECONDS)!!.getHeader("Authorization"))
            assertEquals(0, local.requestCount)
            assertEquals(1, switching.snapshots)
            assertEquals("original-fixture", client.version().getString("version"))
            assertEquals("Bearer $localSecret", local.takeRequest(2, TimeUnit.SECONDS)!!.getHeader("Authorization"))
            assertEquals(2, switching.snapshots)
        } }
    }

    @Test fun local401KeepsItsTypedCodeAndSuppressesCredentialBearingBody() {
        MockWebServer().use { server ->
            server.start(); endpoints(server.port, server.port)
            val before = HashMap(prefs.all)
            server.enqueue(MockResponse().setResponseCode(401).setBody("echo $localSecret $customSecret private-response-detail"))
            val error = assertThrows(MihomoControllerClient.ControllerHttpException::class.java) {
                MihomoControllerClient(context).version()
            }
            assertEquals(401, error.statusCode)
            assertFalse(error.customApi)
            assertTrue(error.message!!.contains("本机"))
            assertFalse(error.message!!.contains("自定义"))
            listOf(localSecret, customSecret, "private-response-detail").forEach { assertFalse(error.message!!.contains(it)) }
            assertEquals(before, prefs.all)
            assertEquals(1, server.requestCount)
        }
    }

    @Test fun custom401UsesCapturedModeEvenWhenSettingsChangeBeforeTheReply() {
        MockWebServer().use { server ->
            server.start(); endpoints(server.port, server.port, custom = true)
            server.dispatcher = object : Dispatcher() {
                override fun dispatch(request: RecordedRequest): MockResponse {
                    prefs.edit().putBoolean("proxyCustomApiEnabled", false).commit()
                    return MockResponse().setResponseCode(401).setBody("echo $customSecret private-response-detail")
                }
            }
            val error = assertThrows(MihomoControllerClient.ControllerHttpException::class.java) {
                MihomoControllerClient(context).version()
            }
            assertEquals(401, error.statusCode)
            assertTrue(error.customApi)
            assertTrue(error.message!!.contains("自定义"))
            assertFalse(error.message!!.contains("本机"))
            assertFalse(error.message!!.contains(customSecret))
            assertFalse(error.message!!.contains("private-response-detail"))
            assertEquals(localSecret, prefs.getString("proxyControllerSecret", ""))
            assertEquals(customSecret, prefs.getString("proxyCustomApiSecret", ""))
            assertEquals(1, server.requestCount)
        }
    }

    @Test fun groupDelayDoesNotRetryOtherProbeUrlsAfter401() {
        MockWebServer().use { server ->
            server.start(); endpoints(server.port, server.port)
            server.enqueue(MockResponse().setResponseCode(401).setBody("Unauthorized"))
            server.enqueue(MockResponse().setBody("{\"node\":42}"))
            val error = assertThrows(MihomoControllerClient.ControllerHttpException::class.java) {
                MihomoControllerClient(context).groupDelay("original-group")
            }
            assertEquals(401, error.statusCode)
            assertEquals(1, server.requestCount)
        }
    }

    @Test fun readinessStopsAt401WithoutRotatingCredentialsOrRetrying() {
        MockWebServer().use { server ->
            server.start(); endpoints(server.port, server.port)
            val before = HashMap(prefs.all)
            server.enqueue(MockResponse().setResponseCode(401).setBody("Unauthorized"))
            server.enqueue(MockResponse().setBody("{}"))
            assertFalse(MihomoControllerClient(context).waitReady(10_000L))
            assertEquals(1, server.requestCount)
            assertEquals(before, prefs.all)
        }
    }

    @Test fun utf8CredentialBytesReachTheWireUnchangedInBothModes() {
        val credential = "原创-密钥-é-🔑"
        for (custom in listOf(false, true)) {
            ServerSocket(0, 1, InetAddress.getByName("127.0.0.1")).use { server ->
                val executor = Executors.newSingleThreadExecutor()
                val captured = executor.submit<ByteArray> {
                    server.accept().use { socket ->
                        socket.soTimeout = 3_000
                        val bytes = ByteArrayOutputStream()
                        val input = socket.getInputStream()
                        var state = 0
                        while (state < 4) {
                            val byte = input.read()
                            check(byte >= 0) { "Request headers ended prematurely" }
                            bytes.write(byte)
                            val expected = if (state == 0 || state == 2) 13 else 10
                            state = if (byte == expected) state + 1 else if (byte == 13) 1 else 0
                            check(bytes.size() <= 8192) { "Fixture request too large" }
                        }
                        socket.getOutputStream().write("HTTP/1.1 200 OK\r\nContent-Length: 2\r\nConnection: close\r\n\r\n{}".toByteArray(Charsets.US_ASCII))
                        bytes.toByteArray()
                    }
                }
                try {
                    endpoints(server.localPort, server.localPort, custom)
                    prefs.edit().putString(if (custom) "proxyCustomApiSecret" else "proxyControllerSecret", credential).commit()
                    MihomoControllerClient(context).version()
                    val wire = captured.get(3, TimeUnit.SECONDS)
                    val expected = ("GET /version HTTP/1.1\r\nHost: 127.0.0.1:${server.localPort}\r\n" +
                        "Authorization: Bearer $credential\r\nAccept: application/json\r\nConnection: close\r\n\r\n").toByteArray(Charsets.UTF_8)
                    assertArrayEquals(expected, wire)
                    assertEquals(credential, prefs.getString(if (custom) "proxyCustomApiSecret" else "proxyControllerSecret", ""))
                } finally { executor.shutdownNow() }
            }
        }
    }

    @Test fun invalidCredentialNewlinesAreRejectedBeforeOpeningASocket() {
        MockWebServer().use { server ->
            server.start(); endpoints(server.port, server.port, custom = true)
            prefs.edit().putString("proxyCustomApiSecret", "original\r\nInjected: header").commit()
            assertThrows(IOException::class.java) { MihomoControllerClient(context).version() }
            assertEquals(0, server.requestCount)
        }
    }

    @Test fun missingLocalCredentialCannotFallBackToTheCustomCredential() {
        MockWebServer().use { server ->
            server.start(); endpoints(server.port, server.port)
            prefs.edit().remove("proxyControllerSecret").commit()
            val before = HashMap(prefs.all)
            assertThrows(IOException::class.java) { MihomoControllerClient(context).version() }
            assertEquals(0, server.requestCount)
            assertEquals(before, prefs.all)
        }
    }
}
