package io.modernia.pixerion.interop

import io.modernia.pixerion.domain.Book
import io.modernia.pixerion.domain.BookId
import io.modernia.pixerion.domain.BookRef
import io.modernia.pixerion.domain.Catalog
import io.modernia.pixerion.domain.DownloadEvent
import io.modernia.pixerion.domain.Page
import io.modernia.pixerion.domain.SourceRef
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import org.junit.jupiter.api.Test
import java.nio.file.Files
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Direct tests for the Java-interop seam ([BlockingCatalog] + [Refs]).
 *
 * The seam is exercised end-to-end by the server module, but its coverage must live
 * here in `core` (JaCoCo reports per module). These tests drive it against an
 * in-memory [Catalog], asserting it preserves the [Catalog] contract's invariants
 * when bridged to blocking calls.
 */
class InteropTest {
    private val book = Book(BookId("id-1"), SourceRef("mangadex", "id-1"), "Berserk", "Guts wields a giant sword.")

    @Test
    fun `find returns the book, or null when absent`() {
        val blocking = BlockingCatalog(FakeCatalog(found = book))

        assertEquals(book, blocking.find(SourceRef("mangadex", "id-1")))
        assertNull(BlockingCatalog(FakeCatalog(found = null)).find(SourceRef("mangadex", "x")))
    }

    @Test
    fun `search returns matches, or an empty list when nothing matches`() {
        assertEquals(listOf(book), BlockingCatalog(FakeCatalog(searchResult = listOf(book))).search(mapOf("title" to "berserk")))
        assertEquals(emptyList(), BlockingCatalog(FakeCatalog(searchResult = emptyList())).search(mapOf("title" to "nope")))
    }

    @Test
    fun `download writes pages under the given root and summarizes them`() {
        val root = Files.createTempDirectory("pixerion-interop-test")
        try {
            val pages = (1..3).map { TestPage(chapter = "1", number = it, data = byteArrayOf(it.toByte())) }
            val summary = BlockingCatalog(FakeCatalog(found = book, pages = pages)).download(SourceRef("mangadex", "id-1"), root)

            assertEquals(book, summary?.book)
            assertEquals(3, summary?.stats?.pages)
            assertTrue(summary!!.directory.startsWith(root))
            val written = Files.walk(root).use { paths -> paths.filter { Files.isRegularFile(it) }.count() }
            assertEquals(3, written)
        } finally {
            root.toFile().deleteRecursively()
        }
    }

    @Test
    fun `download returns null when the book does not exist`() {
        val root = Files.createTempDirectory("pixerion-interop-test")
        try {
            assertNull(BlockingCatalog(FakeCatalog(found = null)).download(SourceRef("mangadex", "x"), root))
        } finally {
            root.toFile().deleteRecursively()
        }
    }

    @Test
    fun `idOf projects the value-class BookId onto a plain string`() {
        assertEquals("id-1", Refs.idOf(book))
    }

    @Test
    fun `render flattens a SourceRef to scheme colon value and a BookId to its raw value`() {
        assertEquals("mangadex:id-1", Refs.render(SourceRef("mangadex", "id-1")))
        assertEquals("id-1", Refs.render(BookId("id-1")))
    }

    private class FakeCatalog(
        private val found: Book? = null,
        private val searchResult: List<Book> = emptyList(),
        private val pages: List<Page> = emptyList(),
    ) : Catalog {
        override suspend fun find(ref: BookRef): Book? = found

        override suspend fun search(query: Map<String, String>): List<Book> = searchResult

        override fun download(ref: BookRef): Flow<DownloadEvent> = pages.asDownloadEvents()
    }

    private class TestPage(
        override val chapter: String,
        override val number: Int,
        private val data: ByteArray,
    ) : Page {
        override val filename: String = "$number.png"

        override suspend fun bytes(): ByteArray = data
    }
}

/** Projects a flat page list to the structured event stream a [Catalog] now emits. */
private fun List<Page>.asDownloadEvents(): Flow<DownloadEvent> {
    val byChapter = groupBy { it.chapter } // LinkedHashMap: preserves first-seen order
    return flow {
        emit(DownloadEvent.Manifest(byChapter.size))
        byChapter.forEach { (label, chapterPages) ->
            emit(DownloadEvent.ChapterStarted(label, label, chapterPages.size))
            chapterPages.forEach { emit(DownloadEvent.PageReady(label, it)) }
        }
    }
}
