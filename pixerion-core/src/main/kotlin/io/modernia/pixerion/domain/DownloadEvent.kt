package io.modernia.pixerion.domain

/**
 * An event in the structured stream a [Catalog.download] produces.
 *
 * The stream carries the book's *structure* — how many chapters it spans and how
 * many pages each chapter holds — alongside the pages themselves, so a caller can
 * report determinate progress (a total-chapters bar, per-chapter page bars)
 * without materializing the whole book first. The structure a source already
 * knows up front (chapter and page counts) would otherwise be flattened away by a
 * bare page stream.
 *
 * Ordering within the cold flow:
 * - exactly one [Manifest] is emitted first, before any chapter;
 * - each chapter emits its [ChapterStarted] before any of its [PageReady] events;
 * - across chapters, events may interleave (a source is free to resolve chapters
 *   concurrently), so consumers key per-chapter state by [ChapterStarted.chapterId].
 *
 * Page image [bytes][Page.bytes] remain lazy: a [PageReady] describes a page but
 * does not fetch its content.
 */
sealed interface DownloadEvent {
    /**
     * The first event: how many chapters the download spans.
     *
     * @property chapters the total number of chapters that will be downloaded.
     */
    data class Manifest(
        val chapters: Int,
    ) : DownloadEvent

    /**
     * A chapter has been resolved and its pages are about to stream.
     *
     * @property chapterId a stable, source-scoped identifier for the chapter, used
     *   to attribute later [PageReady] events even when two chapters share a
     *   display [label].
     * @property label the source's human label for the chapter (e.g. `"1"`,
     *   `"12.5"`); may be blank if the source does not chapter its content.
     * @property pages the number of pages this chapter holds.
     */
    data class ChapterStarted(
        val chapterId: String,
        val label: String,
        val pages: Int,
    ) : DownloadEvent

    /**
     * One downloadable page, tagged with its owning chapter.
     *
     * @property chapterId the [ChapterStarted.chapterId] of the chapter this page
     *   belongs to.
     * @property page the page descriptor; its image [bytes][Page.bytes] are still
     *   fetched lazily.
     */
    data class PageReady(
        val chapterId: String,
        val page: Page,
    ) : DownloadEvent
}
