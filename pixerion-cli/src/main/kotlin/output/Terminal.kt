package output

import picocli.CommandLine
import kotlin.math.roundToInt

/** Unicode glyphs (not emoji) used as accents and status markers. */
internal object Glyph {
    const val SPINE = "▌"   // card accent bar
    const val PROMPT = "❯"  // search header marker
    const val DOT = "·"     // inline separator
    const val EMPTY = "○"   // no result
    const val CHECK = "✓"   // success
    const val CROSS = "✗"   // error
    const val BAR_LEFT = "▐"  // progress-bar left cap
    const val BAR_RIGHT = "▌" // progress-bar right cap
    const val BAR_FILL = "█"  // progress-bar filled cell
    const val BAR_EMPTY = "░" // progress-bar empty cell
}

/**
 * ANSI text styling using a restrained xterm-256 palette, gated on picocli's
 * terminal detection so styling is emitted only when appropriate (a TTY,
 * `CLICOLOR_FORCE`, …) and suppressed when output is piped/redirected or
 * `NO_COLOR` is set. When disabled, structural glyphs still render in plain text.
 */
internal object Style {
    private val enabled: Boolean = CommandLine.Help.Ansi.AUTO.enabled()
    private const val ESC = ""
    private const val RESET = "$ESC[0m"

    // Palette (xterm-256). Muted, cohesive tones.
    private const val ACCENT = 141   // soft violet
    private const val TITLE = 231    // near-white
    private const val GREY = 245     // secondary text
    private const val BADGE_BG = 238 // dark grey badge background
    private const val GREEN = 114    // success
    private const val AMBER = 179    // warnings
    private const val RED = 203      // errors

    private fun sgr(text: String, vararg codes: String): String =
        if (enabled && text.isNotEmpty()) "$ESC[${codes.joinToString(";")}m$text$RESET" else text

    /** The card's left accent bar; falls back to a plain pipe without color. */
    val spine: String get() = if (enabled) "$ESC[38;5;${ACCENT}m${Glyph.SPINE}$RESET" else "│"

    fun strong(text: String) = sgr(text, "1")
    fun accent(text: String) = sgr(text, "38;5;$ACCENT")
    fun title(text: String) = sgr(text, "1", "38;5;$TITLE")
    fun muted(text: String) = sgr(text, "38;5;$GREY")
    fun synopsis(text: String) = sgr(text, "3", "38;5;$GREY") // italic grey
    fun ok(text: String) = sgr(text, "38;5;$GREEN")
    fun warn(text: String) = sgr(text, "38;5;$AMBER")
    fun error(text: String) = sgr(text, "1", "38;5;$RED")

    /** A pill-style badge: padded text on a colored background. */
    fun badge(text: String): String =
        if (enabled) "$ESC[48;5;$BADGE_BG;38;5;${TITLE}m $text $RESET" else "[$text]"

    /**
     * A fixed-[width] progress bar for [fraction] of 1.0 — a green filled run over a
     * muted track, bracketed by caps. Colors follow the same TTY gating as the rest
     * of [Style], so a piped/`NO_COLOR` bar is plain characters.
     */
    fun bar(
        fraction: Double,
        width: Int,
    ): String {
        val filled = (fraction.coerceIn(0.0, 1.0) * width).roundToInt()
        val fill = ok(Glyph.BAR_FILL.repeat(filled))
        val track = muted(Glyph.BAR_EMPTY.repeat(width - filled))
        return "${Glyph.BAR_LEFT}$fill$track${Glyph.BAR_RIGHT}"
    }
}

/**
 * ANSI cursor and line-erase controls for the in-place, multi-line live displays
 * (the download progress bars). Gated on the same terminal detection as [Style], so
 * every operation is a no-op (empty string) when output is piped/redirected or
 * `NO_COLOR` is set — callers fall back to plain line-by-line output there.
 */
internal object Cursor {
    /**
     * Whether the output stream is an interactive terminal that understands these codes.
     * A `var` (not a `val`) solely so tests can force the interactive path; production never
     * reassigns it — it stays at picocli's terminal detection.
     */
    var enabled: Boolean = CommandLine.Help.Ansi.AUTO.enabled()
    private const val ESC = ""

    /** Moves the cursor up [n] lines (to the start of the region to redraw). */
    fun up(n: Int): String = if (enabled && n > 0) "$ESC[${n}A" else ""

    /** Erases from the cursor to the end of the screen — wipes a stale region before redrawing. */
    fun clearBelow(): String = if (enabled) "$ESC[0J" else ""

    /** Hides the cursor during animation to avoid it flickering across the redrawn bars. */
    fun hide() {
        if (enabled) print("$ESC[?25l")
    }

    /** Restores the cursor once animation ends. */
    fun show() {
        if (enabled) print("$ESC[?25h")
    }
}
