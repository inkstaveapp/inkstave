package app.inkstave.shared.ui

import androidx.compose.ui.input.key.Key
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.pressKey
import androidx.compose.ui.test.runComposeUiTest
import app.inkstave.shared.pedal.PedalAction
import app.inkstave.shared.pedal.PedalKeyMapping
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Real simulated-UI coverage for [PedalSettingsScreen] (`ROADMAP.md` M3) --
 * same honest status as [AppUiTest]: written against the actual composable
 * via `runComposeUiTest`, real clicks and real simulated key input, but
 * **not observed to pass in this session** for the same reason `AppUiTest`
 * isn't -- see `client/README.md`'s "Known rough edges" and
 * `ROADMAP.md`'s M1 entry for the `compose.uiTest` dependency-resolution
 * issue specific to this sandboxed environment. Confirmed to script
 * -compile correctly; run `./gradlew :shared:desktopTest` with working
 * network access to observe it actually pass.
 */
@OptIn(ExperimentalTestApi::class)
class PedalSettingsUiTest {
    @Test
    fun `remove button clears one binding without disturbing the others`() =
        runComposeUiTest {
            var current = PedalKeyMapping.DEFAULT
            setContent {
                PedalSettingsScreen(mapping = current, onMappingChange = { current = it }, onBack = {})
            }

            onNodeWithTag(TestTags.pedalBindingChip(PedalAction.NEXT_PAGE, Key.Spacebar)).assertExists()
            onNodeWithTag(TestTags.pedalBindingChipRemove(PedalAction.NEXT_PAGE, Key.Spacebar)).performClick()

            assertFalse(Key.Spacebar in current.keysFor(PedalAction.NEXT_PAGE), "removed key must no longer be bound")
            assertTrue(Key.DirectionRight in current.keysFor(PedalAction.NEXT_PAGE), "other NEXT_PAGE bindings must be untouched")
            assertEquals(PedalKeyMapping.DEFAULT.keysFor(PedalAction.PREVIOUS_PAGE), current.keysFor(PedalAction.PREVIOUS_PAGE))
        }

    @Test
    fun `reset to defaults restores DEFAULT after a customization`() =
        runComposeUiTest {
            var current = PedalKeyMapping.DEFAULT.withBinding(PedalAction.NEXT_PAGE, Key.F1)
            setContent {
                PedalSettingsScreen(mapping = current, onMappingChange = { current = it }, onBack = {})
            }

            onNodeWithTag(TestTags.PEDAL_SETTINGS_RESET).performClick()

            assertEquals(PedalKeyMapping.DEFAULT, current)
        }

    @Test
    fun `add binding then pressing a key binds it -- the actual 'I don't know my pedal's keys' fix`() =
        runComposeUiTest {
            var current = PedalKeyMapping.DEFAULT
            setContent {
                PedalSettingsScreen(mapping = current, onMappingChange = { current = it }, onBack = {})
            }

            onNodeWithTag(TestTags.pedalAddBindingButton(PedalAction.PREVIOUS_PAGE)).performClick()
            onNodeWithTag(TestTags.PEDAL_SETTINGS_CAPTURE_PROMPT).performKeyInput { pressKey(Key.F1) }

            assertEquals(PedalAction.PREVIOUS_PAGE, current.actionFor(Key.F1))
        }

    @Test
    fun `back invokes onBack`() =
        runComposeUiTest {
            var backCount = 0
            setContent {
                PedalSettingsScreen(mapping = PedalKeyMapping.DEFAULT, onMappingChange = {}, onBack = { backCount++ })
            }

            onNodeWithTag(TestTags.PEDAL_SETTINGS_BACK).performClick()

            assertEquals(1, backCount)
        }
}
