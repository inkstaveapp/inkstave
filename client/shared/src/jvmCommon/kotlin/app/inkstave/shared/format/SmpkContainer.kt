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
 * One page's raster bytes plus its metadata, as [SmpkWriter] needs them. [pngBytes] is
 * already-encoded PNG: both Android and the JVM decode it natively, with no extra codec.
 */
data class SmpkPage(
    val id: String,
    val pngBytes: ByteArray,
    val meta: PageMeta,
)

/**
 * Writes a `.smpk` package (`docs/format-spec.md`) as a zip archive: `manifest.json`,
 * `parts/<part-id>/part.json`, and `pages/<page-id>.png` + `.meta.json` per page.
 * Takes exactly one [Part]: multi-part scores aren't supported yet. Annotations are
 * added afterwards by [SmpkUpdater].
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
 * Reads a `.smpk` package. Uses [java.util.zip.ZipFile], not a `ZipInputStream`, so each
 * entry is random-access: reading one page never decompresses the pages before it.
 *
 * Holds the zip file open; [close] it (or use [use]) when done.
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
     * Reads `annotations/<pageId>.json`, or returns [AnnotationLayer.empty] if the page has
     * never been annotated. Unlike the other readers, a missing entry is normal here.
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
 * Updates a page's annotation layer in an existing `.smpk` file. `java.util.zip` can't
 * replace an entry in place, so this rewrites the whole package to a sibling temp file
 * (copying every other entry unchanged) and atomically moves it over [file].
 *
 * A full rewrite per save is fine for single scores of a few MB; callers debounce saves
 * so this runs once per edit, not per pointer move.
 */
object SmpkUpdater {
    /**
     * Replaces (or adds) the `annotations/<layer.pageId>.json` entry in [file] with [layer].
     * Every other entry survives byte-for-byte.
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
     * Moves [source] onto [destination], atomically where the filesystem supports it so a
     * reader never sees a half-written `.smpk`; otherwise a plain move.
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
