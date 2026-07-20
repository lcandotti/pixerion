import command.BundleCommand
import command.DownloadCommand
import command.ExecutionErrorHandler
import command.FindCommand
import command.SearchCommand
import picocli.CommandLine
import picocli.CommandLine.Command
import kotlin.system.exitProcess

/**
 * Entry point. picocli parses the arguments, dispatches to a subcommand, and
 * returns its exit code. [ExecutionErrorHandler] turns any exception a command
 * doesn't handle itself into a clean error message and exit code `2`.
 *
 * The command classes are deliberately plain so picocli-codegen can generate
 * complete GraalVM reachability metadata for them at build time — see
 * `cli/build.gradle.kts`.
 */
fun main(args: Array<String>) {
    exitProcess(
        CommandLine(PixerionCommand())
            .setExecutionExceptionHandler(ExecutionErrorHandler())
            .execute(*args),
    )
}

@Command(
    name = "pixerion",
    mixinStandardHelpOptions = true,
    version = ["pixerion 0.1.0"],
    description = ["Query book catalogs from the command line."],
    subcommands = [SearchCommand::class, FindCommand::class, DownloadCommand::class, BundleCommand::class],
)
class PixerionCommand
