package io.github.xgl34222220.hetu

import android.content.ActivityNotFoundException
import android.app.Application
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
class DocumentPickerSafetyTest {
    @Test fun absentOemPickerReturnsVisibleError() {
        var message = ""
        assertFalse(launchDocumentPicker({ message = it }) { throw ActivityNotFoundException("missing DocumentsUI") })
        assertTrue(message.contains("文件选择器不可用"))
    }
    @Test fun disabledDocumentProviderDoesNotCrashClick() {
        var message = ""
        assertFalse(launchDocumentPicker({ message = it }) { throw SecurityException("provider disabled") })
        assertTrue(message.contains("拒绝"))
    }
    @Test fun successfulPickerStillLaunchesExactlyOnce() {
        var launched = 0
        assertTrue(launchDocumentPicker({ fail(it) }) { launched++ })
        assertEquals(1, launched)
    }
    @Test fun programmingErrorsAreNotSwallowed() {
        val failure = IllegalStateException("real bug")
        try { launchDocumentPicker({ fail(it) }) { throw failure }; fail("must propagate") }
        catch (actual: IllegalStateException) { assertSame(failure, actual) }
    }
}
