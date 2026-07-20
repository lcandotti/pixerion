package io.modernia.pixerion.download

/**
 * A sink for a download's live progress, so a delivery module (the CLI, a future
 * web app) can render a progress display without owning any of the download
 * orchestration. [Downloader] drives it as pages are written; the default
 * [NONE] instance discards everything, so progress reporting is opt-in.
 *
 * All methods are called **serially** — [Downloader] guards them so that although
 * pages are written concurrently, an implementation never observes overlapping
 * callbacks and needs no locking of its own. Callbacks should be cheap and must
 * not block, since they run on the download's coroutines.
 */
interface DownloadProgress {
    /**
     * The download's shape is known.
     *
     * @param totalChapters how many chapters will be downloaded.
     */
    fun onStart(totalChapters: Int) {}

    /**
     * A chapter has been resolved and its pages are about to be written.
     *
     * @param chapterId a stable identifier for the chapter (distinct even when two
     *   chapters share a [label]).
     * @param label the source's human label for the chapter.
     * @param totalPages how many pages the chapter holds.
     */
    fun onChapterStart(
        chapterId: String,
        label: String,
        totalPages: Int,
    ) {}

    /**
     * A page of a chapter has been written to disk.
     *
     * @param chapterId the chapter the page belongs to.
     * @param written how many of the chapter's pages are now written.
     * @param totalPages the chapter's total page count.
     */
    fun onPageWritten(
        chapterId: String,
        written: Int,
        totalPages: Int,
    ) {}

    /**
     * Every page of a chapter has been written.
     *
     * @param chapterId the chapter that is now complete.
     */
    fun onChapterComplete(chapterId: String) {}

    companion object {
        /** A no-op sink; the default when a caller wants no progress reporting. */
        val NONE: DownloadProgress = object : DownloadProgress {}
    }
}
