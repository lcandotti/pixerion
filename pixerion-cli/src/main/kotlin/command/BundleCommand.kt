package command

import io.modernia.pixerion.bundle.Bundler
import output.Glyph
import output.Style
import picocli.CommandLine.Command
import picocli.CommandLine.Option
import picocli.CommandLine.Parameters
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.Callable

/**
 * `pixerion bundle [-o <dir>] [--overwrite] <book-dir>` — package a downloaded
 * book's chapters into `.cbz` archives for readers like Panel.
 *
 * Purely local: it reads the on-disk `chapters/ch-*` layout and writes archives,
 * with no catalog or network, so it does not extend [CatalogCommand].
 */
@Command(
    name = "bundle",
    mixinStandardHelpOptions = true,
    description = ["Bundle a downloaded book's chapters into .cbz archives."],
)
class BundleCommand : Callable<Int> {

    @Parameters(
        index = "0",
        arity = "1",
        paramLabel = "DIR",
        description = ["Path to a downloaded book directory (one that contains a chapters/ folder)."],
    )
    lateinit var dir: String

    @Option(
        names = ["-o", "--output"],
        paramLabel = "DIR",
        description = ["Directory to write .cbz files into (default: a 'cbz' folder inside the book)."],
    )
    var output: String? = null

    @Option(
        names = ["--overwrite"],
        description = ["Rewrite .cbz files that already exist (default: skip them)."],
    )
    var overwrite: Boolean = false

    override fun call(): Int {
        val bookDir = Path.of(dir)
        if (!Files.isDirectory(bookDir)) {
            System.err.println()
            System.err.println("  ${Style.error(Glyph.CROSS)} ${Style.muted("Not a directory")} ${Style.strong(dir)}")
            System.err.println()
            return 2
        }
        val out = output?.let { Path.of(it) } ?: bookDir.resolve("cbz")

        println()
        println("  ${Style.accent(Glyph.PROMPT)} ${Style.muted("bundling")} ${Style.strong(bookDir.fileName.toString())}")

        val summary =
            Bundler(overwrite = overwrite).bundle(bookDir, out) { archive ->
                println("  ${Style.ok(Glyph.CHECK)} ${Style.muted(archive.fileName.toString())}")
            }

        val skipped = if (summary.skipped > 0) " ${Glyph.DOT} ${summary.skipped} skipped" else ""
        println(
            "  ${Style.ok(Glyph.CHECK)} ${Style.strong("${summary.bundled} archives")} " +
                Style.muted("$skipped → ${summary.output}"),
        )
        println()
        return if (summary.bundled > 0 || summary.skipped > 0) 0 else 1
    }
}
