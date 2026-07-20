package command

import io.modernia.pixerion.domain.Catalog
import io.modernia.pixerion.domain.CatalogException
import picocli.CommandLine
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class SearchCommandTest {
    private fun run(
        catalog: Catalog,
        vararg args: String,
    ): CapturedRun =
        captureOutput {
            CommandLine(SearchCommand().apply { catalogs = { catalog } }).execute(*args)
        }

    @Test
    fun `prints each match and a pluralized count`() {
        val catalog = FakeCatalog(searchResult = listOf(sampleBook(), sampleBook(id = "id-2", title = "Vinland Saga")))

        val result = run(catalog, "saga")

        assertEquals(0, result.code)
        assertTrue(result.out.contains("Berserk"))
        assertTrue(result.out.contains("Vinland Saga"))
        assertTrue(result.out.contains("2 results"))
    }

    @Test
    fun `a single match is singular`() {
        val result = run(FakeCatalog(searchResult = listOf(sampleBook())), "berserk")

        assertTrue(result.out.contains("1 result"))
        assertFalse(result.out.contains("1 results"))
    }

    @Test
    fun `no matches prints an empty-state notice and exits 1`() {
        val result = run(FakeCatalog(searchResult = emptyList()), "ghost")

        // "No result" is exit 1, consistent with a find/download miss.
        assertEquals(1, result.code)
        assertTrue(result.out.contains("No matches"))
    }

    @Test
    fun `an unreachable source exits 2`() {
        val result = run(FakeCatalog(boom = CatalogException("source unreachable")), "berserk")

        assertEquals(2, result.code)
        assertTrue(result.err.contains("source unreachable"))
    }

    @Test
    fun `an unknown source exits 2 without invoking the catalog`() {
        // No catalogs override: the real registry rejects the source.
        val result = captureOutput { CommandLine(SearchCommand()).execute("--source", "nope", "berserk") }

        assertEquals(2, result.code)
        assertTrue(result.err.contains("Unknown source"))
    }
}
