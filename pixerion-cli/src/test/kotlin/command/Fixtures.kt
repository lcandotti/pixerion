package command

import io.modernia.pixerion.domain.Book
import io.modernia.pixerion.domain.BookId
import io.modernia.pixerion.domain.BookRef
import io.modernia.pixerion.domain.Catalog
import io.modernia.pixerion.domain.DownloadEvent
import io.modernia.pixerion.domain.Page
import io.modernia.pixerion.domain.SourceRef
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import java.io.ByteArrayOutputStream
import java.io.PrintStream

/**
 * A [Catalog] that returns canned data so commands can be driven end-to-end
 * without touching the network. Any operation can be made to fail by passing
 * [boom], to exercise the commands' `CatalogException` handling.
 */
internal class FakeCatalog(
    private val searchResult: List<Book> = emptyList(),
    private val found: Book? = null,
    private val pages: List<Page> = emptyList(),
    private val boom: Throwable? = null,
) : Catalog {
    override suspend fun find(ref: BookRef): Book? = boom?.let { throw it } ?: found

    override suspend fun search(query: Map<String, String>): List<Book> = boom?.let { throw it } ?: searchResult

    override fun download(ref: BookRef): Flow<DownloadEvent> {
        val byChapter = pages.groupBy { it.chapter } // LinkedHashMap: preserves first-seen order
        return flow {
            emit(DownloadEvent.Manifest(byChapter.size))
            byChapter.forEach { (label, chapterPages) ->
                emit(DownloadEvent.ChapterStarted(label, label, chapterPages.size))
                chapterPages.forEach { emit(DownloadEvent.PageReady(label, it)) }
            }
        }
    }
}

/** An in-memory [Page] whose bytes are fixed. */
internal class FakePage(
    override val chapter: String,
    override val number: Int,
    override val filename: String,
    private val data: ByteArray = byteArrayOf(1, 2, 3),
) : Page {
    override suspend fun bytes(): ByteArray = data
}

internal fun sampleBook(
    id: String = "id-1",
    title: String = "Berserk",
    synopsis: String = "Guts wields a giant sword.",
): Book = Book(BookId(id), SourceRef("mangadex", id), title, synopsis)

/** The captured result of running a command: its exit code and what it printed. */
internal data class CapturedRun(
    val code: Int,
    val out: String,
    val err: String,
)

/** Runs [block] with stdout/stderr redirected, returning its exit code and output. */
internal fun captureOutput(block: () -> Int): CapturedRun {
    val out = ByteArrayOutputStream()
    val err = ByteArrayOutputStream()
    val originalOut = System.out
    val originalErr = System.err
    System.setOut(PrintStream(out))
    System.setErr(PrintStream(err))
    return try {
        val code = block()
        System.out.flush()
        System.err.flush()
        CapturedRun(code, out.toString(), err.toString())
    } finally {
        System.setOut(originalOut)
        System.setErr(originalErr)
    }
}
