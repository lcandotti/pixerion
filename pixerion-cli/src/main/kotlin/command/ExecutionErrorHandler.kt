package command

import output.Glyph
import output.Style
import picocli.CommandLine

/**
 * Last-resort failure handler for exceptions no command handles itself (I/O
 * failures during download or bundling, invalid paths, …). Prints a clean
 * one-line error instead of picocli's default stack trace, and returns `2` —
 * the "failure" exit code — so scripts can't mistake an operational failure
 * for exit `1`'s clean "no result".
 *
 * Installed on the root [CommandLine] in `main`, so it applies to every
 * subcommand. [CatalogException][io.modernia.pixerion.domain.CatalogException]
 * never reaches it: `CatalogCommand` maps that to `2` itself.
 */
class ExecutionErrorHandler : CommandLine.IExecutionExceptionHandler {
    override fun handleExecutionException(
        ex: Exception,
        commandLine: CommandLine,
        parseResult: CommandLine.ParseResult,
    ): Int {
        val detail = ex.message ?: ex.javaClass.simpleName
        System.err.println()
        System.err.println("  ${Style.error(Glyph.CROSS)} ${Style.error("Error")} ${Style.muted("${Glyph.DOT} $detail")}")
        System.err.println()
        return 2
    }
}
