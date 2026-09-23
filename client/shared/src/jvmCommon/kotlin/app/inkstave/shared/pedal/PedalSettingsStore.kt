package app.inkstave.shared.pedal

import androidx.compose.ui.input.key.Key
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import java.io.File
import java.io.IOException

/**
 * The on-disk shape of a saved [PedalKeyMapping] -- deliberately not the
 * `.smpk` format's unknown-field-preserving pattern (`format.Manifest` and
 * friends): that pattern exists so a *portable, cross-device* file survives
 * being read by an older client. This is a local, single-device UI
 * preference with exactly one reader (this same app, this same version),
 * so a plain serializable DTO is the right amount of ceremony, not a
 * shortcut.
 *
 * [Key] itself isn't `@Serializable` (it's an external library type), so
 * this stores each bound key as its raw [Key.keyCode] and converts to/from
 * [PedalKeyMapping] at the boundary ([PedalSettingsStore.load]/[save])
 * rather than teaching [PedalKeyMapping] anything about serialization.
 */
@Serializable
private data class PedalSettingsDto(
    val bindings: Map<String, List<Long>>,
)

/**
 * Reads/writes a [PedalKeyMapping] at [file] -- the desktop and Android
 * entry points each pass a different, platform-appropriate [file] (see
 * `DesktopSettingsPaths` and `MainActivity`), mirroring how
 * `LibraryImporter`'s library directory is wired per-platform rather than
 * guessed at from inside shared code.
 */
class PedalSettingsStore(
    private val file: File,
) {
    private val json = Json { prettyPrint = true }

    /**
     * The saved mapping, or [PedalKeyMapping.DEFAULT] if [file] doesn't exist yet
     * (first run) or can't be parsed as valid settings. A corrupted or
     * hand-edited-into-garbage settings file must never crash page turning --
     * falling back to the default (which the user can re-customize) is always a
     * safe, recoverable outcome, unlike propagating the error would be.
     */
    fun load(): PedalKeyMapping {
        if (!file.exists()) return PedalKeyMapping.DEFAULT
        return try {
            val dto = json.decodeFromString(PedalSettingsDto.serializer(), file.readText())
            PedalKeyMapping(
                dto.bindings
                    .mapKeys { (name, _) -> PedalAction.valueOf(name) }
                    .mapValues { (_, codes) -> codes.map { Key(it) }.toSet() },
            )
        } catch (e: SerializationException) {
            PedalKeyMapping.DEFAULT
        } catch (e: IllegalArgumentException) {
            // PedalAction.valueOf on a name from a settings file written by a future/
            // different app version with an action this version doesn't know about.
            PedalKeyMapping.DEFAULT
        } catch (e: IOException) {
            PedalKeyMapping.DEFAULT
        }
    }

    /** Persists [mapping], creating [file]'s parent directory if needed. */
    fun save(mapping: PedalKeyMapping) {
        file.parentFile?.mkdirs()
        val dto =
            PedalSettingsDto(
                mapping.bindings.entries.associate { (action, keys) -> action.name to keys.map { it.keyCode }.toList() },
            )
        file.writeText(json.encodeToString(PedalSettingsDto.serializer(), dto))
    }
}
