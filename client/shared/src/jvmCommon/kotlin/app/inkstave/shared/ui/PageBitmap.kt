package app.inkstave.shared.ui

import androidx.compose.ui.graphics.ImageBitmap

/**
 * Decodes a page's PNG bytes (as read via
 * [app.inkstave.shared.format.SmpkReader.readPageBytes]) into a Compose
 * [ImageBitmap]. Platform-specific: Android via `BitmapFactory`, desktop
 * via Skia's `Image.makeFromEncoded` -- see `PageBitmap.android.kt` /
 * `PageBitmap.desktop.kt`.
 */
expect fun decodePageBitmap(pngBytes: ByteArray): ImageBitmap
