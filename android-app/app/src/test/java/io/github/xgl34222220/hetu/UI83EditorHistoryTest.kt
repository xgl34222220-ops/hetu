package io.github.xgl34222220.hetu

import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.TextFieldValue
import io.github.xgl34222220.hetu.tools.ToolsYamlEditorState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** The fallback editor must retain useful undo without keeping hundreds of full configurations. */
class UI83EditorHistoryTest {
    private val byteLimit = 8L * 1024L * 1024L
    private val twoMiChars = 2 * 1024 * 1024

    private fun replace(state: ToolsYamlEditorState, text: String) {
        state.onValueChange(TextFieldValue(text, TextRange(text.length)))
        assertBounded(state)
    }

    private fun assertBounded(state: ToolsYamlEditorState) {
        assertTrue("History estimate must be nonnegative", state.historyTextBytes >= 0L)
        assertTrue("Undo and redo must share the 8 MiB budget", state.historyTextBytes <= byteLimit)
    }

    @Test fun typingAndBackspacesKeepTheirExistingCoalescing() {
        val state = ToolsYamlEditorState()
        listOf("a", "ab", "abc", "ab", "a").forEach { replace(state, it) }
        state.undo()
        assertEquals("abc", state.text)
        state.undo()
        assertEquals("", state.text)
        assertFalse(state.canUndo)
        state.redo()
        assertEquals("abc", state.text)
        state.redo()
        assertEquals("a", state.text)
        assertFalse(state.canRedo)
        assertBounded(state)
    }

    @Test fun caretMovementSeparatesTypingUndoGroups() {
        val state = ToolsYamlEditorState()
        replace(state, "a")
        state.onValueChange(TextFieldValue("a", TextRange.Zero))
        replace(state, "ba")
        state.undo()
        assertEquals("a", state.text)
        assertEquals(TextRange.Zero, state.value.selection)
        state.undo()
        assertEquals("", state.text)
        assertBounded(state)
    }

    @Test fun accessoryUndoRestoresSelectionWithoutImeComposition() {
        val state = ToolsYamlEditorState("alpha")
        state.onValueChange(TextFieldValue("alpha", TextRange(1, 4), TextRange(1, 4)))
        state.insert("#")
        assertEquals("a# a", state.text)
        state.undo()
        assertEquals("alpha", state.text)
        assertEquals(TextRange(1, 4), state.value.selection)
        assertNull(state.value.composition)
        state.redo()
        assertEquals("a# a", state.text)
        assertBounded(state)
    }

    @Test fun resetReleasesBothHistoriesAndKeepsTheRequestedBuffer() {
        val state = ToolsYamlEditorState("first")
        replace(state, "second")
        replace(state, "third")
        state.undo()
        assertTrue(state.canUndo)
        assertTrue(state.canRedo)
        state.reset("loaded from disk")
        assertEquals("loaded from disk", state.text)
        assertEquals(0L, state.historyTextBytes)
        assertFalse(state.canUndo)
        assertFalse(state.canRedo)
        state.undo()
        state.redo()
        assertEquals("loaded from disk", state.text)
    }

    @Test fun smallEditsRetainExactlyTheLatestTwoHundredUndoSteps() {
        val state = ToolsYamlEditorState("000")
        for (i in 1..205) replace(state, i.toString().padStart(3, '0'))
        repeat(200) { state.undo(); assertBounded(state) }
        assertEquals("005", state.text)
        assertFalse(state.canUndo)
        state.undo()
        assertEquals("005", state.text)
        repeat(200) { state.redo(); assertBounded(state) }
        assertEquals("205", state.text)
        assertFalse(state.canRedo)
    }

    @Test fun byteBudgetEvictsOldestLargeStepsButKeepsImmediateUndoAndRedo() {
        val first = "a".repeat(twoMiChars)
        val second = "b".repeat(twoMiChars)
        val third = "c".repeat(twoMiChars)
        val fourth = "d".repeat(twoMiChars)
        val state = ToolsYamlEditorState(first)
        replace(state, second)
        replace(state, third)
        replace(state, fourth)
        assertEquals(byteLimit, state.historyTextBytes)
        state.undo()
        assertEquals(third, state.text)
        state.undo()
        assertEquals(second, state.text)
        assertFalse(state.canUndo)
        state.redo()
        assertEquals(third, state.text)
        state.redo()
        assertEquals(fourth, state.text)
        assertFalse(state.canRedo)
        assertBounded(state)
    }

    @Test fun currentBufferIsPreservedOutsideTheSnapshotBudget() {
        val first = "a".repeat(4 * 1024 * 1024)
        val second = "b".repeat(first.length)
        val state = ToolsYamlEditorState(first)
        replace(state, second)
        assertEquals(second, state.text)
        assertEquals(byteLimit, state.historyTextBytes)
        assertTrue(state.canUndo)
        state.undo()
        assertEquals(first, state.text)
        assertEquals(byteLimit, state.historyTextBytes)
        state.redo()
        assertEquals(second, state.text)
        assertEquals(byteLimit, state.historyTextBytes)
    }

    @Test fun undoTransfersAccountForLargerCurrentTextAndEvictOldestAcrossBothStacks() {
        val first = "a".repeat(1024 * 1024)
        val second = "b".repeat(first.length)
        val third = "c".repeat(twoMiChars)
        val fourth = "d".repeat(3 * 1024 * 1024)
        val state = ToolsYamlEditorState(first)
        replace(state, second)
        replace(state, third)
        replace(state, fourth)
        state.undo()
        assertEquals(third, state.text)
        assertBounded(state)
        state.undo()
        assertEquals(second, state.text)
        assertFalse(state.canUndo)
        assertBounded(state)
        state.redo()
        assertEquals(third, state.text)
        // The furthest redo target was oldest when its 6 MiB snapshot could no longer fit.
        assertFalse(state.canRedo)
        assertBounded(state)
    }

    @Test fun newEditReleasesObsoleteRedoBeforeItReservesUndoSpace() {
        val first = "a".repeat(twoMiChars)
        val second = "b".repeat(twoMiChars)
        val third = "c".repeat(twoMiChars)
        val branch = "d".repeat(twoMiChars)
        val state = ToolsYamlEditorState(first)
        replace(state, second)
        replace(state, third)
        state.undo()
        replace(state, branch)
        assertFalse(state.canRedo)
        state.undo()
        assertEquals(second, state.text)
        state.undo()
        assertEquals(first, state.text)
        assertBounded(state)
    }

    @Test fun accessoryEditAlsoReleasesRedoBeforeEvictingUsefulUndo() {
        val chars = twoMiChars - 2
        val first = "a".repeat(chars)
        val second = "b".repeat(chars)
        val third = "c".repeat(chars)
        val state = ToolsYamlEditorState(first)
        replace(state, second)
        replace(state, third)
        state.undo()
        state.insert(":")
        assertEquals(second + ": ", state.text)
        assertFalse(state.canRedo)
        state.undo()
        assertEquals(second, state.text)
        state.undo()
        assertEquals(first, state.text)
        assertBounded(state)
    }

    @Test fun oversizedCurrentCanBeUndoneWithoutRetainingAnOversizedRedoSnapshot() {
        val state = ToolsYamlEditorState("small")
        val oversized = "x".repeat(4 * 1024 * 1024 + 1)
        replace(state, oversized)
        assertEquals(oversized, state.text)
        state.undo()
        assertEquals("small", state.text)
        assertFalse(state.canRedo)
        assertEquals(0L, state.historyTextBytes)
    }

    @Test fun supplementaryCharactersUseTheirUtf16CharCountForTheBudget() {
        val initial = "\uD83D\uDE00".repeat(1024 * 1024)
        val state = ToolsYamlEditorState(initial)
        replace(state, "replacement")
        assertEquals(4L * 1024L * 1024L, state.historyTextBytes)
        state.undo()
        assertEquals(initial, state.text)
        state.redo()
        assertEquals("replacement", state.text)
        assertBounded(state)
    }
}
