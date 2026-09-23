package app.inkstave.shared.format

import java.io.File
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.util.zip.ZipEntry
import java.util.zip.ZipFile
import java.util.zip.ZipOutputStream

/** Writes [bytes] as a new entry named [name] in this [ZipOutputStream], then closes the entry. */
private fun ZipOutputStream.putEntry(
    name: String,
    bytes: ByteArray,
) {
    putNextEntry(ZipEntry(name))
    write(bytes)
    closeEntry()
}

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

    /**
     * Reads `annotations/<pageId>.json`. Most pages have never been
     * annotated, so a missing entry is the *expected* case, not an error --
     * this returns [AnnotationLayer.empty] for [pageId] rather than
     * throwing, unlike [readManifest]/[readPart]/[readPageMeta], which
     * throw on a missing entry because manifest/part/page-meta are always
     * present for a valid `.smpk`.
     */
    fun readAnnotationLayer(pageId: String): AnnotationLayer {
        val entry = zip.getEntry("annotations/$pageId.json") ?: return AnnotationLayer.empty(pageId)
        val text = zip.getInputStream(entry).use { it.readBytes().decodeToString() }
        return AnnotationLayerJson.decode(text)
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

/**
 * Updates a page's annotation layer in an *existing* `.smpk` file on disk
 * (M2: annotation edits happen against a score [SmpkWriter] already wrote
 * during M1 import, not a fresh package). `java.util.zip`'s
 * [ZipOutputStream] has no in-place entry replacement, so the pragmatic
 * approach here is a full rewrite: read every existing entry from [file],
 * write a fresh zip to a sibling temp file with only the target
 * `annotations/<pageId>.json` entry swapped in (every other entry's bytes
 * copied straight through, unchanged and un-recompressed), then atomically
 * replace [file] with it.
 *
 * This is a real cost -- one full read+rewrite of the package per saved
 * edit, not an incremental append -- but a deliberate M2 scope call, not a
 * shortcut: `.smpk` files are small, single-digit-MB single scores, not
 * whole libraries in one file, so a full rewrite is milliseconds, not a
 * user-visible stall. [app.inkstave.shared.ui.AnnotationOverlay] debounces
 * calls into this (save after a gesture ends and a short idle period) so
 * it runs once per logical edit, not once per pointer-move event. If a
 * later pass finds this is actually a measured bottleneck (e.g. once very
 * large multi-part scores exist, M5+), an incremental zip-patching approach
 * is the candidate optimization -- not needed yet.
 */
object SmpkUpdater {
    /**
     * Replaces (or adds, if none existed) the `annotations/<layer.pageId>.json`
     * entry in [file] with [layer], leaving every other entry -- manifest,
     * part, every page's PNG bytes and metadata, and every *other* page's
     * annotation layer -- untouched. See [SmpkAnnotationUpdateTest] for the
     * regression test proving untouched entries survive byte-for-byte.
     */
    fun updateAnnotationLayer(
        file: File,
        layer: AnnotationLayer,
    ) {
        val entryName = "annotations/${layer.pageId}.json"
        val tempFile = File.createTempFile(".inkstave-update-", ".smpk.tmp", file.absoluteFile.parentFile)
        try {
            ZipFile(file).use { source ->
                ZipOutputStream(tempFile.outputStream().buffered()).use { out ->
                    var replacedExisting = false
                    for (entry in source.entries()) {
                        if (entry.name == entryName) {
                            out.putEntry(entryName, AnnotationLayerJson.encode(layer).encodeToByteArray())
                            replacedExisting = true
                        } else {
                            out.putNextEntry(ZipEntry(entry.name))
                            source.getInputStream(entry).use { it.copyTo(out) }
                            out.closeEntry()
                        }
                    }
                    if (!replacedExisting) {
                        out.putEntry(entryName, AnnotationLayerJson.encode(layer).encodeToByteArray())
                    }
                }
            }
            replaceAtomically(tempFile, file)
        } finally {
            tempFile.delete()
        }
    }

    /**
     * Moves [source] onto [destination], preferring an atomic move (so a
     * reader never sees a partially-written `.smpk`) but falling back to a
     * plain move if the filesystem doesn't support atomic moves across
     * these two paths (e.g. different filesystems -- shouldn't happen here
     * since [updateAnnotationLayer] creates [source] as a sibling of
     * [destination], but this is cheap insurance against that assumption
     * ever being violated).
     */
    private fun replaceAtomically(
        source: File,
        destination: File,
    ) {
        try {
            Files.move(
                source.toPath(),
                destination.toPath(),
                StandardCopyOption.REPLACE_EXISTING,
                StandardCopyOption.ATOMIC_MOVE,
            )
        } catch (_: java.nio.file.AtomicMoveNotSupportedException) {
            Files.move(source.toPath(), destination.toPath(), StandardCopyOption.REPLACE_EXISTING)
        }
    }
}
