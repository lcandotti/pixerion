package command

import io.modernia.pixerion.domain.Catalog
import io.modernia.pixerion.download.Downloader
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import output.DownloadProgressView
import output.Glyph
import output.Style
import picocli.CommandLine.Command
import picocli.CommandLine.Option
import picocli.CommandLine.Parameters
import java.nio.file.Path

/** `pixerion download [--source <s>] [-o <dir>] [-w <n>] <ref>` — save a book to disk. */
@Command(
    name = "download",
    mixinStandardHelpOptions = true,
    description = ["Download a book's pages to disk."],
)
class DownloadCommand : CatalogCommand() {

    @Parameters(
        index = "0",
        arity = "1",
        paramLabel = "REF",
        description = [
            "Book reference: a bare source id (uses --source), or a 'scheme:id' pair.",
        ],
    )
    lateinit var ref: String

    @Option(
        names = ["-o", "--output"],
        paramLabel = "DIR",
        description = ["Directory to download into (default: ~/.pixerion)."],
    )
    var output: String? = null

    @Option(
        names = ["-w", "--workers"],
        defaultValue = "4",
        description = [$$"Number of parallel download workers (default: ${DEFAULT-VALUE})."],
    )
    var workers: Int = 4

    override suspend fun run(catalog: Catalog): Int {
        if (workers < 1) {
            System.err.println()
            System.err.println(
                "  ${Style.error(Glyph.CROSS)} ${Style.error("Invalid value")} ${Style.strong("--workers $workers")} " +
                    Style.muted("${Glyph.DOT} must be at least 1"),
            )
            System.err.println()
            return 2
        }
        val reference = parseRef(ref, source)
        val root = output?.let { Path.of(it) } ?: Downloader.defaultRoot()

        println()
        println("  ${Style.accent(Glyph.PROMPT)} ${Style.muted("downloading")} ${Style.strong(ref)} ${Style.muted("${Glyph.DOT} $workers workers")}")

        val view = DownloadProgressView()
        // The live frame hides the cursor; a Ctrl-C mid-download must not leave the
        // user's shell without one. finish() is idempotent, so the normal path below
        // and this hook can both run.
        val restoreCursor = Thread { view.finish() }
        Runtime.getRuntime().addShutdownHook(restoreCursor)
        val summary =
            coroutineScope {
                val painter = launch { view.repaintUntilDone() }
                try {
                    Downloader(root = root, workers = workers).download(catalog, reference, progress = view)
                } finally {
                    painter.cancelAndJoin() // stop repainting before wiping, so no frame redraws after finish()
                    view.finish()
                    // Throws once shutdown has begun — exactly when the hook should stay.
                    runCatching { Runtime.getRuntime().removeShutdownHook(restoreCursor) }
                }
            }
        return if (summary == null) {
            System.err.println("  ${Style.warn(Glyph.EMPTY)} ${Style.muted("No book found for")} ${Style.strong(ref)}")
            println()
            1
        } else {
            val stats = summary.stats
            val tally = "${stats.chapters} chapters ${Glyph.DOT} ${stats.pages} pages"
            println("  ${Style.ok(Glyph.CHECK)} ${Style.strong(summary.book.title)} ${Style.muted("${Glyph.DOT} $tally → ${summary.directory}")}")
            println()
            if (stats.pages > 0) 0 else 1
        }
    }
}
