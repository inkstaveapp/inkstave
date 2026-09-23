package app.inkstave.shared.annotation

import app.inkstave.shared.format.AnnotationLayer

/**
 * Undo/redo for one page's [AnnotationLayer], as a capped history of full
 * -layer snapshots. Chosen over fine-grained command objects (the more
 * "proper" long-term design) because a page's annotation layer is bounded
 * in size (hundreds of objects, per `docs/performance.md`'s own target --
 * not unbounded), so a whole-layer snapshot per edit is cheap: Kotlin's
 * immutable `List`s mean an edit that only changes one stroke still shares
 * every *other* list element by reference with the previous snapshot, not
 * a deep copy. Simple, obviously correct (undo restores exactly the prior
 * snapshot, nothing to reconstruct), and easy to test. Revisit only if a
 * future pass finds snapshot memory use is an actual measured problem.
 *
 * Not thread-safe -- used from a single Compose UI's state, same as every
 * other piece of mutable UI state in this codebase.
 */
class AnnotationHistory(
    initial: AnnotationLayer,
    private val maxDepth: Int = 50,
) {
    private val undoStack = ArrayDeque<AnnotationLayer>()
    private val redoStack = ArrayDeque<AnnotationLayer>()

    /** The current layer -- what should actually be rendered/saved right now. */
    var current: AnnotationLayer = initial
        private set

    /** Records [next] as the new current layer, with [current] becoming the one step [undo] would return to. Clears the redo stack, per standard undo/redo semantics: a new edit invalidates any previously-undone future. */
    fun push(next: AnnotationLayer) {
        undoStack.addLast(current)
        if (undoStack.size > maxDepth) undoStack.removeFirst()
        redoStack.clear()
        current = next
    }

    /** Reverts to the layer before the most recent [push], if any. Returns whether it actually did anything. */
    fun undo(): Boolean {
        val previous = undoStack.removeLastOrNull() ?: return false
        redoStack.addLast(current)
        current = previous
        return true
    }

    /** Re-applies the most recently undone [push], if any. Returns whether it actually did anything. */
    fun redo(): Boolean {
        val next = redoStack.removeLastOrNull() ?: return false
        undoStack.addLast(current)
        current = next
        return true
    }

    fun canUndo(): Boolean = undoStack.isNotEmpty()

    fun canRedo(): Boolean = redoStack.isNotEmpty()
}
