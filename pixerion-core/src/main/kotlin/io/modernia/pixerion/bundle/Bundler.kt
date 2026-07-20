package io.modernia.pixerion.bundle

import io.modernia.pixerion.download.StandardLayout
import java.io.BufferedOutputStream
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.util.zip.CRC32
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlin.io.path.name

/**
 * Packages an already-downloaded book into `.cbz` archives — one per chapter —
 * for reading apps like Panel, Tachiyomi or Komga.
 *
 * A `.cbz` is simply a ZIP of a chapter's images read in filename order, which
 * the [io.modernia.pixerion.download.StandardLayout] already produces
 * (`001.png`, `002.jpg`, …). So bundling is a pure filesystem operation over the
 * `<book>/chapters/ch-<chapter>/<NNN>.<ext>` tree — it needs no catalog or
 * network, which is why this lives apart from the download path.
 *
 * Images are already compressed, so entries are stored uncompressed
 * ([ZipEntry.STORED]); the archive just collates them. Each archive is written
 * to a temporary file and atomically moved into place, so a failure mid-write
 * never leaves a half-formed `.cbz` behind.
 *
 * @param overwrite when `false` (default), existing `.cbz` files are left
 *   untouched and counted as skipped; when `true`, they are rewritten.
 */
class Bundler(
    private val overwrite: Boolean = false,
) {
    /**
     * Bundles each `ch-*` directory under [bookDir]'s `chapters/` layer into a
     * `.cbz` written to [output] — by default a sibling `cbz/` layer, so the
     * archives are never interleaved with the raw image folders. [onArchive] is
     * invoked with the path of each archive as it is written, for progress
     * reporting.
     *
     * @return a [Summary] of what was produced.
     * @throws IllegalArgumentException if [bookDir] is not a directory.
     */
    fun bundle(
        bookDir: Path,
        output: Path = bookDir.resolve(DEFAULT_SUBDIR),
        onArchive: (Path) -> Unit = {},
    ): Summary {
        require(Files.isDirectory(bookDir)) { "Not a directory: $bookDir" }

        val chaptersDir = bookDir.resolve(StandardLayout.CHAPTERS_DIR)
        val chapters =
            if (!Files.isDirectory(chaptersDir)) {
                emptyList()
            } else {
                Files
                    .list(chaptersDir)
                    .use { entries ->
                        entries
                            .filter { Files.isDirectory(it) && it.name.startsWith(CHAPTER_PREFIX) }
                            .toList()
                    }.sortedWith(compareBy({ chapterOrder(it) }, { it.name }))
            }

        Files.createDirectories(output)
        var bundled = 0
        var skipped = 0
        for (chapter in chapters) {
            val pages =
                Files
                    .list(chapter)
                    .use { entries ->
                        entries
                            .filter { Files.isRegularFile(it) }
                            // A stray file named like the metadata entry is not a page — including
                            // it would collide with the generated one (duplicate ZIP entry).
                            .filter { !it.name.equals(COMIC_INFO, ignoreCase = true) }
                            .toList()
                    }.sortedBy { it.name }
            if (pages.isEmpty()) continue

            val archive = output.resolve("${bookDir.name} - ${chapter.name}.cbz")
            if (Files.exists(archive) && !overwrite) {
                skipped++
                continue
            }
            writeArchive(archive, pages, comicInfo(bookDir.name, chapter, pages.size))
            bundled++
            onArchive(archive)
        }
        return Summary(bookDir, output, bundled, skipped, chapters.size)
    }

    /**
     * Writes [pages] into [target] as a stored (uncompressed) ZIP, atomically,
     * prefixed with a [comicInfo] `ComicInfo.xml` entry at the archive root.
     */
    private fun writeArchive(
        target: Path,
        pages: List<Path>,
        comicInfo: ByteArray,
    ) {
        // A unique temp name (not a fixed "<name>.tmp") so concurrent bundles of the
        // same book can't corrupt each other's half-written archive.
        val temp = Files.createTempFile(target.parent, target.name, ".tmp")
        try {
            ZipOutputStream(BufferedOutputStream(Files.newOutputStream(temp))).use { zip ->
                zip.setMethod(ZipOutputStream.STORED)
                storeEntry(zip, COMIC_INFO, comicInfo)
                for (page in pages) {
                    storeEntry(zip, page.name, Files.readAllBytes(page))
                }
            }
            try {
                Files.move(temp, target, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
            } catch (e: AtomicMoveNotSupportedException) {
                Files.move(temp, target, StandardCopyOption.REPLACE_EXISTING)
            }
        } catch (e: Exception) {
            // Never leave a half-written .tmp behind (disk full, unreadable page, …).
            Files.deleteIfExists(temp)
            throw e
        }
    }

    /** Appends [bytes] to [zip] as a single stored (uncompressed) entry named [name]. */
    private fun storeEntry(
        zip: ZipOutputStream,
        name: String,
        bytes: ByteArray,
    ) {
        val entry =
            ZipEntry(name).apply {
                method = ZipEntry.STORED
                size = bytes.size.toLong()
                compressedSize = bytes.size.toLong()
                crc = CRC32().apply { update(bytes) }.value
            }
        zip.putNextEntry(entry)
        zip.write(bytes)
        zip.closeEntry()
    }

    /**
     * Builds a minimal `ComicInfo.xml` — the cross-reader metadata standard read
     * by Panel, Komga, Kavita, Mihon and others — from purely local information:
     * the book folder name as `Series`, the chapter label as `Number`, and the
     * image count as `PageCount`. No catalog or network is consulted.
     */
    private fun comicInfo(
        series: String,
        chapter: Path,
        pageCount: Int,
    ): ByteArray =
        buildString {
            append("<?xml version=\"1.0\" encoding=\"utf-8\"?>\n")
            append("<ComicInfo>\n")
            append("  <Series>").append(escapeXml(series)).append("</Series>\n")
            append("  <Number>").append(escapeXml(chapter.name.removePrefix(CHAPTER_PREFIX))).append("</Number>\n")
            append("  <PageCount>").append(pageCount).append("</PageCount>\n")
            append("</ComicInfo>\n")
        }.toByteArray(Charsets.UTF_8)

    /** Escapes the three characters that are unsafe in XML element text. */
    private fun escapeXml(value: String): String =
        value
            .replace("&", "&amp;")
            .replace("<", "&lt;")
            .replace(">", "&gt;")

    /** Numeric sort key for a `ch-*` directory; non-numeric labels sort last. */
    private fun chapterOrder(dir: Path): Double = dir.name.removePrefix(CHAPTER_PREFIX).toDoubleOrNull() ?: Double.MAX_VALUE

    /** The outcome of a completed bundling run. */
    data class Summary(
        val bookDir: Path,
        val output: Path,
        val bundled: Int,
        val skipped: Int,
        val chapters: Int,
    )

    private companion object {
        const val CHAPTER_PREFIX = "ch-"

        /** Default output folder beneath the book, kept apart from the image dirs. */
        const val DEFAULT_SUBDIR = "cbz"

        /** The cross-reader metadata file, conventionally at the archive root. */
        const val COMIC_INFO = "ComicInfo.xml"
    }
}
