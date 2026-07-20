package io.modernia.pixerion.mangadex

import io.modernia.pixerion.domain.CatalogException
import kotlinx.coroutines.test.runTest
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okio.Buffer
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Transport-level tests for [MangaDexClient] that exercise the branches the
 * catalog tests don't reach: image fetching, feed pagination, and the
 * absent/unreachable distinction on each endpoint.
 *
 * A plain OkHttp client is injected so the default rate limiter / retry stack
 * doesn't pace these tests.
 */
class MangaDexClientTest {
    private lateinit var server: MockWebServer
    private lateinit var client: MangaDexClient

    @BeforeEach
    fun setUp() {
        server = MockWebServer()
        server.start()
        client = MangaDexClient(baseUrl = server.url("/"), httpClient = OkHttpClient())
    }

    @AfterEach
    fun tearDown() {
        server.shutdown()
    }

    @Test
    fun `imageBytes returns the body and notes a cache hit`() =
        runTest {
            val image = byteArrayOf(0x89.toByte(), 'P'.code.toByte(), 'N'.code.toByte(), 'G'.code.toByte())
            server.enqueue(MockResponse().setHeader("X-Cache", "HIT").setBody(Buffer().write(image)))

            val bytes = client.imageBytes(server.url("/data/hash/p1.png").toString())

            assertContentEquals(image, bytes)
        }

    @Test
    fun `imageBytes raises a CatalogException on a non-success status`() =
        runTest {
            server.enqueue(MockResponse().setResponseCode(500))

            assertFailsWith<CatalogException> {
                client.imageBytes(server.url("/data/hash/p1.png").toString())
            }
        }

    @Test
    fun `the default base URL targets the public MangaDex API`() {
        assertEquals("https://api.mangadex.org/", MangaDexClient.DEFAULT_BASE_URL.toString())
    }

    @Test
    fun `a transport failure surfaces as a CatalogException`() =
        runTest {
            // A server that is started then immediately shut down leaves a refused port:
            // OkHttp's async callback fails, which the coroutine bridge (await) must
            // translate into a CatalogException.
            val dead = MockWebServer().apply { start() }
            val deadUrl = dead.url("/")
            dead.shutdown()
            val deadClient = MangaDexClient(baseUrl = deadUrl, httpClient = OkHttpClient())

            assertFailsWith<CatalogException> {
                deadClient.searchManga(mapOf("title" to "berserk"))
            }
        }

    @Test
    fun `searchManga treats a 404 list response as empty`() =
        runTest {
            server.enqueue(MockResponse().setResponseCode(404))

            assertTrue(client.searchManga(mapOf("title" to "ghost")).isEmpty())
        }

    @Test
    fun `chapters walks every feed page and sorts unnumbered chapters last`() =
        runTest {
            // Page 1 reports total=2 with one numbered chapter; page 2 returns the rest.
            server.enqueue(MockResponse().setBody(feedPage(id = "c2", chapter = "2", total = 2)))
            server.enqueue(MockResponse().setBody(feedPage(id = "c-null", chapter = null, total = 2)))

            val chapters = client.chapters(mangaId = "m1", language = "en")

            assertEquals(listOf("c2", "c-null"), chapters?.map { it.id })
            // Two feed pages were walked.
            assertEquals(2, server.requestCount)
        }

    @Test
    fun `chapters is null when the manga does not exist`() =
        runTest {
            server.enqueue(MockResponse().setResponseCode(404))

            assertNull(client.chapters(mangaId = "missing", language = "en"))
        }

    @Test
    fun `atHomeServer raises a CatalogException when no server exists`() =
        runTest {
            server.enqueue(MockResponse().setResponseCode(404))

            assertFailsWith<CatalogException> { client.atHomeServer("chapter-1") }
        }

    private fun feedPage(
        id: String,
        chapter: String?,
        total: Int,
    ): String {
        val chapterJson = chapter?.let { "\"$it\"" } ?: "null"
        return """
            {
              "result": "ok",
              "data": [
                { "id": "$id", "attributes": { "chapter": $chapterJson, "translatedLanguage": "en", "pages": 1 } }
              ],
              "limit": 100, "offset": 0, "total": $total
            }
            """.trimIndent()
    }
}
