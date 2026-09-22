package app.inkstave.shared.format

import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipFile
import java.util.zip.ZipOutputStream

/**
 * One page's raster bytes plus its metadata document, as [SmpkWriter] needs
 * them. [pngBytes] is already-encoded PNG (see `docs/format-spec.md`'s
 * `pages/<page-id>.png` -- PNG rather than the originally-specified WebP for
 * M1, since it's natively supported on both Android and the JVM desktop
 * target with no new codec dependency; see the note at that entry in
 * `docs/format-spec.md`).
 */
data class SmpkPage(
    val id: String,
    val pngBytes: ByteArray,
    val meta: PageMeta,
)

/**
 * Writes a `.smpk` package (`docs/format-spec.md`) as a zip archive:
 * `manifest.json`, `parts/<part-id>/part.json`, and `pages/<page-id>.png` +
 * `pages/<page-id>.meta.json` per page. M1 is single-part only (multi-part
 * bundling is M5, `docs/format-spec.md`'s "Reserved for later" /
 * `ROADMAP.md` M5), so this writer takes exactly one [Part] rather than a
 * list.
 *
 * Deliberately excluded here (not part of the M1 container shape):
 * `annotations/` (M2), `pages/<id>.raw.jpg` (there's no raster distinct from
 * the displayed page until M4's cleanup pipeline exists), and `thumbnails/`
 * (regenerable cache, deferred).
 */
object SmpkWriter {
    /** Writes [manifest]/[part]/[pages] to [destination] as a `.smpk` zip, overwriting it if it exists. */
    fun write(
        destination: File,
        manifest: Manifest,
        part: Part,
        pages: List<SmpkPage>,
    ) {
        destination.parentFile?.mkdirs()
        ZipOutputStream(destination.outputStream().buffered()).use { zip ->
            zip.putEntry("manifest.json", ManifestJson.encode(manifest).encodeToByteArray())
            zip.putEntry("parts/${part.id}/part.json", PartJson.encode(part).encodeToByteArray())
            for (page in pages) {
                zip.putEntry("pages/${page.id}.png", page.pngBytes)
                zip.putEntry("pages/${page.id}.meta.json", PageMetaJson.encode(page.meta).encodeToByteArray())
            }
        }
    }

    private fun ZipOutputStream.putEntry(
        name: String,
        bytes: ByteArray,
    ) {
        putNextEntry(ZipEntry(name))
        write(bytes)
        closeEntry()
    }
}

/**
 * Reads a `.smpk` package. Backed by [java.util.zip.ZipFile] rather than
 * [java.util.zip.ZipInputStream] specifically so [readManifest] and
 * [readPageBytes] are genuinely random-access -- reading one page's bytes
 * never requires scanning or decompressing every entry before it, which
 * matters once a score has many pages (the same "load only what's on
 * screen" principle `docs/performance.md` applies to annotations applies
 * here to page bytes).
 *
 * Caller-owned: must be [close]d (or used via [use]) once done, since it
 * holds the underlying zip file open.
 */
class SmpkReader(
    file: File,
) : AutoCloseable {
    private val zip = ZipFile(file)

    /** Reads `manifest.json` without touching any page data. */
    fun readManifest(): Manifest = readEntry("manifest.json", ManifestJson::decode)

    /** Reads `parts/<partId>/part.json`. */
    fun readPart(partId: String): Part = readEntry("parts/$partId/part.json", PartJson::decode)

    /** Reads `pages/<pageId>.meta.json`. */
    fun readPageMeta(pageId: String): PageMeta = readEntry("pages/$pageId.meta.json", PageMetaJson::decode)

    /** Reads one page's raw PNG bytes, decompressing only that entry. */
    fun readPageBytes(pageId: String): ByteArray {
        val entry = zip.getEntry("pages/$pageId.png") ?: missing("pages/$pageId.png")
        return zip.getInputStream(entry).use { it.readBytes() }
    }

    private fun <T> readEntry(
        name: String,
        parse: (String) -> T,
    ): T {
        val entry = zip.getEntry(name) ?: missing(name)
        val text = zip.getInputStream(entry).use { it.readBytes().decodeToString() }
        return parse(text)
    }

    private fun missing(entryName: String): Nothing = error("'$entryName' not found in ${zip.name} -- corrupt or not a valid .smpk package")

    override fun close() = zip.close()
}
