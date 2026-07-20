package command

import io.modernia.pixerion.domain.Catalog
import output.Glyph
import output.Style
import output.render
import picocli.CommandLine.Command
import picocli.CommandLine.Parameters

/** `pixerion search [--source <s>] <query>` — discovery against a catalog. */
@Command(
    name = "search",
    mixinStandardHelpOptions = true,
    description = ["Search a catalog for books by title."],
)
class SearchCommand : CatalogCommand() {

    @Parameters(
        index = "0",
        arity = "1",
        paramLabel = "QUERY",
        description = ["Title text to search for."],
    )
    lateinit var query: String

    override suspend fun run(catalog: Catalog): Int {
        val books = catalog.search(mapOf("title" to query))
        if (books.isEmpty()) {
            println()
            println("  ${Style.warn(Glyph.EMPTY)} ${Style.muted("No matches for")} ${Style.strong("“$query”")}")
            println()
            // "No result" is exit 1, like a find miss — kept consistent across subcommands.
            return 1
        }
        val plural = if (books.size == 1) "result" else "results"
        println()
        println("  ${Style.accent(Glyph.PROMPT)} ${Style.strong(query)}  ${Style.muted("${Glyph.DOT} ${books.size} $plural")}")
        println()
        println(books.joinToString("\n\n") { it.render() })
        println()
        return 0
    }
}
