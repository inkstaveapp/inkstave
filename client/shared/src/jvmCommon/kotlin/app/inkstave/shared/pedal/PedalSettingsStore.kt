package app.inkstave.shared.pedal

import androidx.compose.ui.input.key.Key
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import java.io.File
import java.io.IOException

/**
 * On-disk shape of a saved [PedalKeyMapping]. A plain DTO: unlike `.smpk` files, this local setting
 * needn't preserve unknown fields. Keys are stored as [Key.keyCode], since [Key] isn't serializable.
 */
@Serializable
private data class PedalSettingsDto(
    val bindings: Map<String, List<Long>>,
)

/** Reads and writes a [PedalKeyMapping] at [file]; each platform supplies its own settings location. */
class PedalSettingsStore(
    private val file: File,
) {
    private val json = Json { prettyPrint = true }

    /** The saved mapping, or [PedalKeyMapping.DEFAULT] if there is none or it can't be read: a bad file must never break page turning. */
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
            // An action name from another app version that this one doesn't know.
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
