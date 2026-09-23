package app.inkstave.shared.pedal

import androidx.compose.ui.input.key.Key
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals

class PedalSettingsStoreTest {
    private fun tempSettingsFile(): File {
        val dir =
            kotlin.io.path
                .createTempDirectory("inkstave-pedal-settings-")
                .toFile()
        return File(dir, "pedal-settings.json")
    }

    @Test
    fun `load with no file yet returns DEFAULT`() {
        val store = PedalSettingsStore(tempSettingsFile())
        assertEquals(PedalKeyMapping.DEFAULT, store.load())
    }

    @Test
    fun `save then load round-trips a customized mapping exactly`() {
        val store = PedalSettingsStore(tempSettingsFile())
        val customized =
            PedalKeyMapping.DEFAULT
                .withBinding(
                    PedalAction.NEXT_PAGE,
                    Key.F1,
                ).withoutBinding(PedalAction.NEXT_PAGE, Key.Spacebar)

        store.save(customized)
        val loaded = store.load()

        assertEquals(customized, loaded)
        assertEquals(PedalAction.NEXT_PAGE, loaded.actionFor(Key.F1))
    }

    @Test
    fun `load on a corrupted settings file falls back to DEFAULT rather than throwing`() {
        val file = tempSettingsFile()
        file.parentFile.mkdirs()
        file.writeText("{ not valid json at all")

        assertEquals(PedalKeyMapping.DEFAULT, PedalSettingsStore(file).load())
    }

    @Test
    fun `load on a settings file naming an action this version doesn't know falls back to DEFAULT`() {
        val file = tempSettingsFile()
        file.parentFile.mkdirs()
        file.writeText("""{"bindings": {"SOME_FUTURE_ACTION": [21]}}""")

        assertEquals(PedalKeyMapping.DEFAULT, PedalSettingsStore(file).load())
    }

    @Test
    fun `save creates the parent directory if it doesn't exist yet`() {
        val nested =
            File(
                kotlin.io.path
                    .createTempDirectory("inkstave-pedal-settings-")
                    .toFile(),
                "nested/dir/pedal-settings.json",
            )
        PedalSettingsStore(nested).save(PedalKeyMapping.DEFAULT)
        assertEquals(true, nested.exists())
    }
}
