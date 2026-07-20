package output

import io.modernia.pixerion.domain.Book
import io.modernia.pixerion.domain.BookId
import io.modernia.pixerion.domain.SourceRef

/** Single-line synopsis budget before it gets clipped with an ellipsis. */
private const val SYNOPSIS_WIDTH = 96

/**
 * Renders a book as a "card": a colored accent spine down the left edge, a bold
 * title with a source badge, the dimmed identifier, and an italic synopsis.
 */
internal fun Book.render(): String = buildString {
    val bar = Style.spine
    appendLine("  $bar ${Style.title(title.ifBlank { "Untitled" })}  ${Style.badge(sourceLabel())}")
    appendLine("  $bar ${Style.muted(id.value)}")
    val synopsisLine = synopsis.lineSequence().firstOrNull()?.trim().orEmpty()
    if (synopsisLine.isNotEmpty()) {
        val clipped = if (synopsisLine.length > SYNOPSIS_WIDTH) {
            synopsisLine.take(SYNOPSIS_WIDTH - 1).trimEnd() + "…"
        } else {
            synopsisLine
        }
        appendLine("  $bar ${Style.synopsis(clipped)}")
    }
}.trimEnd()

/** The human-facing name of the source a book came from. */
private fun Book.sourceLabel(): String = when (val r = ref) {
    is SourceRef -> r.scheme
    is BookId -> "catalog"
}
