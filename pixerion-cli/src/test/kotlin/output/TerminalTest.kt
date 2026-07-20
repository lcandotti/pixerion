package output

import command.captureOutput
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Rendering tests for the progress-display primitives. They run under Gradle where
 * stdout is not a terminal, so [Style]/[Cursor] are disabled — `bar()` renders as
 * plain characters and the view takes its non-TTY (line-per-completion) path, both
 * of which are deterministic to assert.
 */
class TerminalTest {
    @Test
    fun `bar fills in proportion to the fraction`() {
        assertEquals("▐░░░░▌", Style.bar(0.0, 4))
        assertEquals("▐██░░▌", Style.bar(0.5, 4))
        assertEquals("▐████▌", Style.bar(1.0, 4))
    }

    @Test
    fun `bar clamps fractions outside 0 to 1`() {
        assertEquals("▐████▌", Style.bar(2.0, 4))
        assertEquals("▐░░░░▌", Style.bar(-1.0, 4))
    }

    @Test
    fun `off a terminal the view reports each completed chapter as a plain line`() {
        val view = DownloadProgressView()

        val run =
            captureOutput {
                view.onStart(1)
                view.onChapterStart("c1", "12", 2)
                view.onPageWritten("c1", 1, 2)
                view.onPageWritten("c1", 2, 2)
                view.onChapterComplete("c1")
                view.finish()
                0
            }

        assertTrue(run.out.contains("chapter"))
        assertTrue(run.out.contains("12"))
        assertTrue(run.out.contains("2 pages"))
    }

    @Test
    fun `cursor escapes are emitted only on a terminal`() {
        withCursor(enabled = false) {
            assertEquals("", Cursor.up(3))
            assertEquals("", Cursor.clearBelow())
            assertEquals("", captureOutput { hideThenShow() }.out)
        }
        withCursor(enabled = true) {
            assertTrue(Cursor.up(3).endsWith("3A"))
            assertEquals("", Cursor.up(0)) // n <= 0 is a no-op even on a terminal
            assertTrue(Cursor.clearBelow().endsWith("0J"))
            val loud = captureOutput { hideThenShow() }
            assertTrue(loud.out.contains("?25l")) // hide
            assertTrue(loud.out.contains("?25h")) // show
        }
    }

    @Test
    fun `on a terminal the view repaints bars and clears the region on finish`() {
        withCursor(enabled = true) {
            val view = DownloadProgressView()
            val run =
                captureOutput {
                    view.onStart(2)
                    view.onChapterStart("c1", "12", 3)
                    view.onPageWritten("c1", 1, 3)
                    // One repaint pass, then cancel — the first render() runs before the delay.
                    runBlocking { withTimeoutOrNull(50) { view.repaintUntilDone() } }
                    view.finish()
                    0
                }

            assertTrue(run.out.contains("Chapters")) // the total-chapters bar label
            assertTrue(run.out.contains("ch 12")) // the live page bar for the active chapter
            assertTrue(run.out.contains(Glyph.BAR_LEFT)) // an actual bar was drawn
        }
    }

    /** Exercises both cursor toggles under [captureOutput], returning a 0 exit code. */
    private fun hideThenShow(): Int {
        Cursor.hide()
        Cursor.show()
        return 0
    }

    /** Forces [Cursor]'s TTY gate for the duration of [block], restoring it afterward. */
    private fun withCursor(
        enabled: Boolean,
        block: () -> Unit,
    ) {
        val original = Cursor.enabled
        Cursor.enabled = enabled
        try {
            block()
        } finally {
            Cursor.enabled = original
        }
    }
}
