package io.modernia.pixerion.download

import io.modernia.pixerion.domain.Book
import io.modernia.pixerion.domain.BookId
import io.modernia.pixerion.domain.Page
import io.modernia.pixerion.domain.SourceRef
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals

/**
 * [StandardLayout] shapes path segments from *remote* data (titles, chapter labels,
 * filenames), so beyond the happy path it must neutralize anything that would
 * traverse or escape: dot-only segments and separator-smuggling extensions.
 */
class LayoutTest {
    private val layout = StandardLayout()

    @Test
    fun `lays out a page under source, book, chapters and chapter`() {
        val segments = layout.pagePath(book(title = "My Book"), page(chapter = "1", filename = "p1.png"))

        assertEquals(listOf("mangadex", "My-Book", "chapters", "ch-1", "001.png"), segments)
    }

    @Test
    fun `a dot-only title cannot make the book segment traverse`() {
        assertEquals(listOf("mangadex", "_"), layout.bookPath(book(title = "..")))
        assertEquals(listOf("mangadex", "_"), layout.bookPath(book(title = ".")))
    }

    @Test
    fun `a hostile filename cannot smuggle separators through the extension`() {
        val segments = layout.pagePath(book(title = "My Book"), page(chapter = "1", filename = "a./x/y"))

        assertEquals("001.jpg", segments.last())
    }

    @Test
    fun `a filename without an extension falls back to jpg`() {
        val segments = layout.pagePath(book(title = "My Book"), page(chapter = "1", filename = "noext"))

        assertEquals("001.jpg", segments.last())
    }

    @Test
    fun `a blank chapter label becomes the oneshot directory`() {
        val segments = layout.pagePath(book(title = "My Book"), page(chapter = "", filename = "p1.png"))

        assertEquals("ch-oneshot", segments[3])
    }

    private fun book(title: String): Book = Book(BookId("id1"), SourceRef("mangadex", "id1"), title, "synopsis")

    private fun page(
        chapter: String,
        filename: String,
    ): Page =
        object : Page {
            override val chapter = chapter
            override val number = 1
            override val filename = filename

            override suspend fun bytes(): ByteArray = ByteArray(0)
        }
}
