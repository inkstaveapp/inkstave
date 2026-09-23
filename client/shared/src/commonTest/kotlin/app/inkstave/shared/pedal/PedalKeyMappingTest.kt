package app.inkstave.shared.pedal

import androidx.compose.ui.input.key.Key
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class PedalKeyMappingTest {
    @Test
    fun `DEFAULT has no key bound to more than one action`() {
        val default = PedalKeyMapping.DEFAULT
        val allKeys = default.bindings.values.flatten()
        assertEquals(allKeys.size, allKeys.toSet().size, "a key appears under more than one action in DEFAULT: $allKeys")
    }

    @Test
    fun `DEFAULT covers every documented factory pedal mode -- arrows in both axes, page up-down, and space`() {
        val default = PedalKeyMapping.DEFAULT
        assertEquals(PedalAction.PREVIOUS_PAGE, default.actionFor(Key.DirectionLeft))
        assertEquals(PedalAction.PREVIOUS_PAGE, default.actionFor(Key.DirectionUp))
        assertEquals(PedalAction.PREVIOUS_PAGE, default.actionFor(Key.PageUp))
        assertEquals(PedalAction.NEXT_PAGE, default.actionFor(Key.DirectionRight))
        assertEquals(PedalAction.NEXT_PAGE, default.actionFor(Key.DirectionDown))
        assertEquals(PedalAction.NEXT_PAGE, default.actionFor(Key.PageDown))
        assertEquals(PedalAction.NEXT_PAGE, default.actionFor(Key.Spacebar))
    }

    @Test
    fun `an unbound key has no action`() {
        assertNull(PedalKeyMapping.DEFAULT.actionFor(Key.A))
    }

    @Test
    fun `withBinding adds a new key without disturbing other bindings for the same action`() {
        val updated = PedalKeyMapping.DEFAULT.withBinding(PedalAction.NEXT_PAGE, Key.Enter)
        assertEquals(PedalAction.NEXT_PAGE, updated.actionFor(Key.Enter))
        assertEquals(PedalAction.NEXT_PAGE, updated.actionFor(Key.DirectionRight), "existing bindings for the action must survive")
    }

    @Test
    fun `withBinding moves a key from its previous action, never leaving it bound to two`() {
        // Key.PageUp starts on PREVIOUS_PAGE in DEFAULT; rebind it to NEXT_PAGE.
        val updated = PedalKeyMapping.DEFAULT.withBinding(PedalAction.NEXT_PAGE, Key.PageUp)
        assertEquals(PedalAction.NEXT_PAGE, updated.actionFor(Key.PageUp))
        assertEquals(
            PedalKeyMapping.DEFAULT.keysFor(PedalAction.PREVIOUS_PAGE) - Key.PageUp,
            updated.keysFor(PedalAction.PREVIOUS_PAGE),
        )
    }

    @Test
    fun `withoutBinding removes a key, leaving other actions and other keys of the same action untouched`() {
        val updated = PedalKeyMapping.DEFAULT.withoutBinding(PedalAction.NEXT_PAGE, Key.Spacebar)
        assertNull(updated.actionFor(Key.Spacebar))
        assertEquals(PedalAction.NEXT_PAGE, updated.actionFor(Key.DirectionRight))
        assertEquals(PedalKeyMapping.DEFAULT.keysFor(PedalAction.PREVIOUS_PAGE), updated.keysFor(PedalAction.PREVIOUS_PAGE))
    }

    @Test
    fun `keysFor an action with no bindings returns an empty set, not an error`() {
        val empty = PedalKeyMapping(emptyMap())
        assertEquals(emptySet(), empty.keysFor(PedalAction.NEXT_PAGE))
        assertNull(empty.actionFor(Key.DirectionRight))
    }
}
