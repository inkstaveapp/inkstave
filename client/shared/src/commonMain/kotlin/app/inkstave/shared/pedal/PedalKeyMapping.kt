package app.inkstave.shared.pedal

import androidx.compose.ui.input.key.Key

/**
 * Which [Key]s trigger which [PedalAction], and the reverse lookup key
 * dispatch actually needs (`ViewerScreen`, both platforms -- see
 * `docs/architecture.md`'s note on this file and `PedalSettingsStore` for
 * persistence).
 *
 * One action can be bound to multiple keys at once ([bindings]' value is a
 * `Set`, not a single [Key]) -- this is not a nicety, it's required for
 * [DEFAULT] to exist at all: real page-turner pedals ship in several
 * factory "modes" that each send a different key for the same physical
 * button (see [DEFAULT]'s own doc), and a mapping that could only hold one
 * key per action could cover at most one of those modes by default.
 */
data class PedalKeyMapping(
    val bindings: Map<PedalAction, Set<Key>>,
) {
    // Built once at construction, not recomputed per lookup -- actionFor is called on
    // every key event while the viewer is open, so this needs to be O(1), not an
    // O(actions * keys-per-action) scan every keystroke.
    //
    // If the same key appears under more than one action (only possible via a
    // hand-edited or otherwise corrupted persisted settings file -- withBinding, the
    // only mutation path this class itself exposes, never produces it), later entries
    // in [bindings]' iteration order win. That's a deliberate, harmless choice: better
    // to deterministically pick *an* answer for a key press than to throw and make the
    // whole mapping (and therefore every other, valid binding in it) unusable over one
    // bad entry.
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

    /**
     * [key] bound to [action], as the *only* action it's bound to -- if [key] was
     * previously bound to a different action, that binding is removed first, so the
     * result never has one key mapped to two actions (the invariant [reverse] relies
     * on being harmless-by-construction for every mapping this method produces).
     * This is what the remap UI's "press the key you want to use" flow calls.
     */
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
         * The out-of-the-box mapping, chosen to work with as many real pedals'
         * *factory default* mode as possible without any remapping -- not a guess.
         * Cross-referenced against vendor documentation for AirTurn (PEDpro/PED500),
         * PageFlip (Butterfly/Firefly/Dragonfly), iRig BlueTurn, and Donner/Moukey,
         * plus forScore's own documented defaults (the dominant sheet-music app,
         * whose default key set is itself the de facto standard pedals are built
         * against): every one of those vendors' pedals is a Bluetooth-HID keyboard
         * that ships in one of a small number of factory-selectable modes --
         * Up/Down arrow, Left/Right arrow, and Page Up/Page Down are the three modes
         * every single one of them offers (exact naming varies: AirTurn calls them
         * "Mode 2"/"Mode 3", PageFlip "Mode 2"/"Mode 3"/"Mode 1", iRig just lists the
         * three directly) -- so binding all three covers every vendor's out-of-the-box
         * behavior at once, regardless of which mode the user's specific unit happens
         * to be set to.
         *
         * [Key.Spacebar] is also bound to [PedalAction.NEXT_PAGE]: "space advances"
         * is a near-universal convention across presentation software and PDF
         * viewers generally, independent of pedal-specific documentation, and low
         * -risk to bind (nothing else in the viewer currently uses a bare Spacebar
         * press). Deliberately *not* bound: Enter (some pedals' less-common
         * "Space/Enter" mode use it for one direction, but which direction isn't
         * consistently documented across vendors, and Enter already means something
         * -- "confirm" -- in this app's own text-annotation dialog; guessing wrong
         * here would be worse than leaving it unbound, and the remap flow covers
         * this mode's users in five seconds regardless).
         *
         * Any pedal that doesn't match this -- including the repo owner's own,
         * whose exact keys aren't known yet (see `ROADMAP.md` M3) -- is exactly
         * what the remap flow (`PedalSettingsScreen`) exists for.
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
