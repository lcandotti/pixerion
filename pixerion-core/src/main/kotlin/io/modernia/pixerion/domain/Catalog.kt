package io.modernia.pixerion.domain

import kotlinx.coroutines.flow.Flow

/**
 * A provider-agnostic catalog of books.
 *
 * Implementations adapt a concrete source (a REST API, a database, an in-memory
 * fake for tests) to this contract. Nothing here exposes transport, serialization,
 * or vendor details — callers depend only on domain types ([Book], [BookRef]) and
 * cannot tell where the data originates.
 *
 * Implementations resolve the references they understand: a leaf adapter bound to
 * a single source handles the [SourceRef] schemes it owns, while an aggregating
 * catalog additionally resolves a [BookId] by mapping it to the right source.
 *
 * Implementations are expected to translate source-level failures (timeouts,
 * non-success responses) into domain failures rather than leaking them. A book
 * that genuinely does not exist is distinct from a catalog that could not be
 * reached: the former is a successful `null`/empty result, the latter is an error.
 */
interface Catalog {
    /**
     * Retrieves a single book by a stable reference.
     *
     * The [ref] is typically one obtained from a [Book] returned by [search] —
     * either its portable [Book.id] or its source-scoped [Book.ref] — which
     * makes search results re-fetchable.
     *
     * @param ref the handle identifying the book: a portable [BookId] or a
     *   source-scoped [SourceRef].
     * @return the matching [Book], or `null` if no book exists for [ref]
     *   (including a [ref] this implementation cannot resolve).
     */
    suspend fun find(ref: BookRef): Book?

    /**
     * Searches the catalog.
     *
     * The primary entry point for discovery. Each returned [Book] carries both a
     * portable [Book.id] and its source-scoped [Book.ref], either of which can
     * be passed back to [find].
     *
     * @param query the search criteria, expressed in domain terms.
     * @return matching books, or an empty list if none match. An empty list means
     *   "no matches", not "lookup failed".
     */
    suspend fun search(query: Map<String, String>): List<Book>

    /**
     * Streams the downloadable structure of a book as a flow of [DownloadEvent]s.
     *
     * The returned [Flow] is **cold**: collecting it drives the download. It emits
     * a single [Manifest][DownloadEvent.Manifest] first, then for each chapter a
     * [ChapterStarted][DownloadEvent.ChapterStarted] followed by one
     * [PageReady][DownloadEvent.PageReady] per page — carrying the chapter and
     * page counts a determinate progress display needs. A large book streams
     * rather than materializing all at once, and each page's image
     * [bytes][Page.bytes] are still fetched lazily, on demand. How the structure
     * is sourced — a provider API, HTML scraping — is an implementation detail
     * invisible to callers.
     *
     * @param ref the handle identifying the book to download: a portable
     *   [BookId] or a source-scoped [SourceRef].
     * @return a cold flow of the book's download events, **empty** (not even a
     *   [Manifest][DownloadEvent.Manifest]) if [ref] resolves to nothing this
     *   catalog can download (including a [ref] it cannot resolve). An empty flow
     *   means "nothing to download", not "download failed"; source-level failures
     *   surface as [CatalogException] during collection.
     */
    fun download(ref: BookRef): Flow<DownloadEvent>
}
