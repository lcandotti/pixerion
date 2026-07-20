package command

import io.modernia.pixerion.domain.Catalog
import output.Glyph
import output.Style
import output.render
import picocli.CommandLine.Command
import picocli.CommandLine.Parameters

/** `pixerion find [--source <s>] <ref>` — fetch a single book by reference. */
@Command(
    name = "find",
    mixinStandardHelpOptions = true,
    description = ["Fetch a single book by reference."],
)
class FindCommand : CatalogCommand() {

    @Parameters(
        index = "0",
        arity = "1",
        paramLabel = "REF",
        description = [
            "Book reference: a bare source id (uses --source), or a 'scheme:id' pair.",
        ],
    )
    lateinit var ref: String

    override suspend fun run(catalog: Catalog): Int {
        val book = catalog.find(parseRef(ref, source))
        return if (book == null) {
            System.err.println()
            System.err.println("  ${Style.warn(Glyph.EMPTY)} ${Style.muted("No book found for")} ${Style.strong(ref)}")
            System.err.println()
            1
        } else {
            println()
            println(book.render())
            println()
            0
        }
    }
}
