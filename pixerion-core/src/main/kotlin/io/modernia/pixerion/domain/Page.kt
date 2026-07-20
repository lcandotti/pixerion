package io.modernia.pixerion.domain

/**
 * A single downloadable image within a [Book]'s content.
 *
 * A page is a lightweight descriptor: its image [bytes] are fetched lazily, so a
 * caller can stream a large download in reading order without holding every image
 * in memory and can control fetch concurrency itself. How the bytes are obtained
 * — a provider API, HTML scraping — is an adapter concern and never exposed here.
 *
 * @property chapter the chapter this page belongs to, as labeled by the source
 *   (e.g. `"1"`, `"12.5"`); may be blank if the source does not chapter its content.
 * @property number the 1-based position of this page within its [chapter].
 * @property filename a source-suggested file name including extension
 *   (e.g. `"x1-abc.png"`), suitable for naming the page on disk.
 */
interface Page {
    val chapter: String
    val number: Int
    val filename: String

    /**
     * Fetches this page's image content.
     *
     * @return the raw image bytes.
     * @throws CatalogException if the source cannot be reached or returns a
     *   non-success response.
     */
    suspend fun bytes(): ByteArray
}
