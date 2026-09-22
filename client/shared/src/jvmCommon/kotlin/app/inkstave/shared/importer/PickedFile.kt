package app.inkstave.shared.importer

/**
 * One file the user picked via the platform file picker (Android's Storage
 * Access Framework, the desktop `JFileChooser`) -- see `androidApp`'s
 * `DocumentPicker.kt` and `desktopApp`'s `DesktopFilePicker.kt` for how each
 * platform actually produces these. [displayName] is used as the score's
 * initial title guess and recorded as `manifest.json`'s
 * `source.details.originalFilename` (`docs/format-spec.md`) -- M1 has no
 * OCR yet (that's M4), so the filename is the only title signal available.
 */
data class PickedFile(
    val bytes: ByteArray,
    val displayName: String,
)
