package app.inkstave.shared.annotation

import app.inkstave.shared.format.AnnotationLayer

/**
 * Undo/redo for one page's [AnnotationLayer], as a capped stack of whole-layer snapshots. Snapshots
 * are cheap because layers are small and immutable lists share unchanged elements.
 *
 * Not thread-safe; used from UI state only.
 */
class AnnotationHistory(
    initial: AnnotationLayer,
    private val maxDepth: Int = 50,
) {
    private val undoStack = ArrayDeque<AnnotationLayer>()
    private val redoStack = ArrayDeque<AnnotationLayer>()

    /** The layer to render and save. */
    var current: AnnotationLayer = initial
        private set

    /** Makes [next] current, pushing the old current onto the undo stack. Clears the redo stack. */
    fun push(next: AnnotationLayer) {
        undoStack.addLast(current)
        if (undoStack.size > maxDepth) undoStack.removeFirst()
        redoStack.clear()
        current = next
    }

    /** Reverts the most recent [push], if any; returns whether it did. */
    fun undo(): Boolean {
        val previous = undoStack.removeLastOrNull() ?: return false
        redoStack.addLast(current)
        current = previous
        return true
    }

    /** Re-applies the most recently undone [push], if any; returns whether it did. */
    fun redo(): Boolean {
        val next = redoStack.removeLastOrNull() ?: return false
        undoStack.addLast(current)
        current = next
        return true
    }

    fun canUndo(): Boolean = undoStack.isNotEmpty()

    fun canRedo(): Boolean = redoStack.isNotEmpty()
}
