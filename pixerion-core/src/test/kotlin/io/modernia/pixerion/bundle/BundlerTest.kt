package io.modernia.pixerion.bundle

import org.junit.jupiter.api.Test
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.attribute.PosixFilePermissions
import java.util.zip.ZipFile
import kotlin.io.path.name
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class BundlerTest {
    @Test
    fun `bundles one cbz per chapter into a separate directory`() {
        withBook { book ->
            writePage(book, "ch-1", "001.png", byteArrayOf(1))
            writePage(book, "ch-1", "002.png", byteArrayOf(2))
            writePage(book, "ch-2", "001.jpg", byteArrayOf(3))

            val summary = Bundler().bundle(book)

            assertEquals(2, summary.bundled)
            // archives land in the dedicated cbz/ layer, never beside the images
            val cbzDir = book.resolve("cbz")
            assertTrue(Files.isDirectory(cbzDir))
            assertFalse(Files.exists(book.resolve("chapters").resolve("ch-1").resolve("${book.name} - ch-1.cbz")))

            val chapterOne = cbzDir.resolve("${book.name} - ch-1.cbz")
            assertTrue(Files.exists(chapterOne))
            ZipFile(chapterOne.toFile()).use { zip ->
                assertEquals(listOf("ComicInfo.xml", "001.png", "002.png"), zip.entries().toList().map { it.name })
                assertContentEquals(byteArrayOf(1), zip.getInputStream(zip.getEntry("001.png")).readBytes())

                val comicInfo = zip.getInputStream(zip.getEntry("ComicInfo.xml")).readBytes().decodeToString()
                assertTrue(comicInfo.contains("<Series>My-Book</Series>"))
                assertTrue(comicInfo.contains("<Number>1</Number>"))
                assertTrue(comicInfo.contains("<PageCount>2</PageCount>"))
            }
        }
    }

    @Test
    fun `skips existing archives unless overwrite is set`() {
        withBook { book ->
            writePage(book, "ch-1", "001.png", byteArrayOf(1))

            assertEquals(1, Bundler().bundle(book).bundled)

            val rerun = Bundler().bundle(book)
            assertEquals(0, rerun.bundled)
            assertEquals(1, rerun.skipped)

            assertEquals(1, Bundler(overwrite = true).bundle(book).bundled)
        }
    }

    @Test
    fun `ignores non-chapter folders and empty chapters`() {
        withBook { book ->
            writePage(book, "ch-1", "001.png", byteArrayOf(1))
            Files.createDirectories(book.resolve("chapters").resolve("ch-empty"))
            Files.createDirectories(book.resolve("chapters").resolve("notes"))

            val summary = Bundler().bundle(book)

            assertEquals(1, summary.bundled)
            assertFalse(Files.exists(book.resolve("cbz").resolve("${book.name} - ch-empty.cbz")))
            assertFalse(Files.exists(book.resolve("cbz").resolve("${book.name} - notes.cbz")))
        }
    }

    @Test
    fun `a stray ComicInfo file in a chapter is not treated as a page`() {
        withBook { book ->
            writePage(book, "ch-1", "001.png", byteArrayOf(1))
            // Would collide with the generated metadata entry (duplicate ZIP entry).
            writePage(book, "ch-1", "ComicInfo.xml", byteArrayOf(9))

            val summary = Bundler().bundle(book)

            assertEquals(1, summary.bundled)
            ZipFile(book.resolve("cbz").resolve("${book.name} - ch-1.cbz").toFile()).use { zip ->
                assertEquals(listOf("ComicInfo.xml", "001.png"), zip.entries().toList().map { it.name })
                // The generated metadata won, not the stray file's bytes.
                val comicInfo = zip.getInputStream(zip.getEntry("ComicInfo.xml")).readBytes().decodeToString()
                assertTrue(comicInfo.contains("<Series>My-Book</Series>"))
            }
        }
    }

    @Test
    fun `a failed write leaves no temp file behind`() {
        withBook { book ->
            writePage(book, "ch-1", "001.png", byteArrayOf(1))
            val page = book.resolve("chapters").resolve("ch-1").resolve("001.png")
            Files.setPosixFilePermissions(page, emptySet()) // make the page unreadable

            try {
                assertFailsWith<Exception> { Bundler().bundle(book) }
            } finally {
                Files.setPosixFilePermissions(page, PosixFilePermissions.fromString("rw-r--r--"))
            }

            val leftovers =
                Files.list(book.resolve("cbz")).use { entries ->
                    entries.filter { it.name.endsWith(".tmp") }.toList()
                }
            assertTrue(leftovers.isEmpty(), "stale temp files: $leftovers")
        }
    }

    private fun writePage(
        book: Path,
        chapter: String,
        name: String,
        data: ByteArray,
    ) {
        val dir = book.resolve("chapters").resolve(chapter)
        Files.createDirectories(dir)
        Files.write(dir.resolve(name), data)
    }

    private fun withBook(block: (Path) -> Unit) {
        val root = Files.createTempDirectory("pixerion-bundle-test")
        try {
            val book = Files.createDirectories(root.resolve("My-Book"))
            block(book)
        } finally {
            root.toFile().deleteRecursively()
        }
    }
}
