package command

import io.modernia.pixerion.domain.Catalog
import picocli.CommandLine
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class FindCommandTest {
    private fun run(
        catalog: Catalog,
        vararg args: String,
    ): CapturedRun =
        captureOutput {
            CommandLine(FindCommand().apply { catalogs = { catalog } }).execute(*args)
        }

    @Test
    fun `renders a found book and exits 0`() {
        val result = run(FakeCatalog(found = sampleBook()), "id-1")

        assertEquals(0, result.code)
        assertTrue(result.out.contains("Berserk"))
    }

    @Test
    fun `resolves an explicit scheme-id reference`() {
        val result = run(FakeCatalog(found = sampleBook()), "mangadex:id-1")

        assertEquals(0, result.code)
        assertTrue(result.out.contains("Berserk"))
    }

    @Test
    fun `a missing book exits 1 with a notice on stderr`() {
        val result = run(FakeCatalog(found = null), "absent")

        assertEquals(1, result.code)
        assertTrue(result.err.contains("No book found"))
    }
}
