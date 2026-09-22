package app.inkstave.shared.ui

import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.toComposeImageBitmap
import org.jetbrains.skia.Image

actual fun decodePageBitmap(pngBytes: ByteArray): ImageBitmap = Image.makeFromEncoded(pngBytes).toComposeImageBitmap()
