package app.inkstave.shared.ui

import android.graphics.BitmapFactory
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap

actual fun decodePageBitmap(pngBytes: ByteArray): ImageBitmap = BitmapFactory.decodeByteArray(pngBytes, 0, pngBytes.size).asImageBitmap()
