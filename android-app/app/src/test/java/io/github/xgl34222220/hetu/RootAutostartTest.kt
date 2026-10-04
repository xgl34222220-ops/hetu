package io.github.xgl34222220.hetu

import android.app.Application
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.Implements
import org.robolectric.annotation.Implementation
import java.io.File
import java.util.concurrent.Executors

@Implements(value = RootBridge::class, isInAndroidSdk = false)
class AutostartBridgeShadow {
    companion object {
        var deny = false
        @JvmStatic @Implementation fun rootShell(context: Context, command: String, timeout: Long): RootBridge.Result {
            require(command.contains("rm -f") && command.contains(RootAutostart.ENTRY)) { "Unexpected mutation: $command" }
            return if (deny) RootBridge.Result(126, "Root denied") else RootBridge.Result(0, "")
        }
    }
}

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class, shadows = [AutostartBridgeShadow::class])
class RootAutostartTest {
    private fun args() = Array(30) { "arg$it" }.also { it[19] = "1"; it[20] = "1" }
    @Test fun planPreservesThirtyArgumentsAndNeverExecutesTheirText() {
        val folder = java.nio.file.Files.createTempDirectory("hetu-boot-plan-").toFile()
        try {
            val sink = File(folder, "args.bin")
            val control = File(folder, "control.sh").apply { writeText("printf '%s\\000' \"\$@\" > \"\$HETU_TEST_ARGS\"\n") }
            val marker = File(folder, "must-not-exist")
            val values = args().also { it[16] = "a b;'\n\$(touch ${marker.absolutePath})" }
            val plan = RootAutostart.plan(values).replace("/system/bin/sh", "/bin/sh").replace("/data/adb/hetu/hetu-root.sh", control.absolutePath)
            val process = ProcessBuilder("sh", "-c", plan).apply { environment()["HETU_TEST_ARGS"] = sink.absolutePath }.start()
            assertEquals(0, process.waitFor())
            val actual = sink.readBytes().toString(Charsets.UTF_8).split('\u0000').dropLast(1)
            assertEquals(31, actual.size)
            assertEquals("start", actual[0])
            assertEquals(values[16], actual[17])
            assertEquals(RootAutostart.BASE + "/config", actual[2])
            assertEquals("0", actual[20]); assertEquals("0", actual[21])
            assertFalse(marker.exists())
        } finally { folder.deleteRecursively() }
    }
    @Test fun cacheResetDoesNotMutateNormalStartArguments() {
        val values = args(); RootAutostart.plan(values)
        assertEquals("1", values[19]); assertEquals("1", values[20])
    }
    @Test(expected = IllegalArgumentException::class) fun partialPlanIsRejected() { RootAutostart.plan(Array(29) { "" }) }
    private fun change(enabled: Boolean) {
        val app = ApplicationProvider.getApplicationContext<Application>()
        val worker = Executors.newSingleThreadExecutor()
        try { worker.submit<String> { RootProxyManager(app).setAutoStart(enabled) }.get() }
        finally { worker.shutdownNow() }
    }
    private fun seed() = ApplicationProvider.getApplicationContext<Application>().getSharedPreferences("hetu", 0).also {
        it.edit().clear().putBoolean("proxyRootAutoStart", true).putBoolean("proxyRootAutoStartInstalled", true).commit()
    }
    @Test fun failedRootRemovalNeverClaimsTheSettingIsOff() {
        val prefs = seed(); AutostartBridgeShadow.deny = true
        try { change(false); fail("Root denied") } catch (error: java.util.concurrent.ExecutionException) { assertTrue(error.cause is java.io.IOException) }
        assertTrue(prefs.getBoolean("proxyRootAutoStart", false))
        assertTrue(prefs.getBoolean("proxyRootAutoStartInstalled", false))
        assertTrue(prefs.getString("proxyRootAutoStartError", "").orEmpty().contains("Root denied"))
    }
    @Test fun confirmedRemovalTurnsOffBothAutostartAndInstalledState() {
        val prefs = seed(); AutostartBridgeShadow.deny = false; change(false)
        assertFalse(prefs.getBoolean("proxyRootAutoStart", true))
        assertFalse(prefs.getBoolean("proxyRootAutoStartInstalled", true))
        assertFalse(prefs.contains("proxyRootAutoStartError"))
    }
}
