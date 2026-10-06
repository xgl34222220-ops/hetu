package io.github.xgl34222220.hetu

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File
import java.io.IOException
import java.util.concurrent.Executors

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
class ProxyNetworkJournalTest {
    @get:Rule val temporary = TemporaryFolder()
    private fun event(error: Throwable? = null, parent: String? = null) = ProxyNetworkJournal.capture(
        ProxyNetworkJournal.Stage.EGRESS_RESULT, 7, ProxyNetworkJournal.Outcome.UNVERIFIED, 503, error, parent)
    private fun records(directory: File) = directory.listFiles()!!.sortedBy { it.name }
        .flatMap { it.readLines().filter(String::isNotEmpty) }.map { JSONObject(it) }

    @Test fun exceptionMessagesAddressesAndCredentialsNeverEnterRecords() {
        val hidden = "https://example.invalid/token?secret=TOPSECRET SSID=PRIVATE user@private.invalid /data/private"
        val cause = IOException(hidden)
        cause.stackTrace = arrayOf(StackTraceElement("CoreWorker", "check", "worker.java", 42))
        val captured = event(IllegalStateException(hidden, cause), "not-a-generated-id")
        assertFalse(captured.line.contains(hidden)); assertFalse(captured.line.contains("TOPSECRET"))
        assertFalse(captured.line.contains("SSID")); assertFalse(captured.line.contains("private.invalid"))
        val record = JSONObject(captured.line)
        assertFalse(record.has("parent"))
        assertEquals("java.io.IOException", record.getJSONArray("causes").getJSONObject(1).getString("type"))
        assertTrue(captured.line.contains("CoreWorker.check:42"))
    }
    @Test fun onlyKnownStructuralFaultCodesAreRecorded() {
        val captured = ProxyNetworkJournal.captureHealth(3, ProxyNetworkJournal.Outcome.DEGRADED,
            "session-manifest-missing,listener-11053-udp,watchdog-missing,https://private.invalid/token", null)
        val faults = JSONObject(captured.line).getJSONArray("faults")
        assertEquals("session-manifest-missing", faults.getString(0))
        assertEquals("listener-11053-udp", faults.getString(1))
        assertEquals("watchdog-missing", faults.getString(2))
        assertEquals("unclassified-fault", faults.getString(3))
        assertFalse(captured.line.contains("private.invalid"))
    }
    @Test fun laterHealthyObservationPreservesFailureAndBothTraceIds() {
        val dir = temporary.newFolder(); val journal = ProxyNetworkJournal(dir, 4096)
        val failed = ProxyNetworkJournal.captureHealth(1, ProxyNetworkJournal.Outcome.UPGRADE_REQUIRED, "session-manifest-missing", null)
        val healthy = ProxyNetworkJournal.captureHealth(1, ProxyNetworkJournal.Outcome.HEALTHY, "", null)
        journal.append(failed); journal.append(healthy)
        val recent = journal.recent()
        assertTrue(recent.contains(failed.id)); assertTrue(recent.contains(healthy.id))
        assertTrue(recent.contains("session-manifest-missing")); assertTrue(recent.contains("HEALTHY"))
    }
    @Test fun rotationRetainsOldSegmentsByteForByte() {
        val dir = temporary.newFolder(); val journal = ProxyNetworkJournal(dir, 1024)
        journal.append(event()); val first = dir.listFiles()!!.single(); val bytes = first.readBytes()
        // Fill the first chunk, then compare after a subsequent rollover.
        while (dir.listFiles()!!.size == 1) journal.append(event())
        val completed = first.readBytes(); assertTrue(completed.size >= bytes.size)
        repeat(20) { journal.append(event()) }
        assertArrayEquals(completed, first.readBytes()); assertTrue(dir.listFiles()!!.size > 2)
    }
    @Test fun partialWriteIsRetainedAndNextCompleteRecordRemainsReadable() {
        val dir = temporary.newFolder(); val journal = ProxyNetworkJournal(dir, 4096)
        journal.append(event()); val file = dir.listFiles()!!.single()
        file.appendText("{incomplete-record")
        val next = event(); journal.append(next)
        assertTrue(file.readText().contains("{incomplete-record\n"))
        assertTrue(journal.recent().contains(next.id)); assertTrue(journal.recent().contains("unreadableLines=1"))
    }
    @Test fun restartFindsRetainedSegmentsAndAppendsInsteadOfReplacing() {
        val dir = temporary.newFolder(); val first = event()
        ProxyNetworkJournal(dir, 1024).append(first)
        val restarted = ProxyNetworkJournal(dir, 1024); val second = event(); restarted.append(second)
        assertTrue(restarted.recent().contains(first.id)); assertTrue(restarted.recent().contains(second.id))
        assertEquals(2, records(dir).size)
    }
    @Test fun concurrentAppendsProduceWholeUniqueRecords() {
        val dir = temporary.newFolder(); val journal = ProxyNetworkJournal(dir, 4096)
        val workers = Executors.newFixedThreadPool(8)
        try { (1..128).map { workers.submit { journal.append(event()) } }.forEach { it.get() } }
        finally { workers.shutdownNow() }
        val records = records(dir)
        assertEquals(128, records.size); assertEquals(128, records.map { it.getString("id") }.toSet().size)
    }
    @Test fun recentViewIsBoundedWithoutDeletingOlderRecords() {
        val dir = temporary.newFolder(); val journal = ProxyNetworkJournal(dir, 256 * 1024)
        val first = event(); journal.append(first); repeat(1000) { journal.append(event()) }
        val recent = journal.recent()
        assertEquals(64, recent.lineSequence().count { it.startsWith("{") })
        assertFalse(recent.contains(first.id)); assertEquals(first.id, records(dir).first().getString("id"))
        assertEquals(1001, records(dir).size)
    }
    @Test fun causeDepthAndFramesHaveFiniteBounds() {
        var error: Throwable = IOException("secret")
        repeat(20) { error = IllegalStateException("secret", error) }
        error.stackTrace = Array(30) { StackTraceElement("Worker", "check", "file.java", it) }
        val record = JSONObject(event(error).line)
        assertEquals(4, record.getJSONArray("causes").length())
        assertEquals(3, record.getJSONArray("causes").getJSONObject(0).getJSONArray("frames").length())
        assertTrue(record.has("elapsedMs")); assertTrue(record.has("timeMs"))
    }
    @Test fun applicationJournalUsesStablePrivateDirectoryAndNeedsNoRoot() {
        val app = ApplicationProvider.getApplicationContext<Application>(); val first = event()
        ProxyNetworkJournal(app).append(first)
        assertTrue(File(app.noBackupFilesDir, "hetu-network-events").isDirectory)
        assertFalse(File(app.cacheDir, "hetu-network-events").exists())
        assertTrue(ProxyNetworkJournal(app).recent().contains(first.id))
    }
    @Test fun separateTargetsKeepFailedAndSuccessfulResultsLinkedToOneRequest() {
        val request = event()
        val first = ProxyNetworkJournal.captureEgress(7, ProxyNetworkJournal.Target.GOOGLE_204, 503, null, request.id)
        val second = ProxyNetworkJournal.captureEgress(7, ProxyNetworkJournal.Target.CLOUDFLARE_204, 204, null, request.id)
        assertEquals("GOOGLE_204", JSONObject(first.line).getString("target"))
        assertEquals("UNVERIFIED", JSONObject(first.line).getString("outcome"))
        assertEquals("REACHABLE", JSONObject(second.line).getString("outcome"))
        assertEquals(request.id, JSONObject(first.line).getString("parent"))
        assertEquals(request.id, JSONObject(second.line).getString("parent"))
    }
}
