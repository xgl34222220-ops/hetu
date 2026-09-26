package io.github.xgl34222220.hetu

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.util.concurrent.TimeUnit

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class NodeSelectionContinuityTest {
    private val app get() = ApplicationProvider.getApplicationContext<Context>()
    @Before fun reset() { app.getSharedPreferences("hetu", 0).edit().clear().commit() }

    private fun connect(server: MockWebServer): ProxyDashboardRepository {
        server.start()
        app.getSharedPreferences("hetu", 0).edit()
            .putBoolean("proxyCustomApiEnabled", true)
            .putString("proxyCustomApiHost", "127.0.0.1")
            .putInt("proxyCustomApiPort", server.port).commit()
        return ProxyDashboardRepository(app)
    }

    private fun oldNode() = MockResponse().setBody("""{"proxies":{"SELECT":{"now":"old"}}}""")
    private fun sessions() = MockResponse().setBody("""{"connections":[
        {"id":"wechat","chains":["DIRECT"],"metadata":{"process":"com.tencent.mm"}},
        {"id":"other","chains":["old","OTHER"]},
        {"id":"unrelated-name","chains":["old","SELECT → OTHER"]},
        {"id":"unknown"},
        {"id":"nested","chains":["old","ASIA","SELECT"]},
        {"id":"affected","chains":["old","SELECT"]}
    ]}""")

    @Test fun switchingRetainsWechatDirectAndOtherGroups() = runBlocking {
        MockWebServer().use { server ->
            val repo = connect(server)
            server.enqueue(oldNode()); server.enqueue(sessions())
            repeat(3) { server.enqueue(MockResponse().setResponseCode(204)) }
            repo.select("SELECT", "new", disconnectPrevious = true)
            val requests = List(5) { server.takeRequest(2, TimeUnit.SECONDS)!! }
            assertEquals(listOf("GET", "GET", "PUT", "DELETE", "DELETE"), requests.map { it.method })
            assertEquals(listOf("/proxies", "/connections", "/proxies/SELECT", "/connections/nested", "/connections/affected"), requests.map { it.path })
            assertEquals("new", JSONObject(requests[2].body.readUtf8()).getString("name"))
            assertEquals(5, server.requestCount)
            assertEquals(2, app.getSharedPreferences("hetu", 0).getInt("proxyLastSelectionClosed", -1))
        }
    }

    @Test fun failedSwitchNeverDisconnectsAnySession() = runBlocking {
        MockWebServer().use { server ->
            val repo = connect(server)
            server.enqueue(oldNode()); server.enqueue(sessions())
            server.enqueue(MockResponse().setResponseCode(500).setBody("switch failed"))
            var failed = false
            try { repo.select("SELECT", "new", disconnectPrevious = true) }
            catch (_: Exception) { failed = true }
            assertTrue(failed)
            assertEquals(listOf("GET", "GET", "PUT"), List(3) { server.takeRequest(2, TimeUnit.SECONDS)!!.method })
            assertEquals(3, server.requestCount)
        }
    }

    @Test fun currentSelectionAndDisabledCleanupKeepConnections() = runBlocking {
        MockWebServer().use { server ->
            val repo = connect(server)
            server.enqueue(oldNode()); server.enqueue(MockResponse().setResponseCode(204))
            repo.select("SELECT", "old", disconnectPrevious = true)
            assertEquals(listOf("/proxies", "/proxies/SELECT"), List(2) { server.takeRequest(2, TimeUnit.SECONDS)!!.path })
            server.enqueue(MockResponse().setResponseCode(204))
            repo.select("SELECT", "new", disconnectPrevious = false)
            assertEquals("PUT", server.takeRequest(2, TimeUnit.SECONDS)!!.method)
            assertEquals(3, server.requestCount)
        }
    }

    @Test fun cleanupFailureDoesNotUndoSuccessfulSelection() = runBlocking {
        MockWebServer().use { server ->
            val repo = connect(server)
            server.enqueue(oldNode()); server.enqueue(sessions())
            server.enqueue(MockResponse().setResponseCode(204))
            server.enqueue(MockResponse().setResponseCode(500))
            server.enqueue(MockResponse().setResponseCode(204))
            repo.select("SELECT", "new", disconnectPrevious = true)
            val prefs = app.getSharedPreferences("hetu", 0)
            assertEquals(1, prefs.getInt("proxyLastSelectionCloseFailed", -1))
            assertEquals(1, prefs.getInt("proxyLastSelectionClosed", -1))
            assertEquals(5, server.requestCount)
        }
    }
}
