package output

import io.modernia.pixerion.download.DownloadProgress
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlin.time.Duration.Companion.milliseconds

/**
 * A [DownloadProgress] sink that renders live progress bars for a `download`:
 * a determinate total-chapters bar plus one page bar per chapter currently being
 * fetched by a worker. The `core` [io.modernia.pixerion.download.Downloader]
 * drives the callbacks (serially); this view only renders — orchestration stays in
 * `core`.
 *
 * On an interactive terminal it repaints an in-place, multi-line frame (driven by
 * [repaintUntilDone]); when output is piped/redirected or `NO_COLOR` is set (so
 * [Cursor] is disabled), it degrades to a single quiet line per completed chapter,
 * emitting no cursor-control escapes. All state is guarded by [lock] because the
 * repaint loop reads it on a different coroutine than the ones firing callbacks.
 */
internal class DownloadProgressView : DownloadProgress {
    private val lock = Any()
    private var started = false
    private var totalChapters = 0
    private var chaptersDone = 0

    /** Chapters resolved but not yet complete, in arrival order — the live page bars. */
    private val active = LinkedHashMap<String, ChapterState>()

    /** Lines the previous frame drew, so the next frame knows how far to rewind. */
    private var linesDrawn = 0

    private class ChapterState(
        val label: String,
        val total: Int,
    ) {
        var written = 0
    }

    override fun onStart(totalChapters: Int) {
        synchronized(lock) {
            started = true
            this.totalChapters = totalChapters
        }
    }

    override fun onChapterStart(
        chapterId: String,
        label: String,
        totalPages: Int,
    ) {
        synchronized(lock) { active[chapterId] = ChapterState(label, totalPages) }
    }

    override fun onPageWritten(
        chapterId: String,
        written: Int,
        totalPages: Int,
    ) {
        synchronized(lock) { active[chapterId]?.written = written }
    }

    override fun onChapterComplete(chapterId: String) {
        synchronized(lock) {
            val done = active.remove(chapterId)
            chaptersDone++
            // No live region to animate: report the completion as a plain line instead.
            if (!Cursor.enabled && done != null) {
                println("  ${Style.ok(Glyph.CHECK)} ${Style.muted("chapter")} ${Style.strong(done.label.ifBlank { "?" })} ${Style.muted("${Glyph.DOT} ${done.total} pages")}")
            }
        }
    }

    /**
     * Repaints the live frame until the enclosing coroutine is cancelled. A no-op
     * off a terminal (the non-TTY path reports per-chapter completions inline). The
     * caller cancels this once the download returns, then calls [finish].
     */
    suspend fun repaintUntilDone() {
        if (!Cursor.enabled) return
        Cursor.hide()
        while (currentCoroutineContext().isActive) {
            render()
            delay(REPAINT_INTERVAL)
        }
    }

    /** Wipes the live region and restores the cursor, leaving the caller to print the summary. */
    fun finish() {
        if (!Cursor.enabled) return
        synchronized(lock) {
            if (linesDrawn > 0) {
                print(Cursor.up(linesDrawn) + Cursor.clearBelow())
                linesDrawn = 0
            }
            Cursor.show()
            System.out.flush()
        }
    }

    private fun render() {
        synchronized(lock) {
            if (!started) return
            val lines = buildList {
                add(barLine("Chapters", chaptersDone, totalChapters))
                // Page labels carry a 2-space sub-indent so they nest under the chapters bar; the
                // fixed-width label cell in barLine then still lines every bar up on the same column.
                // The frame height is capped: chapter-started events can run well ahead of page
                // writes, and a frame taller than the terminal breaks the rewind arithmetic
                // (Cursor.up clamps at the top row), smearing duplicate frames down the screen.
                val bars = active.values.take(MAX_PAGE_BARS)
                bars.forEach { add(barLine("  ch ${it.label.ifBlank { "?" }}", it.written, it.total)) }
                val overflow = active.size - bars.size
                if (overflow > 0) add("  ${Style.muted("  … +$overflow more")}")
            }
            val frame =
                buildString {
                    append(Cursor.up(linesDrawn))
                    append(Cursor.clearBelow())
                    lines.forEach { append(it).append('\n') }
                }
            linesDrawn = lines.size
            print(frame)
            System.out.flush()
        }
    }

    private fun barLine(
        label: String,
        done: Int,
        total: Int,
    ): String {
        val fraction = if (total > 0) done.toDouble() / total else 1.0
        // Pad the *raw* label to a fixed visible width before styling (ANSI codes would throw
        // padEnd's count off), so every bar starts — and, sharing one width, ends — on the same
        // column. `take` guards the alignment against an unexpectedly long label.
        val cell = label.take(LABEL_WIDTH).padEnd(LABEL_WIDTH)
        return "  ${Style.muted(cell)} ${Style.bar(fraction, BAR_WIDTH)} ${Style.strong("$done/$total")}"
    }
}

// File-private constants (not a companion object): a companion holding only these would
// compile to a `Companion` class whose sole member is REPAINT_INTERVAL's getter, which
// same-file access never calls — leaving that class perpetually at 0% coverage.

/** Repaint cadence for the live frame (a `Duration`, so `delay` uses its modern overload). */
private val REPAINT_INTERVAL = 100.milliseconds

/** Fixed visible width of the label cell, so all bars align to one start (and end) column. */
private const val LABEL_WIDTH = 12

/** Most page bars shown at once; further active chapters collapse into one "+N more" line. */
private const val MAX_PAGE_BARS = 8

/** Single bar width shared by the chapters bar and every page bar. */
private const val BAR_WIDTH = 24
