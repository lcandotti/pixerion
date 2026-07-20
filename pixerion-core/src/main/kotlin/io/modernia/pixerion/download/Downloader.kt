package io.modernia.pixerion.download

import io.modernia.pixerion.domain.Book
import io.modernia.pixerion.domain.BookRef
import io.modernia.pixerion.domain.Catalog
import io.modernia.pixerion.domain.DownloadEvent
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger

/**
 * Downloads a book from a [Catalog] to disk, in parallel.
 *
 * The full capability lives here in `core` so any delivery module (CLI, a future
 * web app) reuses it by passing parameters — none of the orchestration leaks into
 * callers. It resolves the book, streams its pages, and fans the writes out to a
 * bounded pool of [workers] coroutines; the enclosing scope joins every write
 * before returning (a structured-concurrency worker pool with a built-in
 * wait-group). Files are placed under [root] according to [layout].
 *
 * @param root base directory downloads are written under (default `~/.pixerion`).
 * @param workers maximum number of pages downloaded/written concurrently.
 * @param layout how books and pages are organized beneath [root].
 * @param dispatcher the dispatcher the blocking page writes run on; defaults to
 *   [Dispatchers.IO]. Injectable so tests can supply a deterministic dispatcher.
 */
class Downloader(
    private val root: Path = defaultRoot(),
    private val workers: Int = DEFAULT_WORKERS,
    private val layout: Layout = StandardLayout(),
    private val dispatcher: CoroutineDispatcher = Dispatchers.IO,
) {
    /**
     * Downloads the book [ref] points at from [catalog], reporting live progress
     * to [progress] (a no-op by default).
     *
     * @return a [Summary] of what was written, or `null` if no such book exists.
     */
    suspend fun download(
        catalog: Catalog,
        ref: BookRef,
        progress: DownloadProgress = DownloadProgress.NONE,
    ): Summary? {
        val book = catalog.find(ref) ?: return null
        val pages = AtomicInteger()
        // Chapter state, keyed by the source's stable chapter id (not its label, which
        // may collide across chapters). Concurrent because writes fan out over workers.
        val chapters = ConcurrentHashMap<String, ChapterTally>()
        // Pages are written on parallel coroutines, so the collector's sequential
        // callbacks (onStart/onChapterStart) and the workers' callbacks
        // (onPageWritten/onChapterComplete) can race. Serialize all of them behind one
        // lock so [progress] observes them one at a time and needs no locking itself.
        val progressLock = Any()
        coroutineScope {
            val gate = Semaphore(workers)
            catalog.download(ref).collect { event ->
                when (event) {
                    is DownloadEvent.Manifest ->
                        synchronized(progressLock) { progress.onStart(event.chapters) }

                    is DownloadEvent.ChapterStarted -> {
                        chapters[event.chapterId] = ChapterTally(event.pages)
                        synchronized(progressLock) { progress.onChapterStart(event.chapterId, event.label, event.pages) }
                        // A page-less chapter emits no PageReady, so complete it now.
                        if (event.pages == 0) {
                            synchronized(progressLock) { progress.onChapterComplete(event.chapterId) }
                        }
                    }

                    is DownloadEvent.PageReady -> {
                        val page = event.page
                        val tally = chapters.getValue(event.chapterId)
                        gate.acquire() // bound concurrency (and back-pressure the collector)
                        launch(dispatcher) {
                            try {
                                val file = layout.pagePath(book, page).fold(root, Path::resolve)
                                Files.createDirectories(file.parent)
                                Files.write(file, page.bytes())
                                pages.incrementAndGet()
                                synchronized(progressLock) {
                                    // Increment inside the lock: bumping outside would let a later
                                    // count be reported before an earlier one, stepping progress back.
                                    val written = tally.written.incrementAndGet()
                                    progress.onPageWritten(event.chapterId, written, tally.total)
                                    if (written == tally.total) progress.onChapterComplete(event.chapterId)
                                }
                            } finally {
                                gate.release()
                            }
                        }
                    }
                }
            }
        }
        val directory = layout.bookPath(book).fold(root, Path::resolve)
        return Summary(book, directory, DownloadStats(pages.get(), chapters.size))
    }

    /** Per-chapter page tally used to detect chapter completion. */
    private class ChapterTally(
        val total: Int,
    ) {
        val written = AtomicInteger()
    }

    /** The outcome of a completed download. */
    data class Summary(
        val book: Book,
        val directory: Path,
        val stats: DownloadStats,
    )

    /**
     * Aggregate tally of a completed download.
     *
     * A single value object rather than loose counters, so delivery modules (CLI, a
     * future web app) consume one descriptor and new measures can be added without
     * widening call sites.
     *
     * @property pages number of pages written to disk.
     * @property chapters number of distinct chapters those pages span.
     */
    data class DownloadStats(
        val pages: Int,
        val chapters: Int,
    )

    companion object {
        const val DEFAULT_WORKERS = 4

        /** The default download root: `~/.pixerion`. */
        fun defaultRoot(): Path = Path.of(System.getProperty("user.home"), ".pixerion")
    }
}
