package io.modernia.pixerion.interop

import io.modernia.pixerion.domain.Book
import io.modernia.pixerion.domain.BookRef
import io.modernia.pixerion.domain.Catalog
import io.modernia.pixerion.domain.CatalogException
import io.modernia.pixerion.download.DownloadProgress
import io.modernia.pixerion.download.Downloader
import kotlinx.coroutines.runBlocking
import java.nio.file.Path

/**
 * A blocking, Java-friendly facade over a coroutine-first [Catalog].
 *
 * `core` is coroutine-first by contract ([Catalog.find]/[Catalog.search] are
 * `suspend`, [Catalog.download] returns a `Flow`). Java callers cannot invoke
 * `suspend` functions or `runBlocking` directly, so this class is the single
 * **interop seam** that bridges that contract to plain blocking calls — and it is
 * Kotlin because only Kotlin can call into the `suspend` world. See ADR-0007.
 *
 * Every method blocks the calling thread until the underlying coroutine completes,
 * which suits a servlet (Spring MVC) front-end. The [Catalog] contract's
 * invariants are preserved verbatim: a genuinely missing book is a `null`/empty
 * result, while a source-level failure surfaces as [CatalogException].
 *
 * @param delegate the coroutine-first catalog to adapt.
 */
class BlockingCatalog(
    private val delegate: Catalog,
) {
    /** Blocking equivalent of [Catalog.find]. Returns `null` if no such book exists. */
    fun find(ref: BookRef): Book? = runBlocking { delegate.find(ref) }

    /** Blocking equivalent of [Catalog.search]. Returns an empty list if nothing matches. */
    fun search(query: Map<String, String>): List<Book> = runBlocking { delegate.search(query) }

    /**
     * Blocking download to disk, reusing the parallel [Downloader] unchanged.
     *
     * @param ref the book to download.
     * @param root base directory to write under; defaults to the downloader's
     *   default root (`~/.pixerion`).
     * @param progress a sink for live progress, so a Java caller (e.g. the server's
     *   SSE endpoint) can observe the download without touching coroutines; defaults
     *   to [DownloadProgress.NONE]. Its callbacks are invoked serially and must not
     *   block (they run on the download's coroutines).
     * @return a [Downloader.Summary] of what was written, or `null` if no such book exists.
     */
    @JvmOverloads
    fun download(
        ref: BookRef,
        root: Path? = null,
        progress: DownloadProgress = DownloadProgress.NONE,
    ): Downloader.Summary? =
        runBlocking {
            val downloader = if (root != null) Downloader(root) else Downloader()
            downloader.download(delegate, ref, progress)
        }
}
