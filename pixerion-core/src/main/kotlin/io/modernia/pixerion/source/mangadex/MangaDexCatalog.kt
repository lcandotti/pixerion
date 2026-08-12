package io.modernia.pixerion.source.mangadex

import io.modernia.pixerion.domain.Book
import io.modernia.pixerion.domain.BookId
import io.modernia.pixerion.domain.BookRef
import io.modernia.pixerion.domain.Catalog
import io.modernia.pixerion.domain.CatalogException
import io.modernia.pixerion.domain.DownloadEvent
import io.modernia.pixerion.domain.Page
import io.modernia.pixerion.domain.SourceRef
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.channelFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import okhttp3.HttpUrl
import okhttp3.OkHttpClient

/**
 * A [Catalog] backed by the MangaDex API (<https://mangadex.org>).
 *
 * This is a *leaf* adapter bound to a single source: it owns the
 * [`"mangadex"`][SCHEME] [SourceRef] scheme and resolves nothing else. Having no
 * cross-source matching layer of its own, it mints each book's portable
 * [BookId] directly from the MangaDex UUID, so a [BookId] it issued round-trips
 * back through [find]; references it does not understand resolve to `null`.
 *
 * All transport and serialization concerns live in [MangaDexClient]; this class
 * only translates between the source's DTOs and domain types. Source-level
 * failures surface as [CatalogException] (raised by the client), while a
 * genuinely missing book is a plain `null`/empty result.
 *
 * @param client the transport to use; the default targets the public MangaDex API.
 */
class MangaDexCatalog internal constructor(
    private val client: MangaDexClient,
) : Catalog {
    /** Creates a catalog targeting the public MangaDex API. */
    constructor() : this(MangaDexClient())

    /**
     * Creates a catalog targeting [baseUrl] with the given [httpClient].
     *
     * Useful for pointing the adapter at a proxy or mirror, or supplying an
     * OkHttp client configured with custom timeouts or interceptors. The client
     * is augmented with the adapter's MangaDex transport policy (rate limiting,
     * 429 retry) — talking to the API unpaced is never correct, whatever the
     * endpoint.
     */
    constructor(baseUrl: HttpUrl, httpClient: OkHttpClient = OkHttpClient()) :
        this(MangaDexClient(baseUrl = baseUrl, httpClient = MangaDexClient.withDefaultPolicy(httpClient)))

    override suspend fun find(ref: BookRef): Book? {
        val id = ref.toMangaId() ?: return null
        return client.getManga(id)?.toBook()
    }

    override suspend fun search(query: Map<String, String>): List<Book> = client.searchManga(query).map { it.toBook() }

    /**
     * Streams the structure and pages of every [language][LANGUAGE] chapter of the
     * manga as [DownloadEvent]s.
     *
     * The feed carries one entry per *upload*, so a chapter released by several
     * scanlation groups appears once per group under the same chapter label.
     * Duplicates are collapsed to the first upload per label before anything is
     * emitted: the on-disk layout keys chapter directories by label, so a second
     * version of "chapter 1" would race the first, overwriting the same files.
     *
     * After fetching the (serially paginated) chapter feed, it emits a single
     * [Manifest][DownloadEvent.Manifest] with the chapter count, then fans out
     * across chapters — up to [DOWNLOAD_WORKER] at a time — resolving each
     * chapter's at-home server in parallel. Once a chapter resolves it emits a
     * [ChapterStarted][DownloadEvent.ChapterStarted] (carrying that chapter's page
     * count) followed by one [PageReady][DownloadEvent.PageReady] per image. Image
     * bytes are still fetched lazily when [Page.bytes] is called, so the caller
     * controls download/write concurrency on its side.
     */
    override fun download(ref: BookRef): Flow<DownloadEvent> =
        channelFlow {
            val mangaId = ref.toMangaId() ?: return@channelFlow
            // A missing manga (null feed) is an *empty* flow per the contract — not a
            // Manifest(0), which would read as "exists but has no chapters".
            val feed = client.chapters(mangaId, LANGUAGE) ?: return@channelFlow
            // One version per chapter label (the feed sort is stable, so this keeps the
            // first upload the feed returned). A null label (one-shots) is a label too:
            // the layout cannot separate two unnumbered chapters either.
            val chapters = feed.distinctBy { it.attributes.chapter }
            send(DownloadEvent.Manifest(chapters.size))
            val gate = Semaphore(DOWNLOAD_WORKER)
            for ((id, attributes) in chapters) {
                launch {
                    gate.withPermit {
                        val server = client.atHomeServer(id)
                        val label = attributes.chapter.orEmpty()
                        val filenames = server.chapter.data
                        send(DownloadEvent.ChapterStarted(id, label, filenames.size))
                        filenames.forEachIndexed { index, filename ->
                            val url = "${server.baseUrl}/data/${server.chapter.hash}/$filename"
                            send(DownloadEvent.PageReady(id, MangaDexPage(label, index + 1, filename, url, client)))
                        }
                    }
                }
            }
        }

    companion object {
        /** The [SourceRef] scheme this adapter owns. */
        const val SCHEME: String = "mangadex"

        /** Translated language whose chapters are downloaded (English for now). */
        private const val LANGUAGE = "en"

        /** The number of worker available for download */
        private const val DOWNLOAD_WORKER = 4
    }
}

/** A [Page] that fetches its image bytes from an at-home URL on demand. */
private class MangaDexPage(
    override val chapter: String,
    override val number: Int,
    override val filename: String,
    private val url: String,
    private val client: MangaDexClient,
) : Page {
    override suspend fun bytes(): ByteArray = client.imageBytes(url)
}

/**
 * Extracts the MangaDex UUID a [BookRef] points at, or `null` if this adapter
 * cannot resolve it. A [SourceRef] must carry the
 * [`"mangadex"`][MangaDexCatalog.SCHEME] scheme; a [BookId] is taken as a UUID
 * this adapter previously minted. Either way the value must be UUID-shaped:
 * MangaDex ids are UUIDs, so anything else (a foreign adapter's [BookId], a
 * typo) can never name a book here — that's an unresolvable ref (`null`), not
 * a request worth issuing (the API answers 400, which would surface as a
 * spurious [CatalogException]).
 */
private fun BookRef.toMangaId(): String? =
    when (this) {
        is SourceRef -> value.takeIf { scheme == MangaDexCatalog.SCHEME && it.matches(MANGA_UUID) }
        is BookId -> value.takeIf { it.matches(MANGA_UUID) }
    }

/** The shape of every MangaDex entity id. */
private val MANGA_UUID = Regex("""[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}""")
