package command

import io.modernia.pixerion.domain.Catalog
import org.junit.jupiter.api.io.TempDir
import picocli.CommandLine
import java.io.IOException
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class DownloadCommandTest {
    @TempDir
    lateinit var output: Path

    private fun run(
        catalog: Catalog,
        vararg args: String,
    ): CapturedRun =
        captureOutput {
            CommandLine(DownloadCommand().apply { catalogs = { catalog } })
                .setExecutionExceptionHandler(ExecutionErrorHandler())
                .execute(*args)
        }

    @Test
    fun `writes pages to disk and exits 0`() {
        val catalog =
            FakeCatalog(
                found = sampleBook(),
                pages = listOf(FakePage("1", 1, "p1.png"), FakePage("1", 2, "p2.png")),
            )

        val result = run(catalog, "-o", output.toString(), "-w", "2", "id-1")

        assertEquals(0, result.code)
        assertTrue(result.out.contains("2 workers"))
        // Files were laid out under <output>/mangadex/Berserk/...
        val written = Files.walk(output).use { paths -> paths.filter { Files.isRegularFile(it) }.count() }
        assertEquals(2, written)
    }

    @Test
    fun `binds the output option`() {
        val cmd = DownloadCommand()
        CommandLine(cmd).parseArgs("-o", "/tmp/out", "id-1")

        assertEquals("/tmp/out", cmd.output)
    }

    @Test
    fun `a missing book exits 1`() {
        val result = run(FakeCatalog(found = null), "-o", output.toString(), "absent")

        assertEquals(1, result.code)
        assertTrue(result.err.contains("No book found"))
    }

    @Test
    fun `a book with no pages exits 1`() {
        val result = run(FakeCatalog(found = sampleBook(), pages = emptyList()), "-o", output.toString(), "id-1")

        assertEquals(1, result.code)
    }

    @Test
    fun `an unexpected failure exits 2 with a clean error, not a stack trace`() {
        val catalog = FakeCatalog(boom = IOException("disk full"))

        val result = run(catalog, "-o", output.toString(), "id-1")

        assertEquals(2, result.code)
        assertTrue(result.err.contains("disk full"))
        assertFalse(result.err.contains("at command."), "stack trace leaked to stderr")
    }

    @Test
    fun `a non-positive workers count exits 2 with a usage error`() {
        val result = run(FakeCatalog(found = sampleBook()), "-o", output.toString(), "-w", "0", "id-1")

        assertEquals(2, result.code)
        assertTrue(result.err.contains("--workers"))
    }
}
