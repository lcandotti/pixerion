package command

import org.junit.jupiter.api.io.TempDir
import picocli.CommandLine
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class BundleCommandTest {
    @TempDir
    lateinit var tmp: Path

    private fun run(vararg args: String): CapturedRun =
        captureOutput {
            CommandLine(BundleCommand())
                .setExecutionExceptionHandler(ExecutionErrorHandler())
                .execute(*args)
        }

    private fun writePage(
        book: Path,
        chapter: String,
        name: String,
        data: ByteArray = byteArrayOf(1),
    ) {
        val dir = book.resolve("chapters").resolve(chapter)
        Files.createDirectories(dir)
        Files.write(dir.resolve(name), data)
    }

    @Test
    fun `bundles a book's chapters and exits 0`() {
        val book = Files.createDirectories(tmp.resolve("Berserk"))
        writePage(book, "ch-1", "001.png")
        writePage(book, "ch-2", "001.jpg")

        val result = run(book.toString())

        assertEquals(0, result.code)
        assertTrue(result.out.contains("2 archives"))
        assertTrue(Files.exists(book.resolve("cbz").resolve("Berserk - ch-1.cbz")))
    }

    @Test
    fun `reports skipped archives on a re-run`() {
        val book = Files.createDirectories(tmp.resolve("Berserk"))
        writePage(book, "ch-1", "001.png")
        run(book.toString())

        val rerun = run(book.toString())

        assertEquals(0, rerun.code)
        assertTrue(rerun.out.contains("skipped"))
    }

    @Test
    fun `binds the output and overwrite options`() {
        val cmd = BundleCommand()
        CommandLine(cmd).parseArgs("--output", "/tmp/out", "--overwrite", "/some/book")

        assertEquals("/tmp/out", cmd.output)
        assertTrue(cmd.overwrite)
    }

    @Test
    fun `a non-directory argument exits 2`() {
        val result = run(tmp.resolve("does-not-exist").toString())

        assertEquals(2, result.code)
        assertTrue(result.err.contains("Not a directory"))
    }

    @Test
    fun `a book with no chapters exits 1`() {
        val book = Files.createDirectories(tmp.resolve("Empty"))

        val result = run(book.toString())

        assertEquals(1, result.code)
    }

    @Test
    fun `an unexpected IO failure exits 2 with a clean error, not a stack trace`() {
        val book = Files.createDirectories(tmp.resolve("Berserk"))
        writePage(book, "ch-1", "001.png")
        // An output "directory" that is actually a file makes createDirectories throw.
        val blocked = Files.write(tmp.resolve("blocked"), byteArrayOf(1))

        val result = run("-o", blocked.toString(), book.toString())

        assertEquals(2, result.code)
        assertTrue(result.err.contains("Error"))
        assertFalse(result.err.contains("at command."), "stack trace leaked to stderr")
    }
}
