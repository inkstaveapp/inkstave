package app.inkstave.shared.importer

/**
 * A file picked with the platform file picker. [displayName] seeds the score's title and is recorded
 * as `source.details.originalFilename` in `manifest.json`.
 */
data class PickedFile(
    val bytes: ByteArray,
    val displayName: String,
)
