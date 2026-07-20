package io.modernia.pixerion.mangadex

import io.modernia.pixerion.domain.Book
import io.modernia.pixerion.domain.BookId
import io.modernia.pixerion.domain.SourceRef
import kotlinx.serialization.Serializable

/** Envelope returned by `GET /manga` (a paginated list of manga). */
@Serializable
internal data class MangaListResponse(
    val result: String = "",
    val data: List<MangaData> = emptyList(),
    val limit: Int = 0,
    val offset: Int = 0,
    val total: Int = 0,
)

/** Envelope returned by `GET /manga/{id}` (a single manga). */
@Serializable
internal data class MangaResponse(
    val result: String = "",
    val data: MangaData,
)

/** A single manga entity. */
@Serializable
internal data class MangaData(
    val id: String,
    val type: String = "manga",
    val attributes: MangaAttributes = MangaAttributes(),
)

/**
 * Maps a MangaDex manga onto the domain [Book], minting the portable [BookId]
 * from the MangaDex UUID and tagging the source-scoped [SourceRef] with the
 * adapter's [scheme][MangaDexCatalog.SCHEME].
 */
internal fun MangaData.toBook(): Book =
    Book(
        id = BookId(id),
        ref = SourceRef(MangaDexCatalog.SCHEME, id),
        title = attributes.title.localized(),
        synopsis = attributes.description.localized(),
    )

/**
 * The attributes of a manga. [title] and [description] are localized maps keyed
 * by IETF language tag (e.g. `"en"`, `"ja"`); see [localized] for how a single
 * display string is chosen.
 */
@Serializable
internal data class MangaAttributes(
    val title: Map<String, String> = emptyMap(),
    val description: Map<String, String> = emptyMap(),
)

/**
 * Collapses a localized MangaDex map into a single display string, preferring
 * English, then any available language, then the empty string.
 */
internal fun Map<String, String>.localized(): String = this["en"] ?: values.firstOrNull() ?: ""

/** Envelope returned by `GET /manga/{id}/feed` (a paginated list of chapters). */
@Serializable
internal data class ChapterListResponse(
    val result: String = "",
    val data: List<ChapterData> = emptyList(),
    val limit: Int = 0,
    val offset: Int = 0,
    val total: Int = 0,
)

/** A single chapter entity. */
@Serializable
internal data class ChapterData(
    val id: String,
    val attributes: ChapterAttributes = ChapterAttributes(),
)

/**
 * The attributes of a chapter. [chapter] is the source's chapter label (e.g.
 * `"1"`, `"12.5"`) and may be `null` for one-shots; [pages] is the page count.
 */
@Serializable
internal data class ChapterAttributes(
    val chapter: String? = null,
    val volume: String? = null,
    val translatedLanguage: String? = null,
    val pages: Int = 0,
)

/**
 * Envelope returned by `GET /at-home/server/{chapterId}`, locating the image
 * server and the page file names for a chapter.
 */
@Serializable
internal data class AtHomeResponse(
    val result: String = "",
    val baseUrl: String,
    val chapter: AtHomeChapter = AtHomeChapter(),
)

/**
 * The image-delivery details for a chapter. A page URL is built as
 * `{baseUrl}/data/{hash}/{filename}`, where filename comes from [data]
 * (original quality).
 */
@Serializable
internal data class AtHomeChapter(
    val hash: String = "",
    val data: List<String> = emptyList(),
    val dataSaver: List<String> = emptyList(),
)

/**
 * Telemetry POSTed to `POST /at-home/report` after fetching an image from a
 * MangaDex@Home node. The network uses these reports to monitor node health, and
 * the MangaDex@Home spec requires them for images served from `*.mangadex.network`
 * (never for the canonical `uploads.mangadex.org` server). Field names match the
 * wire format exactly. [duration] is the full retrieval time in milliseconds and
 * [cached] reflects whether the node served the image from its cache (`X-Cache: HIT`).
 */
@Serializable
internal data class AtHomeReport(
    val url: String,
    val success: Boolean,
    val cached: Boolean,
    val bytes: Int,
    val duration: Long,
)
