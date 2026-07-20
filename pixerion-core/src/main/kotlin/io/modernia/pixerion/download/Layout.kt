package io.modernia.pixerion.download

import io.modernia.pixerion.domain.Book
import io.modernia.pixerion.domain.Page
import io.modernia.pixerion.domain.SourceRef

/**
 * Decides the *relative* location of downloaded content, as a list of path
 * segments (e.g. `["mangadex", "Berserk", "ch-1", "001.png"]`).
 *
 * Deliberately delivery-agnostic: the segments carry the naming/organization
 * convention, and each [PageSink] resolves them against its own medium — a
 * filesystem sink against a root directory, an object-store sink joined into a
 * key. Keeping the convention here lets every sink organise downloads identically.
 */
interface Layout {
    /** Segments locating [book]'s container, relative to a sink's root. */
    fun bookPath(book: Book): List<String>

    /** Segments locating a [page] of [book], relative to a sink's root. */
    fun pagePath(
        book: Book,
        page: Page,
    ): List<String>
}

/**
 * The standard layout: `<source>/<book>/chapters/ch-<chapter>/<NNN>.<ext>`, with
 * each segment reduced to filesystem-safe characters.
 *
 * Raw chapters live under a dedicated `chapters/` purpose layer beneath the book,
 * so other purposes (e.g. the bundler's `cbz/`) sit as siblings rather than mixing
 * archives in with the image folders.
 */
class StandardLayout : Layout {
    override fun bookPath(book: Book): List<String> = listOf(slug(sourceOf(book)), slug(book.title.ifBlank { book.id.value }))

    override fun pagePath(
        book: Book,
        page: Page,
    ): List<String> {
        // The filename comes from the remote source: accept only a plain alphanumeric
        // extension so it can't smuggle separators (or anything else) into the path.
        val extension = page.filename.substringAfterLast('.', "").takeIf { it.matches(EXTENSION) } ?: "jpg"
        return bookPath(book) +
            listOf(
                CHAPTERS_DIR,
                "ch-" + slug(page.chapter.ifBlank { "oneshot" }),
                "%03d.%s".format(page.number, extension),
            )
    }

    private fun sourceOf(book: Book): String =
        when (val ref = book.ref) {
            is SourceRef -> ref.scheme
            else -> "catalog"
        }

    companion object {
        /**
         * The purpose layer holding raw downloaded chapters
         * (`<book>/chapters/ch-<chapter>/<NNN>.<ext>`), kept beside sibling layers
         * such as the bundler's `cbz/`.
         */
        const val CHAPTERS_DIR = "chapters"

        private val UNSAFE = Regex("""[^A-Za-z0-9._-]+""")

        private val EXTENSION = Regex("""[A-Za-z0-9]{1,8}""")

        /**
         * Collapses a string into a single filesystem-safe path segment. Values come
         * from remote sources (book titles, chapter labels), so beyond stripping unsafe
         * characters this must also refuse the dot-only segments (`.`/`..`) that would
         * make the segment traverse instead of name.
         */
        private fun slug(value: String): String {
            val cleaned = value.trim().replace(UNSAFE, "-").trim('-')
            return if (cleaned.isEmpty() || cleaned.all { it == '.' }) "_" else cleaned
        }
    }
}
