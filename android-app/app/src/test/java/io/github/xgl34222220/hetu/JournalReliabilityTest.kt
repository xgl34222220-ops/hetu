package io.github.xgl34222220.hetu

import android.app.Application
import android.content.ClipboardManager
import android.content.Context
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
import java.util.UUID
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.Executors

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
class JournalReliabilityTest {
    @get:Rule val temporary = TemporaryFolder()
    private fun event(error: Throwable? = null) = ProxyNetworkJournal.capture(
        ProxyNetworkJournal.Stage.HEALTH_RESULT, 7, ProxyNetworkJournal.Outcome.DEGRADED, 0, error, null)
    private fun file(dir: File) = File(dir, "events-00000000000000000001.jsonl")
    private fun snapshot(dir: File) = dir.listFiles()!!.associate { it.name to it.readBytes().toList() }
    private fun fill(journal: ProxyNetworkJournal): Int {
        var accepted = 0
        repeat(3000) { try { journal.append(event()); accepted++ } catch (_: ProxyNetworkJournal.JournalFullException) {} }
        return accepted
    }
    private fun records(report: String) = report.lineSequence().filter { it.startsWith("{") }.map { JSONObject(it) }.toList()

    @Test fun continuousFaultsStopAtHardBudgetAndPreserveEveryRetainedByte() {
        val dir = temporary.newFolder(); val j = ProxyNetworkJournal(dir, 2048, 8192L)
        val accepted = fill(j); assertTrue(accepted in 1..2999)
        assertTrue(dir.listFiles()!!.sumOf { it.length() } <= 8192L)
        val before = snapshot(dir); repeat(100) { try { j.append(event()) } catch (_: ProxyNetworkJournal.JournalFullException) {} }
        assertEquals(before, snapshot(dir)); assertTrue(j.recent().contains("storagePaused=true"))
        assertTrue(j.recent().contains("历史未删除"))
    }
    @Test fun oversizedLegacyHistoryCannotGrowAndIsNeverDeleted() {
        val dir = temporary.newFolder(); val line = event().line + "\n"
        file(dir).writeText(line.repeat(100)); val before = snapshot(dir)
        val j = ProxyNetworkJournal(dir, 2048, 8192L)
        try { j.append(event()); fail("legacy over-quota history accepted append") } catch (_: ProxyNetworkJournal.JournalFullException) {}
        assertEquals(before, snapshot(dir)); assertTrue(j.recent().contains("storagePaused=true"))
    }
    @Test fun reconstructionKeepsPersistentPauseAndHistoryAtSameBudget() {
        val dir = temporary.newFolder(); fill(ProxyNetworkJournal(dir, 2048, 8192L)); val before = snapshot(dir)
        val rebuilt = ProxyNetworkJournal(dir, 2048, 8192L)
        try { rebuilt.append(event()); fail("reconstruction bypassed quota") } catch (_: ProxyNetworkJournal.JournalFullException) {}
        assertEquals(before, snapshot(dir)); assertTrue(rebuilt.recent().contains("storagePaused=true"))
    }
    @Test fun interleavedInstancesCannotAppendBackIntoCompletedOlderSegment() {
        val dir = temporary.newFolder(); val a = ProxyNetworkJournal(dir, 1024); val b = ProxyNetworkJournal(dir, 1024)
        a.append(event()); val first = file(dir)
        val large = event().let { ProxyNetworkJournal.Event(it.id, JSONObject(it.line).put("padding", "x".repeat(400)).toString()) }
        b.append(large); b.append(large); val completed = first.readBytes(); assertEquals(2, dir.listFiles()!!.size)
        a.append(event()); assertArrayEquals(completed, first.readBytes())
    }
    @Test fun copiedLegacyRecordsDropUnknownConfigurationAndCredentialFields() {
        val dir = temporary.newFolder(); val e = event()
        val record = JSONObject(e.line).put("configuration", "https://user:TOPSECRET@private.invalid/token")
            .put("authorization", "Bearer TOPSECRET").put("message", "/data/private/config.yaml")
        file(dir).writeText(record.toString() + "\n"); val before = snapshot(dir)
        val recent = ProxyNetworkJournal(dir, 4096).recent()
        assertTrue(recent.contains(e.id)); assertFalse(recent.contains("TOPSECRET")); assertFalse(recent.contains("private.invalid"))
        assertFalse(recent.contains("/data/private")); assertTrue(recent.contains("metadataRedacted")); assertEquals(before, snapshot(dir))
    }
    @Test fun syntheticStackSymbolsCannotSmuggleAddressesOrNewlinesIntoCapture() {
        val error = IOException("secret-message")
        error.stackTrace = arrayOf(StackTraceElement("https://TOPSECRET@private.invalid/path", "password=TOPSECRET\n", "ignored", 42))
        val e = event(error)
        assertFalse(e.line.contains("TOPSECRET")); assertFalse(e.line.contains("private.invalid")); assertFalse(e.line.contains("secret-message"))
        assertTrue(e.line.contains("redacted-frame")); assertTrue(e.line.contains("java.io.IOException"))
    }
    @Test fun actualCopyHelperPreservesTraceAndBoundaryStatusWithoutCopyingPoisonedMetadata() {
        val dir = temporary.newFolder(); val e = event()
        file(dir).writeText(JSONObject(e.line).put("configuration", "https://user:TOPSECRET@private.invalid/token").toString() + "\n")
        val app = ApplicationProvider.getApplicationContext<Application>()
        val report = ProxyNetworkJournal(dir, 4096).report(app.getSharedPreferences("clipboard-fixture", 0))
        hxCopy(app, "网络事件记录", report)
        val clip = (app.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager).primaryClip!!
        assertEquals("网络事件记录", clip.description.label.toString())
        val copied = clip.getItemAt(0).coerceToText(app).toString()
        assertEquals(report, copied); assertTrue(copied.contains(e.id)); assertTrue(copied.contains("storageLimitBytes=2097152"))
        assertTrue(copied.contains("journalLastWrittenId=")); assertFalse(copied.contains("TOPSECRET")); assertFalse(copied.contains("private.invalid"))
    }
    @Test fun configuredSecretIsRedactedFromFieldsAndCurrentTracePointers() {
        val dir = temporary.newFolder(); val secret = UUID.randomUUID().toString()
        val record = JSONObject(event().line).put("id", secret); file(dir).writeText(record.toString() + "\n")
        val app = ApplicationProvider.getApplicationContext<Application>(); val prefs = app.getSharedPreferences("copy-fixture", 0)
        prefs.edit().putString("proxyNetworkHealthTraceId", secret).commit()
        val j = ProxyNetworkJournal(dir, 4096, 8192L, secret); val report = j.report(prefs)
        assertFalse(report.contains(secret)); assertTrue(report.contains("[redacted]")); assertTrue(report.contains("[redacted-id]"))
    }
    @Test fun shortSecretExpansionStillProducesWholeRecordsWithinViewBudget() {
        val dir = temporary.newFolder(); val j = ProxyNetworkJournal(dir, 256 * 1024, 1024 * 1024L, "a")
        repeat(100) { j.append(event()) }; val report = j.recent()
        assertTrue(report.toByteArray().size <= 48 * 1024); assertEquals(64, records(report).size)
        assertTrue(report.contains("viewTruncated=true"))
    }
    @Test fun currentReportRejectsUntrustedStateStringsAndInvalidTraceIds() {
        val app = ApplicationProvider.getApplicationContext<Application>(); val prefs = app.getSharedPreferences("unsafe-status", 0)
        listOf("proxyPhysicalNetworkState", "proxyNetworkIntegrity", "proxyPolicyEgressState", "proxyNetworkJournalError", "proxyNetworkSessionId")
            .forEach { prefs.edit().putString(it, "https://TOPSECRET@private.invalid/config").commit() }
        prefs.edit().putString("proxyNetworkHealthTraceId", "------------------------------------").commit()
        val report = ProxyNetworkJournal(temporary.newFolder(), 4096).report(prefs)
        assertFalse(report.contains("TOPSECRET")); assertFalse(report.contains("private.invalid")); assertTrue(report.contains("healthTrace=unknown"))
    }
    @Test fun healthFaultSizeCannotBypassEventLimitOrDiscloseArbitraryChainNames() {
        val e = ProxyNetworkJournal.captureHealth(1, ProxyNetworkJournal.Outcome.DEGRADED, "4-filter-HETU_" + "A".repeat(200000), null)
        assertTrue(e.line.toByteArray().size <= 8192); assertTrue(e.line.contains("faultsTruncated"))
        assertEquals("unclassified-fault", JSONObject(e.line).getJSONArray("faults").getString(0))
        val known = ProxyNetworkJournal.captureHealth(1, ProxyNetworkJournal.Outcome.DEGRADED, "4-nat-HETU_DNSOUT,6-filter-HETU_V6OUT", null)
        assertTrue(known.line.contains("4-nat-HETU_DNSOUT")); assertTrue(known.line.contains("6-filter-HETU_V6OUT"))
    }
    @Test fun discardedCauseFrameAndFaultDetailsHaveExplicitTruncationFlags() {
        var error: Throwable = IOException("secret"); repeat(10) { error = IllegalStateException("secret", error) }
        error.stackTrace = Array(30) { StackTraceElement("Worker", "check", "ignored", it) }
        val e = ProxyNetworkJournal.captureHealth(1, ProxyNetworkJournal.Outcome.DEGRADED, List(20) { "watchdog-missing" }.joinToString(","), error)
        val r = JSONObject(e.line); assertTrue(r.getBoolean("causesTruncated")); assertTrue(r.getBoolean("faultsTruncated"))
        assertTrue(r.getJSONArray("causes").getJSONObject(0).getBoolean("framesTruncated"))
    }
    @Test fun invalidCanonicalUuidIsSkippedAndCannotAppearAsCorrelatedEvidence() {
        val dir = temporary.newFolder(); val r = JSONObject(event().line).put("id", "------------------------------------")
        file(dir).writeText(r.toString() + "\n"); val j = ProxyNetworkJournal(dir, 4096)
        assertTrue(j.recent().contains("unreadableLines=1")); assertTrue(records(j.recent()).isEmpty())
        val valid = event(); assertFalse(JSONObject(ProxyNetworkJournal.inSession(valid, "0-0-0-0-0").line).has("session"))
    }
    @Test fun oversizedRecentRowsAreOmittedWholeAndLatestCompleteTraceIsRetained() {
        val dir = temporary.newFolder(); val j = ProxyNetworkJournal(dir, 256 * 1024)
        var error: Throwable = IOException("secret"); repeat(3) { error = IllegalStateException("secret", error) }
        var c: Throwable? = error
        while (c != null) { c.stackTrace = Array(3) { StackTraceElement("W" + "a".repeat(159), "m".repeat(160), "ignored", it) }; c = c.cause }
        var last = event(); repeat(80) { last = event(error); j.append(last) }
        val view = j.recent(); assertTrue(view.toByteArray().size <= 48 * 1024); assertTrue(view.contains("viewTruncated=true"))
        assertTrue(view.contains(last.id)); assertTrue(records(view).size in 1..63); records(view).forEach { assertTrue(ProxyNetworkJournal.uuid(it.getString("id"))) }
    }
    @Test fun utf8TailWindowAndPoisonedPaddingCannotLeakOrSplitCopiedRecords() {
        val dir = temporary.newFolder(); var last = event()
        file(dir).bufferedWriter().use { out -> repeat(300) {
            last = event(); out.write(JSONObject(last.line).put("padding", "敏感配置".repeat(256)).toString()); out.newLine()
        } }
        val view = ProxyNetworkJournal(dir, 256 * 1024).recent()
        assertTrue(view.contains(last.id)); assertTrue(view.contains("viewTruncated=true")); assertFalse(view.contains("敏感配置"))
        assertFalse(view.contains('\uFFFD')); assertTrue(records(view).isNotEmpty())
    }
    @Test fun concurrentWriteAcknowledgementsFollowPhysicalRecordOrder() {
        val dir = temporary.newFolder(); val a = ProxyNetworkJournal(dir, 2048); val b = ProxyNetworkJournal(dir, 2048)
        val order = CopyOnWriteArrayList<String>(); val threads = Executors.newFixedThreadPool(8)
        try { (0..127).map { n -> threads.submit { val e = event(); (if (n % 2 == 0) a else b).append(e) { order += e.id } } }.forEach { it.get() } }
        finally { threads.shutdownNow() }
        val stored = dir.listFiles()!!.sortedBy { it.name }.flatMap { it.readLines() }.map { JSONObject(it).getString("id") }
        assertEquals(128, order.size); assertEquals(order.toList(), stored)
    }
    @Test fun oversizedDirectEventIsRejectedBeforeAnyHistoricalWrite() {
        val dir = temporary.newFolder(); val j = ProxyNetworkJournal(dir, 4096); j.append(event()); val before = snapshot(dir)
        val e = event(); val huge = ProxyNetworkJournal.Event(e.id, JSONObject(e.line).put("padding", "x".repeat(8192)).toString())
        try { j.append(huge); fail("oversized direct event accepted") } catch (_: IOException) {}
        assertEquals(before, snapshot(dir))
    }
    @Test fun kotlinMangledLocationsFromReportedCrashRemainTraceableThroughCaptureAndCopy() {
        val error = NoSuchMethodError("SYNTHETIC message is private")
        val input = arrayOf(StackTraceElement("com.materialkolor.ktx.DynamicSchemeKt", "toDynamicScheme-Iv8Zu3U", "private-file", 41),
            StackTraceElement("com.materialkolor.ktx.DynamicSchemeKt", "DynamicScheme-9lyMwEc", "private-file", 135),
            StackTraceElement("com.materialkolor.DynamicColorSchemeKt", "dynamicColorScheme-mm0v_cE", "private-file", 111))
        error.stackTrace = input; val e = event(error); val j = ProxyNetworkJournal(temporary.newFolder(), 4096); j.append(e)
        val frames = records(j.recent()).single().getJSONArray("causes").getJSONObject(0).getJSONArray("frames")
        input.forEachIndexed { i, f -> assertEquals("${f.className}.${f.methodName}:${f.lineNumber}", frames.getString(i)) }
        assertTrue(j.recent().contains(e.id)); assertFalse(j.recent().contains("private-file")); assertFalse(j.recent().contains("SYNTHETIC"))
    }
    @Test fun constructorAndClassInitializerLocationsSurviveCaptureAndLegacyRead() {
        val error = IllegalStateException("ignored"); error.stackTrace = arrayOf(
            StackTraceElement("example.Worker", "<init>", "ignored", 12), StackTraceElement("example.Worker", "<clinit>", "ignored", -2))
        val dir = temporary.newFolder(); val e = event(error); file(dir).writeText(e.line + "\n")
        val frames = records(ProxyNetworkJournal(dir, 4096).recent()).single().getJSONArray("causes").getJSONObject(0).getJSONArray("frames")
        assertEquals("example.Worker.<init>:12", frames.getString(0)); assertEquals("example.Worker.<clinit>:-2", frames.getString(1))
    }
    @Test fun expandingLegalMethodFormsStillRejectsPoisonedOrOverlongSymbols() {
        val methods = listOf("<init>https://TOPSECRET@private.invalid", "invoke-token=TOPSECRET", "invoke-TOPSECRET@private.invalid", "get\nTOPSECRET", "m".repeat(161))
        methods.forEach { method ->
            val error = IOException("private"); error.stackTrace = arrayOf(StackTraceElement("example.Worker", method, "ignored", 42))
            val e = event(error); val dir = temporary.newFolder(); val poisoned = JSONObject(e.line)
            poisoned.getJSONArray("causes").getJSONObject(0).getJSONArray("frames").put(0, "example.Worker.$method:42")
            file(dir).writeText(poisoned.toString() + "\n"); val j = ProxyNetworkJournal(dir, 4096)
            assertTrue(e.line.contains("redacted-frame")); assertTrue(j.recent().contains("redacted-frame"))
            assertFalse(j.recent().contains("TOPSECRET")); assertFalse(j.recent().contains("private.invalid"))
        }
    }
    @Test fun mangledMethodSuffixMatchingConfiguredSecretIsStillRedacted() {
        val error = IOException("ignored"); error.stackTrace = arrayOf(StackTraceElement("example.Worker", "measure-Iv8Zu3U", "ignored", 42))
        val e = event(error); val j = ProxyNetworkJournal(temporary.newFolder(), 4096, 8192L, "Iv8Zu3U"); j.append(e)
        val recent = j.recent(); assertTrue(recent.contains("example.Worker.measure-[redacted]:42")); assertFalse(recent.contains("Iv8Zu3U"))
        assertTrue(recent.contains(e.id))
    }
}
