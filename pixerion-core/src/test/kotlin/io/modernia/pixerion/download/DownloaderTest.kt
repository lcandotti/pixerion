package io.modernia.pixerion.download

import io.modernia.pixerion.domain.Book
import io.modernia.pixerion.domain.BookId
import io.modernia.pixerion.domain.BookRef
import io.modernia.pixerion.domain.Catalog
import io.modernia.pixerion.domain.DownloadEvent
import io.modernia.pixerion.domain.Page
import io.modernia.pixerion.domain.SourceRef
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test
import java.nio.file.Files
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class DownloaderTest {
    @Test
    fun `downloads every page to disk under the layout`() =
        runTest {
            val root = Files.createTempDirectory("pixerion-test")
            try {
                val book = Book(BookId("id1"), SourceRef("mangadex", "id1"), "My Book", "synopsis")
                val pages =
                    (1..2).flatMap { chapter ->
                        (1..3).map { TestPage(chapter = chapter.toString(), number = it, data = byteArrayOf(it.toByte())) }
                    }
                val catalog = FakeCatalog(book, pages)

                val summary = Downloader(root = root, workers = 3).download(catalog, SourceRef("mangadex", "id1"))

                assertEquals(6, summary?.stats?.pages)
                assertEquals(2, summary?.stats?.chapters)
                val files = Files.walk(root).filter { Files.isRegularFile(it) }.toList()
                assertEquals(6, files.size)
                // standard layout: <root>/mangadex/My-Book/chapters/ch-1/001.png
                val firstPage =
                    root
                        .resolve("mangadex")
                        .resolve("My-Book")
                        .resolve("chapters")
                        .resolve("ch-1")
                        .resolve("001.png")
                assertTrue(Files.exists(firstPage))
                assertContentEquals(byteArrayOf(1), Files.readAllBytes(firstPage))
            } finally {
                root.toFile().deleteRecursively()
            }
        }

    @Test
    fun `defaultRoot is a dot-pixerion directory under the user home`() {
        val root = Downloader.defaultRoot()

        assertEquals(".pixerion", root.fileName.toString())
        assertEquals(System.getProperty("user.home"), root.parent.toString())
    }

    @Test
    fun `returns null when the book does not exist`() =
        runTest {
            val root = Files.createTempDirectory("pixerion-test")
            try {
                val summary = Downloader(root = root).download(FakeCatalog(null, emptyList()), SourceRef("mangadex", "x"))
                assertNull(summary)
            } finally {
                root.toFile().deleteRecursively()
            }
        }

    private class FakeCatalog(
        private val book: Book?,
        private val pages: List<Page>,
    ) : Catalog {
        override suspend fun find(ref: BookRef): Book? = book

        override suspend fun search(query: Map<String, String>): List<Book> = listOfNotNull(book)

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
