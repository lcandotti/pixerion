package command

import io.modernia.pixerion.domain.BookRef
import io.modernia.pixerion.domain.Catalog
import io.modernia.pixerion.domain.CatalogException
import io.modernia.pixerion.domain.SourceRef
import io.modernia.pixerion.mangadex.MangaDexCatalog
import output.Glyph
import output.Style
import kotlinx.coroutines.runBlocking
import picocli.CommandLine.Option
import java.util.concurrent.Callable

/**
 * Shared plumbing for subcommands that operate on a [Catalog]: source selection,
 * the `suspend`→blocking bridge, and uniform failure handling.
 *
 * Exit codes: `0` success, `1` a clean "no result" (e.g. `find` miss), `2` a
 * usage error (unknown source) or a failure — a [CatalogException] (source
 * unreachable) here, anything else via [ExecutionErrorHandler].
 */
abstract class CatalogCommand : Callable<Int> {

    @Option(
        names = ["-s", "--source"],
        defaultValue = "mangadex",
        description = [$$"Catalog source to query (default: ${DEFAULT-VALUE})."],
    )
    lateinit var source: String

    /**
     * Resolves a source name to a [Catalog]. Defaults to the real source registry
     * ([catalogFor]); tests substitute a fake so a command can be driven end-to-end
     * (parse → run → render) without touching the network.
     */
    internal var catalogs: (String) -> Catalog? = ::catalogFor

    final override fun call(): Int {
        val catalog = catalogs(source) ?: return 2
        return try {
            runBlocking { run(catalog) }
        } catch (e: CatalogException) {
            System.err.println()
            System.err.println("  ${Style.error(Glyph.CROSS)} ${Style.error("Error")} ${Style.muted("${Glyph.DOT} ${e.message}")}")
            System.err.println()
            2
        }
    }

    /** Runs the command's work against the resolved [catalog], returning an exit code. */
    protected abstract suspend fun run(catalog: Catalog): Int
}

/**
 * The source registry: maps a `--source` name to its [Catalog], printing a usage
 * error and returning `null` for an unknown source. Register new adapters here.
 */
internal fun catalogFor(source: String): Catalog? =
    when (source) {
        MangaDexCatalog.SCHEME -> MangaDexCatalog()
        else -> {
            System.err.println()
            System.err.println(
                "  ${Style.error(Glyph.CROSS)} ${Style.error("Unknown source")} ${Style.strong("“$source”")} " +
                    Style.muted("${Glyph.DOT} known: ${MangaDexCatalog.SCHEME}"),
            )
            System.err.println()
            null
        }
    }

/**
 * Parses a CLI reference string into a [BookRef]: `"scheme:id"` becomes a
 * [SourceRef] for that scheme; a bare token is scoped to [defaultSource].
 */
internal fun parseRef(raw: String, defaultSource: String): BookRef {
    val separator = raw.indexOf(':')
    return if (separator > 0) {
        SourceRef(raw.substring(0, separator), raw.substring(separator + 1))
    } else {
        SourceRef(defaultSource, raw)
    }
}
