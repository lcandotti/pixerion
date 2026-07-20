package output

import io.modernia.pixerion.domain.Book
import io.modernia.pixerion.domain.BookId
import io.modernia.pixerion.domain.BookRef
import io.modernia.pixerion.domain.SourceRef
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class BookViewTest {
    private fun book(
        title: String = "Berserk",
        synopsis: String = "Guts wields a giant sword.",
        ref: BookRef = SourceRef("mangadex", "id-1"),
    ): Book = Book(BookId("id-1"), ref, title, synopsis)

    @Test
    fun `renders title, id, source badge and synopsis`() {
        val card = book().render()

        assertTrue(card.contains("Berserk"))
        assertTrue(card.contains("id-1"))
        assertTrue(card.contains("mangadex"))
        assertTrue(card.contains("Guts wields a giant sword."))
    }

    @Test
    fun `a blank title falls back to Untitled`() {
        assertTrue(book(title = "   ").render().contains("Untitled"))
    }

    @Test
    fun `a long synopsis is clipped with an ellipsis`() {
        val card = book(synopsis = "x".repeat(200)).render()

        assertTrue(card.contains("…"))
        assertFalse(card.contains("x".repeat(200)))
    }

    @Test
    fun `an empty synopsis omits the synopsis line`() {
        assertFalse(book(synopsis = "   ").render().contains("…"))
    }

    @Test
    fun `only the first line of a multi-line synopsis is shown`() {
        val card = book(synopsis = "first line\nsecond line").render()

        assertTrue(card.contains("first line"))
        assertFalse(card.contains("second line"))
    }

    @Test
    fun `a portable book id renders the catalog source label`() {
        assertTrue(book(ref = BookId("id-1")).render().contains("catalog"))
    }
}
