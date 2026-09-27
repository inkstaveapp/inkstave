package app.inkstave.shared.pedal

import androidx.compose.ui.input.key.Key

/**
 * Which [Key]s trigger which [PedalAction], plus the reverse lookup key dispatch needs. An action
 * can have several keys because pedals ship in different factory modes that send different keys
 * for the same button, and [DEFAULT] covers them all.
 */
data class PedalKeyMapping(
    val bindings: Map<PedalAction, Set<Key>>,
) {
    // Built once, since actionFor runs on every key event. A key under two actions (only from a
    // hand-edited settings file) resolves to the later one rather than failing the whole mapping.
    private val reverse: Map<Key, PedalAction> =
        buildMap {
            for ((action, keys) in bindings) {
                for (key in keys) put(key, action)
            }
        }

    /** The action [key] should trigger, or `null` if it isn't bound to anything. */
    fun actionFor(key: Key): PedalAction? = reverse[key]

    /** Every key currently bound to [action], possibly empty. */
    fun keysFor(action: PedalAction): Set<Key> = bindings[action].orEmpty()

    /** [key] bound to [action] and removed from any other action, so a key never maps to two actions. */
    fun withBinding(
        action: PedalAction,
        key: Key,
    ): PedalKeyMapping {
        val cleared = bindings.mapValues { (_, keys) -> keys - key }
        val updated = cleared + (action to (cleared[action].orEmpty() + key))
        return PedalKeyMapping(updated)
    }

    /** [key] removed from [action]'s bindings, if it was there. Leaves other actions' bindings untouched. */
    fun withoutBinding(
        action: PedalAction,
        key: Key,
    ): PedalKeyMapping = PedalKeyMapping(bindings.mapValues { (a, keys) -> if (a == action) keys - key else keys })

    companion object {
        /**
         * Out-of-the-box mapping covering the factory modes of common Bluetooth pedals (AirTurn,
         * PageFlip, iRig BlueTurn, Donner/Moukey): Left/Right arrows, Up/Down arrows, and Page
         * Up/Down. Space also advances, as in most viewers. Enter is left unbound: pedals disagree
         * on its direction, and it confirms dialogs here. Other pedals use the remap screen.
         */
        val DEFAULT: PedalKeyMapping =
            PedalKeyMapping(
                mapOf(
                    PedalAction.PREVIOUS_PAGE to setOf(Key.DirectionLeft, Key.DirectionUp, Key.PageUp),
                    PedalAction.NEXT_PAGE to setOf(Key.DirectionRight, Key.DirectionDown, Key.PageDown, Key.Spacebar),
                ),
            )
    }
}
