package app.inkstave.desktop

import app.inkstave.shared.importer.PickedFile
import javax.swing.JFileChooser
import javax.swing.filechooser.FileNameExtensionFilter

/**
 * Blocking `JFileChooser` pickers, the desktop counterpart of Android's `DocumentPicker`. They block
 * until the user responds, so callers run them on `Dispatchers.IO`.
 */
object DesktopFilePicker {
    /** Opens a single-PDF file chooser; `null` if the user cancelled. */
    fun pickPdf(): PickedFile? {
        val chooser =
            JFileChooser().apply {
                dialogTitle = "Import a PDF"
                fileFilter = FileNameExtensionFilter("PDF files", "pdf")
                isMultiSelectionEnabled = false
            }
        if (chooser.showOpenDialog(null) != JFileChooser.APPROVE_OPTION) return null
        val file = chooser.selectedFile ?: return null
        return PickedFile(file.readBytes(), file.name)
    }

    /** Opens a multi-select image file chooser; empty if the user cancelled. */
    fun pickImages(): List<PickedFile> {
        val chooser =
            JFileChooser().apply {
                dialogTitle = "Import images"
                fileFilter = FileNameExtensionFilter("Image files", "png", "jpg", "jpeg")
                isMultiSelectionEnabled = true
            }
        if (chooser.showOpenDialog(null) != JFileChooser.APPROVE_OPTION) return emptyList()
        return chooser.selectedFiles.sortedBy { it.name }.map { PickedFile(it.readBytes(), it.name) }
    }
}
