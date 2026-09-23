package app.inkstave.shared.annotation

import app.inkstave.shared.format.AnnotationLayer
import app.inkstave.shared.format.TextNote
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class AnnotationHistoryTest {
    private fun layerWithNote(text: String) =
        AnnotationLayer(
            pageId = "page-1",
            layerVersion = 1,
            textNotes = listOf(TextNote(id = "n1", x = 0.0, y = 0.0, text = text, fontSizePt = 10.0)),
        )

    @Test
    fun `undo reverts exactly the most recent push, redo restores it`() {
        val history = AnnotationHistory(initial = layerWithNote("v1"))

        history.push(layerWithNote("v2"))
        history.push(layerWithNote("v3"))
        assertEquals(
            "v3",
            history.current.textNotes
                .single()
                .text,
        )

        assertTrue(history.undo())
        assertEquals(
            "v2",
            history.current.textNotes
                .single()
                .text,
        )

        assertTrue(history.undo())
        assertEquals(
            "v1",
            history.current.textNotes
                .single()
                .text,
        )

        assertFalse(history.undo(), "undo past the initial layer must be a no-op, not an error")
        assertEquals(
            "v1",
            history.current.textNotes
                .single()
                .text,
        )

        assertTrue(history.redo())
        assertEquals(
            "v2",
            history.current.textNotes
                .single()
                .text,
        )
        assertTrue(history.redo())
        assertEquals(
            "v3",
            history.current.textNotes
                .single()
                .text,
        )
        assertFalse(history.redo(), "redo past the most recent push must be a no-op, not an error")
    }

    @Test
    fun `a new push after an undo clears the redo stack`() {
        val history = AnnotationHistory(initial = layerWithNote("v1"))
        history.push(layerWithNote("v2"))
        history.undo()
        assertTrue(history.canRedo())

        history.push(layerWithNote("v2-alt"))

        assertFalse(history.canRedo(), "a fresh edit must discard the now-stale redo branch")
        assertEquals(
            "v2-alt",
            history.current.textNotes
                .single()
                .text,
        )
    }

    @Test
    fun `canUndo and canRedo reflect the current stack state`() {
        val history = AnnotationHistory(initial = layerWithNote("v1"))
        assertFalse(history.canUndo())
        assertFalse(history.canRedo())

        history.push(layerWithNote("v2"))
        assertTrue(history.canUndo())
        assertFalse(history.canRedo())

        history.undo()
        assertFalse(history.canUndo())
        assertTrue(history.canRedo())
    }

    @Test
    fun `history depth is capped -- undoing past maxDepth stops rather than growing unbounded`() {
        val history = AnnotationHistory(initial = layerWithNote("v0"), maxDepth = 3)
        repeat(10) { i -> history.push(layerWithNote("v${i + 1}")) }

        var undoCount = 0
        while (history.undo()) undoCount++

        assertEquals(3, undoCount, "only maxDepth undos should be possible once the cap has been exceeded")
    }
}
