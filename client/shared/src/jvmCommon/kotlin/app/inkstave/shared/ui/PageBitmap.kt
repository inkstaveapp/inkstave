package app.inkstave.shared.ui

import androidx.compose.ui.graphics.ImageBitmap

/**
 * Decodes a page's PNG bytes (from [app.inkstave.shared.format.SmpkReader.readPageBytes]) into a Compose
 * [ImageBitmap]: `BitmapFactory` on Android, Skia on desktop.
 */
expect fun decodePageBitmap(pngBytes: ByteArray): ImageBitmap
