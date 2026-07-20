import command.captureOutput
import picocli.CommandLine
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The root command is a plain picocli parent that registers the subcommands. Driving
 * it through `--help` covers its construction and wiring without invoking `main`
 * (which calls `exitProcess` and so cannot run inside the test JVM).
 */
class PixerionCommandTest {
    @Test
    fun `the root command prints help listing its subcommands and exits 0`() {
        val result = captureOutput { CommandLine(PixerionCommand()).execute("--help") }

        assertEquals(0, result.code)
        assertTrue(result.out.contains("search"))
        assertTrue(result.out.contains("download"))
        assertTrue(result.out.contains("bundle"))
    }
}
